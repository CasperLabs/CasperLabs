package io.casperlabs.client.configuration
import com.google.protobuf.ByteString
import java.io.{ByteArrayOutputStream, File, InputStream}
import java.nio.file.Files

import io.casperlabs.client.configuration.Options.DeployOptions
import io.casperlabs.casper.consensus.Deploy.{Arg, Code}
import Code.{Contract, StoredContract, StoredVersionedContract, WasmContract}
import io.casperlabs.casper.consensus.state.SemVer
import io.casperlabs.crypto.Keys.PublicKey
import io.casperlabs.crypto.codec.Base16
import org.apache.commons.io._

final case class ConnectOptions(
    host: String,
    portExternal: Int,
    portInternal: Int,
    nodeId: Option[String],
    tlsApiCertificate: Option[File],
    useTls: Option[Boolean]
)

/** Options to capture all the possible ways of passing one of the session or payment contracts. */
final case class CodeConfig(
    // Point at a file on disk.
    file: Option[File],
    // Hash of a stored contract.
    contractHash: Option[String],
    // Hash of a stored contract package.
    packageHash: Option[String],
    // Name of a stored contract.
    contractName: Option[String],
    // Name of a stored contract package.
    packageName: Option[String],
    // Arguments parsed from JSON
    args: Option[Seq[Arg]],
    // Name of the method that will be called during execution of the contract.
    entryPoint: Option[String],
    // Version of the contract that is being called. No version specified means most recent version.
    version: Option[Int],
    // Name of a pre-packaged contract in the client JAR.
    resource: Option[String] = None
)
object CodeConfig {
  val empty = CodeConfig(None, None, None, None, None, None, None, None, None)
}

/** Encapsulate reading session and payment contracts from disk or resources
  * before putting them into the format expected by the API.
  */
final case class DeployConfig(
    sessionOptions: CodeConfig,
    paymentOptions: CodeConfig,
    gasPrice: Long,
    paymentAmount: Option[BigInt],
    timeToLive: Option[Int],
    dependencies: List[ByteString],
    chainName: String
) {
  def session(defaultArgs: Seq[Arg] = Nil) = DeployConfig.toCode(sessionOptions, defaultArgs)
  def payment(defaultArgs: Seq[Arg] = Nil) = DeployConfig.toCode(paymentOptions, defaultArgs)

  def withSessionResource(resource: String) =
    copy(sessionOptions = sessionOptions.copy(resource = Some(resource)))
  def withPaymentResource(resource: String) =
    copy(paymentOptions = paymentOptions.copy(resource = Some(resource)))
}

object DeployConfig {
  def apply(args: DeployOptions): DeployConfig =
    DeployConfig(
      sessionOptions = CodeConfig(
        file = args.session.toOption,
        contractHash = args.sessionContractHash.toOption,
        packageHash = args.sessionPackageHash.toOption,
        contractName = args.sessionContractName.toOption,
        packageName = args.sessionPackageName.toOption,
        args = args.sessionArgs.toOption.map(_.args),
        entryPoint = args.sessionEntryPoint.toOption,
        version = args.sessionVer.toOption
      ),
      paymentOptions = CodeConfig(
        file = args.payment.toOption,
        contractHash = args.paymentContractHash.toOption,
        packageHash = args.paymentPackageHash.toOption,
        contractName = args.paymentContractName.toOption,
        packageName = args.paymentPackageName.toOption,
        args = args.paymentArgs.toOption.map(_.args),
        entryPoint = args.paymentEntryPoint.toOption,
        version = args.paymentVer.toOption
      ),
      gasPrice = args.gasPrice(),
      paymentAmount = args.paymentAmount.toOption,
      timeToLive = args.ttlMillis.toOption,
      dependencies = args.dependencies.toOption
        .getOrElse(List.empty)
        .map(d => ByteString.copyFrom(Base16.decode(d))),
      chainName = args.chainName()
    )

  val empty = DeployConfig(CodeConfig.empty, CodeConfig.empty, 0, None, None, List.empty, "")

  /** Produce a Deploy.Code DTO from the options.
    * 'defaultArgs' can be used by specialized commands such as `transfer` and `unbond`
    * to pass arguments they captured via dedicated CLI options, e.g. `--amount`, but
    * if the user sends explicit arguments via `--session-args` or `--payment-args`
    * they take precedence. This allows overriding the built-in contracts with custom ones.
    */
  private def toCode(opts: CodeConfig, defaultArgs: Seq[Arg]): Code = {
    val contract = opts.file.map { f =>
      val wasm = ByteString.copyFrom(Files.readAllBytes(f.toPath))
      Contract.WasmContract(WasmContract(wasm))
    } orElse {

      opts.contractHash.map { x =>
        Contract.StoredContract(
          StoredContract()
            .withContractHash(ByteString.copyFrom(Base16.decode(x)))
            .withEntryPoint(opts.entryPoint.getOrElse(""))
        )
      }
    } orElse {

      opts.contractName.map { x =>
        Contract.StoredContract(
          StoredContract()
            .withName(x)
            .withEntryPoint(opts.entryPoint.getOrElse(""))
        )
      }

    } orElse {

      opts.packageHash.map { x =>
        Contract.StoredVersionedContract(
          StoredVersionedContract()
            .withPackageHash(ByteString.copyFrom(Base16.decode(x)))
            .withVersion(opts.version.getOrElse(0))
            .withEntryPoint(opts.entryPoint.getOrElse(""))
        )
      }

    } orElse {

      opts.packageName.map { x =>
        Contract.StoredVersionedContract(
          StoredVersionedContract()
            .withName(x)
            .withVersion(opts.version.getOrElse(0))
            .withEntryPoint(opts.entryPoint.getOrElse(""))
        )
      }

    } orElse {
      opts.resource.map { x =>
        val wasm =
          ByteString.copyFrom(consumeInputStream(getClass.getClassLoader.getResourceAsStream(x)))
        Contract.WasmContract(WasmContract(wasm))
      }
    } getOrElse {
      Contract.Empty
    }

    val args = opts.args getOrElse defaultArgs

    Code(contract = contract, args = args)
  }

  private def consumeInputStream(is: InputStream): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    IOUtils.copy(is, baos)
    baos.toByteArray
  }
}

sealed trait Configuration
sealed trait Formatting {
  def bytesStandard: Boolean
  def json: Boolean
}

final case class MakeDeploy(
    from: Option[PublicKey],
    publicKey: Option[File],
    deployConfig: DeployConfig,
    deployPath: Option[File]
) extends Configuration

final case class SendDeploy(
    deploy: Array[Byte],
    waitForProcessed: Boolean,
    timeoutSeconds: Long,
    bytesStandard: Boolean,
    json: Boolean
) extends Configuration
    with Formatting

final case class PrintDeploy(
    deploy: Array[Byte],
    bytesStandard: Boolean,
    json: Boolean
) extends Configuration
    with Formatting

final case class Deploy(
    from: Option[PublicKey],
    deployConfig: DeployConfig,
    publicKey: Option[File],
    privateKey: Option[File],
    waitForProcessed: Boolean,
    timeoutSeconds: Long,
    bytesStandard: Boolean,
    json: Boolean
) extends Configuration
    with Formatting

/** Client command to sign a deploy.
  */
final case class Sign(
    deploy: Array[Byte],
    signedDeployPath: Option[File],
    publicKey: File,
    privateKey: File
) extends Configuration

final case object Propose extends Configuration

final case class ShowBlock(blockHash: String, bytesStandard: Boolean, json: Boolean)
    extends Configuration
    with Formatting
final case class ShowDeploys(blockHash: String, bytesStandard: Boolean, json: Boolean)
    extends Configuration
    with Formatting
final case class ShowDeploy(
    deployHash: String,
    bytesStandard: Boolean,
    json: Boolean,
    waitForProcessed: Boolean,
    timeoutSeconds: Long
) extends Configuration
    with Formatting
final case class ShowBlocks(depth: Int, bytesStandard: Boolean, json: Boolean)
    extends Configuration
    with Formatting
final case class Bond(
    amount: Long,
    deployConfig: DeployConfig,
    privateKey: File,
    waitForProcessed: Boolean,
    timeoutSeconds: Long,
    bytesStandard: Boolean,
    json: Boolean
) extends Configuration
    with Formatting
final case class Transfer(
    amount: Long,
    recipientPublicKey: PublicKey,
    deployConfig: DeployConfig,
    privateKey: File,
    waitForProcessed: Boolean,
    timeoutSeconds: Long,
    bytesStandard: Boolean,
    json: Boolean
) extends Configuration
    with Formatting
final case class Unbond(
    amount: Option[Long],
    deployConfig: DeployConfig,
    privateKey: File,
    waitForProcessed: Boolean,
    timeoutSeconds: Long,
    bytesStandard: Boolean,
    json: Boolean
) extends Configuration
    with Formatting
final case class VisualizeDag(
    depth: Int,
    showJustificationLines: Boolean,
    out: Option[String],
    streaming: Option[Streaming]
) extends Configuration
final case class Balance(address: String, blockhash: String) extends Configuration

sealed trait Streaming extends Product with Serializable
object Streaming {
  final case object Single   extends Streaming
  final case object Multiple extends Streaming
}

final case class Query(
    blockHash: String,
    keyType: String,
    key: String,
    path: String,
    bytesStandard: Boolean,
    json: Boolean
) extends Configuration
    with Formatting

final case class Keygen(
    outputDirectory: File
) extends Configuration

object Configuration {

  def parse(args: Array[String]): Option[(ConnectOptions, Configuration)] = {
    val options = Options(args)
    val connect = ConnectOptions(
      options.host(),
      options.port(),
      options.portInternal(),
      options.nodeId.toOption,
      options.tlsApiCertificate.toOption,
      options.useTls.toOption.map(_ == "true")
    )
    val conf = options.subcommand.map {
      case options.deploy =>
        Deploy(
          options.deploy.from.toOption,
          DeployConfig(options.deploy),
          options.deploy.publicKey.toOption,
          options.deploy.privateKey.toOption,
          options.deploy.waitForProcessed.getOrElse(false),
          options.deploy.timeoutSeconds.getOrElse(Options.TIMEOUT_SECONDS_DEFAULT.toSeconds),
          options.deploy.bytesStandard(),
          options.deploy.json()
        )
      case options.makeDeploy =>
        MakeDeploy(
          options.makeDeploy.from.toOption,
          options.makeDeploy.publicKey.toOption,
          DeployConfig(options.makeDeploy),
          options.makeDeploy.deployPath.toOption
        )
      case options.sendDeploy =>
        SendDeploy(
          options.sendDeploy.deployPath(),
          options.sendDeploy.waitForProcessed.getOrElse(false),
          options.sendDeploy.timeoutSeconds.getOrElse(Options.TIMEOUT_SECONDS_DEFAULT.toSeconds),
          options.sendDeploy.bytesStandard(),
          options.sendDeploy.json()
        )
      case options.printDeploy =>
        PrintDeploy(
          options.printDeploy.deployPath(),
          options.printDeploy.bytesStandard(),
          options.printDeploy.json()
        )
      case options.signDeploy =>
        Sign(
          options.signDeploy.deployPath(),
          options.signDeploy.signedDeployPath.toOption,
          options.signDeploy.publicKey(),
          options.signDeploy.privateKey()
        )
      case options.propose =>
        Propose
      case options.showBlock =>
        ShowBlock(
          options.showBlock.hash(),
          options.showBlock.bytesStandard(),
          options.showBlock.json()
        )
      case options.showDeploys =>
        ShowDeploys(
          options.showDeploys.hash(),
          options.showDeploys.bytesStandard(),
          options.showDeploys.json()
        )
      case options.showDeploy =>
        ShowDeploy(
          options.showDeploy.hash(),
          options.showDeploy.bytesStandard(),
          options.showDeploy.json(),
          options.showDeploy.waitForProcessed(),
          options.showDeploy.timeoutSeconds()
        )
      case options.showBlocks =>
        ShowBlocks(
          options.showBlocks.depth(),
          options.showBlocks.bytesStandard(),
          options.showBlocks.json()
        )
      case options.unbond =>
        Unbond(
          options.unbond.amount.toOption,
          DeployConfig(options.unbond),
          options.unbond.privateKey(),
          options.unbond.waitForProcessed(),
          options.unbond.timeoutSeconds(),
          options.unbond.bytesStandard(),
          options.unbond.json()
        )
      case options.bond =>
        Bond(
          options.bond.amount(),
          DeployConfig(options.bond),
          options.bond.privateKey(),
          options.bond.waitForProcessed(),
          options.bond.timeoutSeconds(),
          options.bond.bytesStandard(),
          options.bond.json()
        )
      case options.transfer =>
        Transfer(
          options.transfer.amount(),
          options.transfer.targetAccount(),
          DeployConfig(options.transfer),
          options.transfer.privateKey(),
          options.transfer.waitForProcessed(),
          options.transfer.timeoutSeconds(),
          options.transfer.bytesStandard(),
          options.transfer.json()
        )
      case options.visualizeBlocks =>
        VisualizeDag(
          options.visualizeBlocks.depth(),
          options.visualizeBlocks.showJustificationLines(),
          options.visualizeBlocks.out.toOption,
          options.visualizeBlocks.stream.toOption
        )
      case options.query =>
        Query(
          options.query.blockHash(),
          options.query.keyType(),
          options.query.key(),
          options.query.path(),
          options.query.bytesStandard(),
          options.query.json()
        )
      case options.balance =>
        Balance(
          options.balance.address(),
          options.balance.blockHash()
        )
      case options.keygen =>
        Keygen(options.keygen.outputDirectory())
    }
    conf map (connect -> _)
  }
}
