package io.casperlabs.storage.util
import java.nio.ByteBuffer

import com.google.protobuf.ByteString
import io.casperlabs.storage.BlockHash
import io.casperlabs.storage.dag.DagRepresentation.Validator
import io.casperlabs.crypto.Keys.PublicKeyHash

object byteOps {
  implicit class ByteBufferRich(val byteBuffer: ByteBuffer) extends AnyVal {
    def getBlockHash(): BlockHash = {
      val blockHashBytes = Array.ofDim[Byte](32)
      byteBuffer.get(blockHashBytes)
      ByteString.copyFrom(blockHashBytes)
    }

    def getValidator(): Validator = PublicKeyHash(getBlockHash())
  }

  implicit class IntRich(val value: Int) extends AnyVal {
    def toByteString: ByteString = {
      val byteBuffer = ByteBuffer.allocate(4)
      byteBuffer.putInt(value)
      ByteString.copyFrom(byteBuffer.array())
    }
  }

  implicit class LongRich(val value: Long) extends AnyVal {
    def toByteString: ByteString = {
      val byteBuffer = ByteBuffer.allocate(8)
      byteBuffer.putLong(value)
      ByteString.copyFrom(byteBuffer.array())
    }
  }
}
