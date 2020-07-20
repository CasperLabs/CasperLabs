package io.casperlabs.casper.helper
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.crypto.Keys
import io.casperlabs.crypto.signatures.SignatureAlgorithm
import io.casperlabs.casper.consensus.{Block, Signature}
import io.casperlabs.casper.util.ProtoUtil

import scala.util.Random

object BlockUtil {
  def resignBlock(b: Block, sk: Keys.PrivateKey): Block = {
    // NOTE: Not changing the validator public key, this will likely be invalid.
    val sigAlgorithm = SignatureAlgorithm.unapply(b.getSignature.sigAlgorithm).get
    val sig          = ByteString.copyFrom(sigAlgorithm.sign(b.blockHash.toByteArray, sk))
    b.withSignature(b.getSignature.withSig(sig))
  }

  def generateValidator(prefix: String = ""): Validator =
    Keys.PublicKeyHash(generateHash(prefix))

  def generateHash(prefix: String = ""): BlockHash =
    ByteString.copyFromUtf8(prefix + Random.nextString(20)).substring(0, 32)
}
