package io.casperlabs.client.configuration

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

import cats.syntax.option._
import guru.nidi.graphviz.engine.Format
import io.casperlabs.casper.consensus.state.SemVer
import io.casperlabs.client.BuildInfo
import io.casperlabs.crypto.Keys.PublicKey
import io.casperlabs.crypto.codec.{Base16, Base64}
import org.apache.commons.io.IOUtils
import org.rogach.scallop._

import scala.concurrent.duration._

object Options {
  val TIMEOUT_SECONDS_DEFAULT = 3.minutes

  val hexCheck: String => Boolean  = _.matches("[0-9a-fA-F]+")
  val hashCheck: String => Boolean = x => hexCheck(x) && x.length == 64

  val fileCheck: File => Boolean = file =>
    file.exists() && file.canRead && !file.isDirectory && file.isFile

  val directoryCheck: File => Boolean = dir => dir.exists() && dir.canWrite && dir.isDirectory

  trait DeployOptions { self: Subcommand =>
    def sessionRequired: Boolean = true
    def paymentPathName: String  = "payment"

    implicit val semVerConverter: ValueConverter[SemVer] = {
      val semVerRegex = """(\d+)\.(\d+)\.(\d+)""".r

      implicitly[ValueConverter[String]].flatMap { semVerStr =>
        semVerRegex
          .findFirstMatchIn(semVerStr)
          .map {
            case semVerRegex(major, minor, patch) => SemVer(major.toInt, minor.toInt, patch.toInt)
          }
          .fold[Either[String, Option[SemVer]]](
            Left(
              s"$semVerStr is not a valid semantic version that matches the pattern `major.minor.patch`."
            )
          )(semVer => Right(Option(semVer)))
      }
    }

    // Session code on disk.
    val session =
      opt[File](
        required = false,
        descr = "Path to the file with session code.",
        validate = fileCheck
      )

    val sessionContractHash =
      opt[String](
        required = false,
        descr = "Hash of the stored contract hash to be called in the session; base16 encoded.",
        validate = hashCheck
      )

    val sessionPackageHash = opt[String](
      required = false,
      descr =
        "Hash of the stored contract package hash to be called in the session; base16 encoded.",
      validate = hashCheck
    )

    val sessionContractName =
      opt[String](
        required = false,
        descr =
          "Name of the stored contract (associated with the executing account) to be called in the session."
      )

    val sessionPackageName =
      opt[String](
        required = false,
        descr =
          "Name of the stored contract package (associated with the executing account) to be called in the session."
      )

    val sessionArgs =
      opt[Args](
        required = false,
        descr =
          """JSON encoded list of Deploy.Arg protobuf messages for the session, e.g. '[{"name": "amount", "value": {"long_value": 123456}}]'"""
      )

    val sessionEntryPoint =
      opt[String](
        required = false,
        descr = "Name of the method that will be used when calling the contract."
      )

    val sessionVer =
      opt[Int](
        required = false,
        descr = "Version of the called contract."
      )

    val payment =
      opt[File](
        name = paymentPathName,
        required = false,
        descr = "Path to the file with payment code.",
        validate = fileCheck
      )

    val paymentContractHash =
      opt[String](
        required = false,
        descr = "Hash of the stored contract to be called in the payment; base16 encoded.",
        validate = hashCheck
      )

    val paymentPackageHash =
      opt[String](
        required = false,
        descr = "Hash of the stored contract package to be called in the payment; base16 encoded.",
        validate = hashCheck
      )

    val paymentContractName =
      opt[String](
        required = false,
        descr =
          "Name of the stored contract (associated with the executing account) to be called in the payment."
      )
    val paymentPackageName =
      opt[String](
        required = false,
        descr =
          "Name of the stored contract package (associated with the executing account) to be called in the payment."
      )

    val paymentUref =
      opt[String](
        required = false,
        descr = "URef of the stored contract to be called in the payment; base16 encoded.",
        validate = hashCheck
      )

    val paymentArgs =
      opt[Args](
        required = false,
        descr =
          """JSON encoded list of Deploy.Arg protobuf messages for the payment, e.g. '[{"name": "amount", "value": {"big_int": {"value": "123456", "bit_width": 512}}}]'"""
      )

    val paymentEntryPoint =
      opt[String](
        required = false,
        descr = "Name of the method that will be used when calling the contract."
      )

    val paymentVer =
      opt[Int](
        required = false,
        descr = "Version of the payment contract."
      )

    val gasPrice = opt[Long](
      descr = "The price of gas for this transaction in motes/gas. Must be positive integer.",
      validate = _ > 0,
      required = false,
      default = 10L.some
    )

    val paymentAmount = opt[BigInt](
      descr =
        "Standard payment amount. Use this with the default payment, or override with --payment-args if custom payment code is used.",
      validate = _ > 0,
      required = false
    )

    val ttlMillis = opt[Int](
      descr = "Time to live. Time (in milliseconds) that the deploy will remain valid for.",
      validate = _ > 0,
      required = false,
      noshort = true
    )

    val dependencies = opt[List[String]](
      descr = "List of deploy hashes (base16 encoded) which must be executed before this deploy.",
      validate = _.forall(hashCheck),
      required = false,
      noshort = true
    )

    val chainName = opt[String](
      descr =
        "Name of the chain to optionally restrict the deploy from being accidentally included anywhere else.",
      required = false,
      noshort = true,
      default = "".some
    )

    val waitForProcessed =
      opt[Boolean](
        descr = "Wait for deploy status PROCESSED or DISCARDED",
        default = false.some
      )

    val timeoutSeconds =
      opt[Long](descr = "Timeout in seconds.", default = Option(TIMEOUT_SECONDS_DEFAULT.toSeconds))

    addValidation {
      val storedPaymentCode = paymentContractHash.isDefined || paymentContractName.isDefined || paymentPackageHash.isDefined || paymentPackageName.isDefined
      val sessionProvided   = sessionPackageHash.isDefined || sessionContractHash.isDefined || sessionContractName.isDefined || sessionPackageName.isDefined

      if (sessionRequired && !session.isDefined && !sessionProvided)
        Left("No session contract options provided; please specify exactly one.")
      else if (storedPaymentCode && paymentAmount.isEmpty)
        Left(
          "No payment contract options provided; please specify --payment-amount for the standard payment."
        )
      else Right(())
    }
  }

  trait FormattingOptions { self: Subcommand =>
    val bytesStandard = opt[Boolean](
      required = false,
      descr =
        "Use standard encoding for bytes instead of default Base16, for JSON standard is Base64, for Protobuf text - ASCII escaped",
      default = false.some,
      name = "bytes-standard"
    )

    val json = opt[Boolean](
      required = false,
      descr = "Output in JSON instead of default Protobuf text encoding",
      default = false.some,
      name = "json",
      short = 'j'
    )
  }
}

final case class Options(arguments: Seq[String]) extends ScallopConf(arguments) {
  import Options._

  implicit val streamingConverter: ValueConverter[Streaming] = new ValueConverter[Streaming] {
    override def parse(s: List[(String, List[String])]): Either[String, Option[Streaming]] =
      s match {
        case List((_, List(v))) =>
          v match {
            case "single-output"    => Right(Some(Streaming.Single))
            case "multiple-outputs" => Right(Some(Streaming.Multiple))
            case _                  => Left("Failed to parse 'v', must be 'single-output' or 'multiple-outputs'")
          }
        case Nil => Right(None)
        case _   => Left("Provide 'single-output' or 'multiple-outputs'")
      }
    override val argType: ArgType.V = ArgType.SINGLE
  }

  implicit val publicKeyConverter: ValueConverter[PublicKey] = new ValueConverter[PublicKey] {
    override def parse(s: List[(String, List[String])]): Either[String, Option[PublicKey]] =
      s match {
        case (List((_, List(v)))) =>
          if (hashCheck(v)) {
            Right(Some(PublicKey(Base16.decode(v))))
          } else {
            Base64.tryDecode(v) match {
              case None        => Left("Could not parse as either base16 or base64 value.")
              case Some(bytes) => Right(Some(PublicKey(bytes)))
            }
          }
        case Nil => Right(None)
        case _   => Left("Provide a single base16 or base64 value.")
      }
    override val argType: ArgType.V = ArgType.SINGLE
  }

  version(
    s"CasperLabs Client ${BuildInfo.version} (${BuildInfo.gitHeadCommit.getOrElse("commit # unknown")})"
  )
  printedName = "casperlabs"

  val port =
    opt[Int](descr = "Port used for external gRPC API.", default = Option(40401))

  val portInternal =
    opt[Int](descr = "Port used for internal gRPC API.", default = Option(40402))

  val host =
    opt[String](
      descr = "Hostname or IP of node on which the gRPC service is running.",
      required = false,
      default = Option("localhost")
    )

  val nodeId =
    opt[String](
      descr =
        "Node ID (i.e. the Keccak256 hash of the public key the node uses for TLS) in case secure communication is based on the intra-node certificates.",
      required = false
    )

  val tlsApiCertificate =
    opt[File](
      descr =
        "Certificate of the node to be used for TLS communication. If the --node-id is also provided it will override the authority in the certificate, otherwise we expect the certificate to match the domain. " +
          "A certificate can be downloaded using OpenSSL: `openssl s_client -showcerts -connect localhost:40401 </dev/null 2>/dev/null | openssl x509 -outform PEM > node.crt`"
    )

  val useTls =
    opt[String](
      descr =
        "Optionally, force the TLS to be on or off. When it's on without node-id or tls-api-certificate it will rely on the default system certificate chain. [true | false]",
      validate = Set("true", "false").contains(_)
    )

  val makeDeploy = new Subcommand("make-deploy") with DeployOptions {
    descr("Constructs a deploy that can be signed and sent to a node.")

    val from = opt[PublicKey](
      descr =
        "The public key of the account which is the context of this deployment; base16 or base64 encoded.",
      required = false
    )

    val publicKey =
      opt[File](
        required = false,
        descr = "Path to the file with account public key (Ed25519)",
        validate = fileCheck
      )

    val deployPath =
      opt[File](
        required = false,
        descr = "Path to the file where deploy will be saved. " +
          "Optional, if not provided the deploy will be printed to STDOUT.",
        short = 'o'
      )

    addValidation {
      if (publicKey.isDefined && from.isDefined)
        Left("Both --from  and --public-key were provided. Please provide one of them.")
      else if (publicKey.isEmpty && from.isEmpty)
        Left("Neither --from nor --public-key were provided. Please provide one of them.")
      else Right(())
    }
  }
  addSubcommand(makeDeploy)

  val sendDeploy = new Subcommand("send-deploy") with FormattingOptions {
    descr(
      "Deploy a smart contract source file to Casper on an existing running node. " +
        "The deploy will be packaged and sent as a block to the network depending " +
        "on the configuration of the Casper instance."
    )

    val deployPath = opt[File](
      required = false,
      descr = "Path to the file with signed Deploy.",
      validate = fileCheck,
      short = 'i'
    ).map(file => Files.readAllBytes(file.toPath))
      .orElse(Some(IOUtils.toByteArray(System.in)))

    val waitForProcessed =
      opt[Boolean](
        descr = "Wait for deploy status PROCESSED or DISCARDED",
        default = false.some,
        short = 'w'
      )

    val timeoutSeconds =
      opt[Long](descr = "Timeout in seconds.", default = Option(TIMEOUT_SECONDS_DEFAULT.toSeconds))

  }
  addSubcommand(sendDeploy)

  val deploy = new Subcommand("deploy") with DeployOptions with FormattingOptions {
    descr(
      "Constructs a Deploy and sends it to Casper on an existing running node. " +
        "The deploy will be packaged and sent as a block to the network depending " +
        "on the configuration of the Casper instance."
    )

    val from = opt[PublicKey](
      descr =
        "The public key of the account which is the context of this deployment; base16 or base64 encoded.",
      required = false
    )

    val publicKey =
      opt[File](
        required = false,
        descr = "Path to the file with account public key (Ed25519)",
        validate = fileCheck
      )

    val privateKey =
      opt[File](
        required = false,
        descr = "Path to the file with account private key (Ed25519)",
        validate = fileCheck
      )
  }
  addSubcommand(deploy)

  val signDeploy = new Subcommand("sign-deploy") {
    descr("Cryptographically signs a deploy. The signature is appended to existing approvals.")

    val publicKey =
      opt[File](
        required = true,
        descr = "Path to the file with account public key (Ed25519)",
        validate = fileCheck,
        noshort = true
      )

    val privateKey =
      opt[File](
        required = true,
        descr = "Path to the file with account private key (Ed25519)",
        validate = fileCheck,
        noshort = true
      )

    val signedDeployPath =
      opt[File](
        required = false,
        descr = "Path to the file where signed deploy will be saved." +
          "If not provided, the signed deploy will be sent to stdout.",
        short = 'o'
      )

    val deployPath =
      opt[File](
        required = false,
        descr = "Path to the deploy file.",
        validate = fileCheck,
        short = 'i'
      ).map(file => Files.readAllBytes(file.toPath))
        .orElse(Some(IOUtils.toByteArray(System.in)))
  }

  addSubcommand(signDeploy)

  val propose = new Subcommand("propose") {
    descr(
      "[DEPRECATED] Force a node to propose a block based on its accumulated deploys."
    )
  }
  addSubcommand(propose)

  val showBlock = new Subcommand("show-block") with FormattingOptions {
    descr(
      "View properties of a block known by Casper on an existing running node."
    )

    val hash =
      trailArg[String](
        name = "hash",
        required = true,
        descr = "Value of the block hash, base16 encoded.",
        validate = hexCheck
      )
  }
  addSubcommand(showBlock)

  val showDeploys = new Subcommand("show-deploys") with FormattingOptions {
    descr(
      "View deploys included in a block."
    )

    val hash =
      trailArg[String](
        name = "hash",
        required = true,
        descr = "Value of the block hash, base16 encoded.",
        validate = hexCheck
      )
  }
  addSubcommand(showDeploys)

  val showDeploy = new Subcommand("show-deploy") with FormattingOptions {
    descr(
      "View properties of a deploy known by Casper on an existing running node."
    )

    val hash =
      trailArg[String](
        name = "hash",
        required = true,
        descr = "Value of the deploy hash, base16 encoded.",
        validate = hashCheck
      )

    val waitForProcessed =
      opt[Boolean](
        descr = "Wait for deploy status PROCESSED or DISCARDED",
        default = false.some,
        short = 'w'
      )

    val timeoutSeconds =
      opt[Long](descr = "Timeout in seconds.", default = Option(TIMEOUT_SECONDS_DEFAULT.toSeconds))
  }
  addSubcommand(showDeploy)

  val printDeploy = new Subcommand("print-deploy") with FormattingOptions {
    descr("Print information of a deploy saved by 'make-deploy' command")

    val deployPath =
      opt[File](
        required = false,
        descr = "Path to the deploy file.",
        validate = fileCheck,
        short = 'i'
      ).map(file => Files.readAllBytes(file.toPath))
        .orElse(Some(IOUtils.toByteArray(System.in)))
  }
  addSubcommand(printDeploy)

  val showBlocks = new Subcommand("show-blocks") with FormattingOptions {
    descr(
      "View list of blocks in the current Casper view on an existing running node."
    )
    val depth =
      opt[Int](
        name = "depth",
        validate = _ > 0,
        descr = "lists blocks to the given depth in terms of block height",
        default = Option(1)
      )

  }
  addSubcommand(showBlocks)

  val unbond = new Subcommand("unbond") with DeployOptions with FormattingOptions {
    descr("Issues unbonding request")

    override def sessionRequired = false
    override def paymentPathName = "payment-path"

    val amount = opt[Long](
      name = "amount",
      validate = _ > 0,
      descr =
        "Amount of motes to unbond. If not provided then a request to unbond with full staked amount is made."
    )

    val privateKey =
      opt[File](
        descr = "Path to the file with account private key (Ed25519)",
        validate = fileCheck,
        required = true
      )
  }
  addSubcommand(unbond)

  val bond = new Subcommand("bond") with DeployOptions with FormattingOptions {
    descr("Issues bonding request")

    override def sessionRequired = false
    override def paymentPathName = "payment-path"

    val amount = opt[Long](
      name = "amount",
      validate = _ > 0,
      descr = "amount of motes to bond",
      required = true
    )

    val privateKey =
      opt[File](
        descr = "Path to the file with account private key (Ed25519)",
        validate = fileCheck,
        required = true
      )
  }
  addSubcommand(bond)

  val transfer = new Subcommand("transfer") with DeployOptions with FormattingOptions {
    descr("Transfers funds between accounts")

    override def sessionRequired = false
    override def paymentPathName = "payment-path"

    val amount = opt[Long](
      name = "amount",
      validate = _ > 0,
      descr =
        "Amount of motes to transfer. Note: a mote is the smallest, indivisible unit of a token.",
      required = true
    )

    val privateKey =
      opt[File](
        descr = "Path to the file with (from) account private key (Ed25519)",
        validate = fileCheck,
        required = true
      )

    val targetAccount =
      opt[PublicKey](
        descr = "The target account's public key; base16 or base64 encoded.",
        required = true
      )
  }
  addSubcommand(transfer)

  val visualizeBlocks = new Subcommand("vdag") {
    descr(
      "DAG in DOT format"
    )
    val depth =
      opt[Int](
        name = "depth",
        descr = "depth in terms of block height",
        validate = _ > 0,
        required = true
      )
    val showJustificationLines =
      opt[Boolean](
        descr = "if justification lines should be shown",
        default = false.some
      )

    val out = opt[String](
      descr =
        s"output image filename, outputs to stdout if not specified, must ends with one of the ${Format
          .values()
          .map(_.name().toLowerCase())
          .mkString(", ")}",
      validate = s => Format.values().map(_.name().toLowerCase).exists(s.endsWith)
    )

    val stream =
      opt[Streaming](
        descr =
          "subscribe to changes, '--out' has to specified, valid values are 'single-output', 'multiple-outputs'"
      )
  }
  addSubcommand(visualizeBlocks)

  val query = new Subcommand("query-state") with FormattingOptions {
    descr(
      "Query a value in the global state."
    )

    val blockHash =
      opt[String](
        name = "block-hash",
        descr = "Hash of the block to query the state of",
        required = true
      )

    val keyType =
      opt[String](
        name = "type",
        descr = "Type of base key. Must be one of 'hash', 'uref', 'address'.",
        validate = s => Set("hash", "uref", "address").contains(s.toLowerCase),
        default = Option("address")
      )

    val key =
      opt[String](
        name = "key",
        descr = "Base16 encoding of the base key.",
        required = true,
        validate = (key: String) => hexCheck(key)
      )

    val path =
      opt[String](
        name = "path",
        descr = "Path to the value to query. Must be of the form 'key1/key2/.../keyn'",
        default = Option("")
      )
  }
  addSubcommand(query)

  val balance = new Subcommand("balance") {
    descr("Returns the balance of the account at the specified block.")

    val blockHash =
      opt[String](
        name = "block-hash",
        descr = "Hash of the block to query the state of",
        required = true,
        validate = hexCheck
      )

    val address =
      opt[String](
        name = "address",
        descr = "Account's public key in hex.",
        required = true,
        validate = hexCheck
      )
  }
  addSubcommand(balance)

  val keygen = new Subcommand("keygen") {
    descr("Generates keys.")
    banner(
      """| Usage: casperlabs-client keygen <existingOutputDirectory>
         | Command will override existing files!
         | Generated files:
         |   node-id               # node ID as in casperlabs://c0a6c82062461c9b7f9f5c3120f44589393edf31@<NODE ADDRESS>?protocol=40400&discovery=40404
         |                         # derived from node.key.pem
         |   node.certificate.pem  # TLS certificate used for node-to-node interaction encryption
         |                         # derived from node.key.pem
         |   node.key.pem          # secp256r1 private key
         |   validator-id          # validator ID in Base64 format; can be used in accounts.csv
         |                         # derived from validator.public.pem
         |   validator-id-hex      # validator ID in hex, derived from validator.public.pem
         |   validator-private.pem # ed25519 private key
         |   validator-public.pem  # ed25519 public key""".stripMargin
    )

    val outputDirectory = trailArg[File](
      descr = "Output directory for keys. Should already exists.",
      validate = directoryCheck,
      required = true
    )
  }
  addSubcommand(keygen)

  verify()
}
