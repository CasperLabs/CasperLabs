package io.casperlabs.client
import java.io._
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import cats.Show
import cats.effect.{Resource, Sync, Timer}
import cats.implicits._
import com.google.protobuf.ByteString
import eu.timepit.refined._
import eu.timepit.refined.numeric._
import guru.nidi.graphviz.engine._
import io.casperlabs.casper.consensus
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.casper.consensus.state
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.client.configuration.{DeployConfig, Options, Streaming}
import io.casperlabs.crypto.Keys.{PrivateKey, PublicKey}
import io.casperlabs.crypto.codec.{Base16, Base64}
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.crypto.util.{CertificateHelper, CertificatePrinter}
import io.casperlabs.shared.FilesAPI
import io.casperlabs.models.cltype
import io.casperlabs.models.DeployImplicits._
import org.apache.commons.io._
import scalapb_circe.JsonFormat
import scala.concurrent.duration._
import scala.language.higherKinds
import scala.util.control.NonFatal
import scala.util.Try

object DeployRuntime {

  val BONDING_WASM_FILE   = "bonding.wasm"
  val UNBONDING_WASM_FILE = "unbonding.wasm"
  val TRANSFER_WASM_FILE  = "transfer_to_account_u512.wasm"

  def propose[F[_]: Sync: DeployService](
      exit: Boolean = true,
      ignoreOutput: Boolean = false
  ): F[Unit] =
    gracefulExit(
      for {
        _      <- Sync[F].delay(System.err.println("Warning: command propose is deprecated."))
        result <- DeployService[F].propose()
      } yield result.map(hash => s"Response: Success! Block $hash created and added."),
      exit,
      ignoreOutput
    )

  def showBlock[F[_]: Sync: DeployService](
      hash: String,
      bytesStandard: Boolean,
      json: Boolean
  ): F[Unit] =
    gracefulExit(
      DeployService[F]
        .showBlock(hash)
        .map(_.map(Printer.print(_, bytesStandard, json)))
    )

  def showDeploys[F[_]: Sync: DeployService](
      hash: String,
      bytesStandard: Boolean,
      json: Boolean
  ): F[Unit] =
    gracefulExit(DeployService[F].showDeploys(hash, bytesStandard, json))

  def showDeploy[F[_]: Sync: DeployService](
      hash: String,
      bytesStandard: Boolean,
      json: Boolean,
      waitForProcessed: Boolean,
      timeout: FiniteDuration
  ): F[Unit] =
    gracefulExit(
      DeployService[F].showDeploy(hash, bytesStandard, json, waitForProcessed, timeout)
    )

  def showBlocks[F[_]: Sync: DeployService](
      depth: Int,
      bytesStandard: Boolean,
      json: Boolean
  ): F[Unit] =
    gracefulExit(DeployService[F].showBlocks(depth, bytesStandard, json))

  private def arg(name: String, value: state.CLValueInstance) = Deploy.Arg(name, value.some)

  private def longArg(name: String, value: Long) =
    arg(name, cltype.protobuf.Mappings.toProto(cltype.CLValueInstance.U64(value)))

  private def bigIntArg(name: String, value: BigInt) = {
    val nn = refineV[NonNegative](value).right.get
    arg(name, cltype.protobuf.Mappings.toProto(cltype.CLValueInstance.U512(nn)))
  }

  private def bytesArg(name: String, value: Array[Byte]) =
    arg(
      name,
      cltype.protobuf.Mappings.toProto(
        cltype.CLValueInstance
          .FixedList(
            value.toSeq.map(cltype.CLValueInstance.U8.apply),
            cltype.CLType.U8,
            value.length
          )
          .right
          .get
      )
    )

  def unbond[F[_]: Sync: DeployService](
      maybeAmount: Option[Long],
      deployConfig: DeployConfig,
      privateKeyFile: File,
      waitForProcessed: Boolean,
      timeout: FiniteDuration,
      bytesStandard: Boolean = false,
      json: Boolean = false
  ): F[Unit] =
    for {
      rawPrivateKey <- readFileAsString[F](privateKeyFile)
      amountValue = cltype.CLValueInstance
        .Option(maybeAmount.map(cltype.CLValueInstance.U64.apply), cltype.CLType.U64)
        .right
        .get
      amountProto = cltype.protobuf.Mappings.toProto(amountValue)
      _ <- deployFileProgram[F](
            from = None,
            deployConfig.withSessionResource(UNBONDING_WASM_FILE),
            maybeEitherPublicKey = None,
            maybeEitherPrivateKey = rawPrivateKey.asLeft[PrivateKey].some,
            sessionArgs = List(arg("amount", amountProto)),
            waitForProcessed = waitForProcessed,
            timeout = timeout,
            bytesStandard = bytesStandard,
            json = json
          )
    } yield ()

  def bond[F[_]: Sync: DeployService](
      amount: Long,
      deployConfig: DeployConfig,
      privateKeyFile: File,
      waitForProcessed: Boolean,
      timeout: FiniteDuration,
      bytesStandard: Boolean = false,
      json: Boolean = false
  ): F[Unit] =
    for {
      rawPrivateKey <- readFileAsString[F](privateKeyFile)
      _ <- deployFileProgram[F](
            from = None,
            deployConfig.withSessionResource(BONDING_WASM_FILE),
            maybeEitherPublicKey = None,
            maybeEitherPrivateKey = rawPrivateKey.asLeft[PrivateKey].some,
            sessionArgs = List(longArg("amount", amount)),
            waitForProcessed = waitForProcessed,
            timeout = timeout,
            bytesStandard = bytesStandard,
            json = json
          )
    } yield ()

  def queryState[F[_]: Sync: DeployService](
      blockHash: String,
      keyVariant: String,
      keyValue: String,
      path: String,
      bytesStandard: Boolean,
      json: Boolean
  ): F[Unit] =
    gracefulExit({
      DeployService[F]
        .queryState(blockHash, keyVariant, keyValue, path)
        .map(_.map(Printer.print(_, bytesStandard, json)))
    })

  def balance[F[_]: Sync: DeployService](address: String, blockHash: String): F[Unit] =
    gracefulExit {
      (for {
        value <- DeployService[F].queryState(blockHash, "address", address, "").rethrow
        _ <- Sync[F]
              .raiseError(new IllegalStateException(s"Expected Account type value under $address."))
              .whenA(!value.value.isAccount)
        account = value.getAccount
        localKeyValue = {
          val purseAddrHex = Base16.encode(account.getMainPurse.uref.toByteArray)
          s"$purseAddrHex"
        }
        localQuery  <- DeployService[F].queryState(blockHash, "hash", localKeyValue, "").rethrow
        balanceURef = localQuery.getClValue.getValue.getKey.getUref
        urefQuery <- DeployService[F]
                      .queryState(
                        blockHash,
                        "hash",
                        Base16.encode(balanceURef.uref.toByteArray),
                        ""
                      )
                      .rethrow
        balance = urefQuery.getClValue.getValue.getU512
      } yield s"Balance:\n$address : ${balance.value}").attempt
    }

  def visualizeDag[F[_]: Sync: DeployService: Timer](
      depth: Int,
      showJustificationLines: Boolean,
      maybeOut: Option[String],
      maybeStreaming: Option[Streaming]
  ): F[Unit] =
    gracefulExit({
      def askDag =
        DeployService[F]
          .visualizeDag(depth, showJustificationLines)
          .rethrow

      val useJdkRenderer = Sync[F].delay(Graphviz.useDefaultEngines())

      def writeToFile(out: String, format: Format, dag: String) =
        Sync[F].delay(
          Graphviz
            .fromString(dag)
            .totalMemory(1000000000)
            .render(format)
            .toFile(new File(s"$out"))
        ) >> Sync[F].delay(println(s"Wrote $out"))

      val sleep = Timer[F].sleep(5.seconds)

      def subscribe(
          out: String,
          streaming: Streaming,
          format: Format,
          index: Int = 0,
          prevDag: Option[String] = None
      ): F[Unit] =
        askDag >>= {
          dag =>
            if (prevDag.contains(dag)) {
              sleep >>
                subscribe(out, streaming, format, index, prevDag)
            } else {
              val filename = streaming match {
                case Streaming.Single => out
                case Streaming.Multiple =>
                  val extension = "." + out.split('.').last
                  out.stripSuffix(extension) + s"_$index" + extension
              }
              writeToFile(filename, format, dag) >>
                sleep >>
                subscribe(out, streaming, format, index + 1, dag.some)
            }
        }

      def parseFormat(out: String) = Sync[F].delay(Format.valueOf(out.split('.').last.toUpperCase))

      val eff = (maybeOut, maybeStreaming) match {
        case (None, None) =>
          askDag
        case (Some(out), None) =>
          useJdkRenderer >>
            askDag >>= { dag =>
            parseFormat(out) >>=
              (format => writeToFile(out, format, dag).map(_ => "Success"))
          }
        case (Some(out), Some(streaming)) =>
          useJdkRenderer >>
            parseFormat(out) >>=
            (subscribe(out, streaming, _).map(_ => "Success"))
        case (None, Some(_)) =>
          Sync[F].raiseError[String](new Throwable("--out must be specified if --stream"))
      }

      eff.attempt
    })

  def transferCLI[F[_]: Sync: DeployService: FilesAPI](
      deployConfig: DeployConfig,
      privateKeyFile: File,
      recipientPublicKey: PublicKey,
      amount: Long,
      waitForProcessed: Boolean,
      timeout: FiniteDuration,
      bytesStandard: Boolean,
      json: Boolean
  ): F[Unit] =
    for {
      rawPrivateKey <- readFileAsString[F](privateKeyFile)
      privateKey <- MonadThrowable[F].fromOption(
                     Ed25519.tryParsePrivateKey(rawPrivateKey),
                     new IllegalArgumentException(
                       s"Failed to parse private key file ${privateKeyFile.getPath()}"
                     )
                   )
      publicKey <- MonadThrowable[F].fromOption(
                    Ed25519.tryToPublic(privateKey),
                    new RuntimeException(
                      "Failed to compute Ed25519 public key from given private key."
                    )
                  )
      _ <- transfer[F](
            deployConfig,
            publicKey,
            privateKey,
            recipientPublicKey,
            amount,
            waitForProcessed = waitForProcessed,
            timeout = timeout,
            bytesStandard = bytesStandard,
            json = json
          )
    } yield ()

  def transfer[F[_]: Sync: DeployService: FilesAPI](
      deployConfig: DeployConfig,
      senderPublicKey: PublicKey,
      senderPrivateKey: PrivateKey,
      recipientPublicKey: PublicKey,
      amount: Long,
      exit: Boolean = true,
      ignoreOutput: Boolean = false,
      waitForProcessed: Boolean,
      timeout: FiniteDuration,
      bytesStandard: Boolean = false,
      json: Boolean = false
  ): F[Unit] =
    deployFileProgram[F](
      from = None,
      deployConfig.withSessionResource(TRANSFER_WASM_FILE),
      maybeEitherPublicKey = senderPublicKey.asRight[String].some,
      maybeEitherPrivateKey = senderPrivateKey.asRight[String].some,
      List(
        bytesArg("account", recipientPublicKey),
        bigIntArg("amount", amount)
      ),
      exit,
      ignoreOutput,
      waitForProcessed = waitForProcessed,
      timeout = timeout,
      bytesStandard = bytesStandard,
      json = json
    )

  private def readKey[F[_]: Sync, A](keyFile: File, keyType: String, f: String => Option[A]): F[A] =
    readFileAsString[F](keyFile) >>= { key =>
      Sync[F].fromOption[A](
        f(key),
        new IllegalArgumentException(s"$keyFile was not valid Ed25519 $keyType key.")
      )
    }

  /** Signs a deploy with provided private key and writes it to the file (if provided),
    * or prints to STDOUT.
    */
  def sign[F[_]: Sync](
      deployBA: Array[Byte],
      output: Option[File],
      publicKeyFile: File,
      privateKeyFile: File
  ): F[Unit] = {

    val program = for {
      publicKey    <- readKey[F, PublicKey](publicKeyFile, "public", Ed25519.tryParsePublicKey _)
      privateKey   <- readKey[F, PrivateKey](privateKeyFile, "private", Ed25519.tryParsePrivateKey _)
      deploy       <- Sync[F].fromTry(Try(Deploy.parseFrom(deployBA)))
      signedDeploy = deploy.approve(Ed25519, privateKey, publicKey)
      _            <- writeDeploy(signedDeploy, output)
    } yield ()

    gracefulExit(program.attempt)
  }

  /** Constructs a [[Deploy]] from the provided arguments and writes it to a file (or STDOUT).
    */
  def makeDeploy[F[_]: Sync](
      from: PublicKey,
      deployConfig: DeployConfig,
      sessionArgs: Seq[Deploy.Arg]
  ): Deploy = {
    val session = deployConfig.session(sessionArgs)
    // By default send empty payment code to indicate that EE system payment contract should be used.
    val payment = deployConfig
      .payment(
        deployConfig.paymentAmount.map(bigIntArg("amount", _)).toList
      )

    val accountPublicKeyHash = Ed25519.publicKeyHash(from)

    consensus
      .Deploy()
      .withHeader(
        consensus.Deploy
          .Header()
          .withTimestamp(System.currentTimeMillis)
          .withAccountPublicKeyHash(ByteString.copyFrom(accountPublicKeyHash))
          .withGasPrice(deployConfig.gasPrice)
          .withTtlMillis(deployConfig.timeToLive.getOrElse(0))
          .withDependencies(deployConfig.dependencies)
          .withChainName(deployConfig.chainName)
      )
      .withBody(
        consensus.Deploy
          .Body()
          .withSession(session)
          .withPayment(payment)
      )
      .withHashes
  }

  /** Writes deploy to either a file (if `deployPath` is defined) or STDOUT.
    */
  def writeDeploy[F[_]: Sync](deploy: Deploy, deployPath: Option[File]): F[Unit] =
    gracefulExit(
      deployPath.fold {
        Sync[F].delay(deploy.writeTo(System.out)).attempt
      } { file =>
        Sync[F].delay(Files.write(file.toPath, deploy.toByteArray)).void.attempt
      },
      ignoreOutput = true
    )

  def sendDeploy[F[_]: Sync: DeployService](
      deployBA: Array[Byte],
      waitForProcessed: Boolean,
      timeout: FiniteDuration,
      bytesStandard: Boolean = false,
      json: Boolean = false
  ): F[Unit] =
    gracefulExit {
      for {
        deploy <- Sync[F].fromTry(Try(Deploy.parseFrom(deployBA)))
        result <- if (waitForProcessed) {
                   deployAndWaitForProcessed[F](deploy, timeout, bytesStandard, json)
                 } else {
                   DeployService[F].deploy(deploy)
                 }
      } yield result
    }

  /**
    *
    * @param bytesStandard Use standard Base64 for JSON or ASCII escaped for Protobuf bytes encoding instead of Base16
    * @param json          Use JSON instead of Protobuf text format
    */
  def printDeploy[F[_]: Sync: DeployService](
      deployBA: Array[Byte],
      bytesStandard: Boolean,
      json: Boolean
  ): F[Unit] =
    gracefulExit {
      (for {
        deploy <- MonadThrowable[F].fromTry(Try(Deploy.parseFrom(deployBA)))
      } yield Printer.print(deploy, bytesStandard, json)).attempt
    }

  def deployAndWaitForProcessed[F[_]: Sync: DeployService](
      deploy: Deploy,
      timeout: FiniteDuration = Options.TIMEOUT_SECONDS_DEFAULT,
      bytesStandard: Boolean = false,
      json: Boolean = false
  ): F[Either[Throwable, String]] =
    for {
      message <- DeployService[F].deploy(deploy).rethrow
      _       <- Sync[F].delay(println(message))
      result <- DeployService[F]
                 .showDeploy(
                   Base16.encode(deploy.deployHash.toByteArray),
                   bytesStandard,
                   json,
                   waitForProcessed = true,
                   timeout
                 )
    } yield result

  def deployFileProgram[F[_]: Sync: DeployService](
      from: Option[PublicKey],
      deployConfig: DeployConfig,
      maybeEitherPublicKey: Option[Either[String, PublicKey]],
      maybeEitherPrivateKey: Option[Either[String, PrivateKey]],
      sessionArgs: Seq[Deploy.Arg] = Seq.empty,
      exit: Boolean = true,
      ignoreOutput: Boolean = false,
      waitForProcessed: Boolean = false,
      timeout: FiniteDuration = Options.TIMEOUT_SECONDS_DEFAULT,
      bytesStandard: Boolean = false,
      json: Boolean = false
  ): F[Unit] = {
    val maybePrivateKey = maybeEitherPrivateKey.fold(none[PrivateKey]) { either =>
      either.fold(Ed25519.tryParsePrivateKey, _.some)
    }
    val maybePublicKey = maybeEitherPublicKey.flatMap { either =>
      // In the future with multiple signatures and recovery the
      // account public key  can be different then the private key
      // we sign with, so not checking that they match.
      either.fold(Ed25519.tryParsePublicKey, _.some)
    } orElse maybePrivateKey.flatMap(Ed25519.tryToPublic)

    val deploy = for {
      accountPublicKey <- Sync[F].fromOption(
                           from orElse maybePublicKey,
                           new IllegalArgumentException("--from or --public-key must be present")
                         )
    } yield {
      val deploy =
        makeDeploy(accountPublicKey, deployConfig, sessionArgs)
      (maybePrivateKey, maybePublicKey).mapN(deploy.approve(Ed25519, _, _)) getOrElse deploy
    }

    gracefulExit(
      deploy
        .flatMap({ deploy =>
          if (waitForProcessed) {
            deployAndWaitForProcessed[F](deploy, timeout, bytesStandard, json)
          } else {
            DeployService[F].deploy(deploy)
          }
        })
        .recover {
          case NonFatal(ex) =>
            Left(new RuntimeException(s"Couldn't make deploy, reason: ${ex.getMessage}", ex))
        },
      exit,
      ignoreOutput
    )
  }

  private[client] def gracefulExit[F[_]: Sync, A: Show](
      program: F[Either[Throwable, A]],
      exit: Boolean = true,
      ignoreOutput: Boolean = false
  ): F[Unit] =
    for {
      result <- Sync[F].attempt(program)
      _ <- result.joinRight match {
            case Left(ex) =>
              Sync[F].delay {
                System.err.println(processError(ex).getMessage)
                System.exit(1)
              }
            case Right(msg) =>
              Sync[F].delay {
                if (!ignoreOutput) {
                  println(Show[A].show(msg))
                }
                if (exit) {
                  System.exit(0)
                }
              }
          }
    } yield ()

  private def processError(t: Throwable): Throwable =
    Option(t.getCause).getOrElse(t)

  def readFileAsString[F[_]: Sync](file: File): F[String] = Sync[F].delay {
    new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)
  }

  def keygen[F[_]: Sync](
      outputDirectory: File
  ): F[Unit] = {
    val path                     = outputDirectory.toPath
    val validatorPrivPath: Path  = path.resolve("validator-private.pem")
    val validatorPubPath: Path   = path.resolve("validator-public.pem")
    val validatorIdPath: Path    = path.resolve("validator-id")
    val validatorIdHexPath: Path = path.resolve("validator-id-hex")
    val nodePrivPath: Path       = path.resolve("node.key.pem")
    val nodeCertPath: Path       = path.resolve("node.certificate.pem")
    val nodeIdPath: Path         = path.resolve("node-id")

    val program = for {
      (valPriv, valPub) <- Sync[F].delay(Ed25519.newKeyPair)
      _                 <- writeToFile(validatorPrivPath, printValidatorPriv(valPriv))
      _                 <- writeToFile(validatorPubPath, printValidatorPub(valPub))
      _                 <- writeToFile(validatorIdPath, Base64.encode(valPub))
      _                 <- writeToFile(validatorIdHexPath, Base16.encode(valPub))
      nodeKeyPair       <- Sync[F].delay(CertificateHelper.generateKeyPair(false))
      nodeCert          <- Sync[F].delay(CertificateHelper.generate(nodeKeyPair))
      _                 <- writeToFile(nodePrivPath, CertificatePrinter.printPrivateKey(nodeKeyPair.getPrivate))
      _                 <- writeToFile(nodeCertPath, CertificatePrinter.print(nodeCert))
      nodeId <- Sync[F].fromOption(
                 CertificateHelper.publicAddress(nodeKeyPair.getPublic),
                 new IllegalArgumentException("Certificate's public key is corrupted.")
               )
      _ <- writeToFile(nodeIdPath, Base16.encode(nodeId))

    } yield s"Keys successfully created in directory: ${path.toAbsolutePath.toString}"

    gracefulExit(program.attempt)
  }

  private def writeToFile[F[_]: Sync](path: Path, text: String): F[Unit] =
    Resource
      .fromAutoCloseable {
        Sync[F].delay { new BufferedWriter(new FileWriter(new File(path.toString))) }
      }
      .use(buff => Sync[F].delay(buff.write(text)))

  private def printValidatorPriv(privateKey: PrivateKey): String =
    s"""-----BEGIN PRIVATE KEY-----
       |${Base64.encode(privateKey)}
       |-----END PRIVATE KEY-----
       |""".stripMargin

  private def printValidatorPub(publicKey: PublicKey): String =
    s"""-----BEGIN PUBLIC KEY-----
       |${Base64.encode(publicKey)}
       |-----END PUBLIC KEY-----
       |""".stripMargin
}
