package io.casperlabs.models.bytesrepr

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets
import simulacrum.typeclass

@typeclass trait ToBytes[T] {
  def toBytes(t: T): Array[Byte]
}

object ToBytes {
  def toBytes[T: ToBytes](t: T): Array[Byte] = ToBytes[T].toBytes(t)

  implicit val toBytesBool: ToBytes[Boolean] = new ToBytes[Boolean] {
    override def toBytes(b: Boolean): Array[Byte] =
      if (b) Array(Constants.Boolean.TRUE_TAG)
      else Array(Constants.Boolean.FALSE_TAG)
  }

  implicit val toBytesByte: ToBytes[Byte] = new ToBytes[Byte] {
    override def toBytes(i: Byte): Array[Byte] = Array(i)
  }

  implicit val toBytesInt: ToBytes[Int] = new ToBytes[Int] {
    override def toBytes(i: Int): Array[Byte] =
      ByteBuffer
        .allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(i)
        .array()
  }

  implicit val toBytesLong: ToBytes[Long] = new ToBytes[Long] {
    override def toBytes(i: Long): Array[Byte] =
      ByteBuffer
        .allocate(8)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putLong(i)
        .array()
  }

  implicit val toBytesBigInt: ToBytes[BigInt] = new ToBytes[BigInt] {
    override def toBytes(i: BigInt): Array[Byte] = {
      val bigEndian           = i.toByteArray
      val nonZeroLittleEndian = bigEndian.dropWhile(_ == 0).reverse
      val numBytes            = nonZeroLittleEndian.length.toByte

      numBytes +: nonZeroLittleEndian
    }
  }

  implicit val toBytesUnit: ToBytes[Unit] = new ToBytes[Unit] {
    override def toBytes(x: Unit): Array[Byte] = Array.empty[Byte]
  }

  implicit val toBytesString: ToBytes[String] = new ToBytes[String] {
    override def toBytes(s: String): Array[Byte] = {
      val bytes = s.getBytes(StandardCharsets.UTF_8)
      ToBytes[Int].toBytes(bytes.length) ++ bytes
    }
  }

  implicit def toBytesOption[T: ToBytes]: ToBytes[Option[T]] = new ToBytes[Option[T]] {
    override def toBytes(value: Option[T]): Array[Byte] = value match {
      case None    => Array(Constants.Option.NONE_TAG)
      case Some(t) => Constants.Option.SOME_TAG +: ToBytes[T].toBytes(t)
    }
  }

  private def _toBytesSeq[T: ToBytes](list: Seq[T]): Array[Byte] = {
    val n     = list.size
    val bytes = list.flatMap(t => ToBytes[T].toBytes(t)).toArray
    ToBytes[Int].toBytes(n) ++ bytes
  }

  implicit def toBytesSeq[T: ToBytes]: ToBytes[Seq[T]] = new ToBytes[Seq[T]] {
    override def toBytes(list: Seq[T]): Array[Byte] = _toBytesSeq(list)
  }

  implicit def toBytesSet[T: ToBytes]: ToBytes[Set[T]] = new ToBytes[Set[T]] {
    override def toBytes(set: Set[T]): Array[Byte] = _toBytesSeq(set.toSeq)
  }

  implicit def toBytesIndexedSeq[T: ToBytes]: ToBytes[IndexedSeq[T]] = new ToBytes[IndexedSeq[T]] {
    override def toBytes(list: IndexedSeq[T]): Array[Byte] = _toBytesSeq(list)
  }

  implicit def toBytesEither[A: ToBytes, B: ToBytes]: ToBytes[Either[A, B]] =
    new ToBytes[Either[A, B]] {
      override def toBytes(value: Either[A, B]): Array[Byte] = value match {
        case Left(a)  => Constants.Either.LEFT_TAG +: ToBytes[A].toBytes(a)
        case Right(b) => Constants.Either.RIGHT_TAG +: ToBytes[B].toBytes(b)
      }
    }

  implicit def toBytesMap[K: ToBytes: Ordering, V: ToBytes]: ToBytes[Map[K, V]] =
    new ToBytes[Map[K, V]] {
      override def toBytes(map: Map[K, V]): Array[Byte] = {
        val sortedTuples = map.toVector.sortBy(_._1)
        ToBytes[Seq[(K, V)]].toBytes(sortedTuples)
      }
    }

  implicit def toBytesTuple1[T1: ToBytes]: ToBytes[Tuple1[T1]] =
    new ToBytes[Tuple1[T1]] {
      override def toBytes(tuple: Tuple1[T1]): Array[Byte] = ToBytes[T1].toBytes(tuple._1)
    }

  implicit def toBytesTuple2[T1: ToBytes, T2: ToBytes]: ToBytes[(T1, T2)] =
    new ToBytes[(T1, T2)] {
      override def toBytes(tuple: (T1, T2)): Array[Byte] = {
        val first  = ToBytes[T1].toBytes(tuple._1)
        val second = ToBytes[T2].toBytes(tuple._2)

        first ++ second
      }
    }

  implicit def toBytesTuple3[T1: ToBytes, T2: ToBytes, T3: ToBytes]: ToBytes[(T1, T2, T3)] =
    new ToBytes[(T1, T2, T3)] {
      override def toBytes(tuple: (T1, T2, T3)): Array[Byte] = {
        val first  = ToBytes[T1].toBytes(tuple._1)
        val second = ToBytes[T2].toBytes(tuple._2)
        val third  = ToBytes[T3].toBytes(tuple._3)

        first ++ second ++ third
      }
    }
}
