package io.casperlabs.models

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus
import io.casperlabs.casper.consensus.{Deploy, DeploySummary}
import io.casperlabs.crypto.Keys.{PrivateKey, PublicKey}
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.crypto.signatures.SignatureAlgorithm
import io.casperlabs.shared.Sorting.byteStringOrdering

object DeployImplicits {

  private def hash[T <: scalapb.GeneratedMessage](data: T): ByteString =
    ByteString.copyFrom(Blake2b256.hash(data.toByteArray))

  implicit class DeployOps(d: consensus.Deploy) {
    def withHashes: Deploy = {
      val h = d.getHeader.withBodyHash(hash(d.getBody))
      d.withHeader(h).withDeployHash(hash(h))
    }

    def approve(alg: SignatureAlgorithm, privateKey: PrivateKey, publicKey: PublicKey): Deploy = {
      val sig = alg.sign(d.deployHash.toByteArray, privateKey)
      val approval = consensus
        .Approval()
        .withApproverPublicKey(ByteString.copyFrom(publicKey))
        .withSignature(
          consensus.Signature().withSigAlgorithm(alg.name).withSig(ByteString.copyFrom(sig))
        )
      val approvals = d.approvals.toList :+ approval
      d.withApprovals(approvals.distinct.sortBy(_.approverPublicKey))
    }

    def getSummary: DeploySummary = DeploySummary(d.deployHash, d.header, d.approvals)
  }
}
