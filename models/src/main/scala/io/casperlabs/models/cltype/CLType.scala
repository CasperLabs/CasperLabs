package io.casperlabs.models.cltype

import cats.data.NonEmptyList
import io.casperlabs.models.bytesrepr.{BytesView, FromBytes, ToBytes}
import scala.annotation.tailrec
import scala.collection.immutable

sealed trait CLType

object CLType {
  case object Bool extends CLType {
    override def toString(): String = "Bool";
  }
  case object I32 extends CLType {
    override def toString(): String = "I32";
  }
  case object I64 extends CLType {
    override def toString(): String = "I64";
  }
  case object U8 extends CLType {
    override def toString(): String = "U8";
  }
  case object U32 extends CLType {
    override def toString(): String = "U32";
  }
  case object U64 extends CLType {
    override def toString(): String = "U64";
  }
  case object U128 extends CLType {
    override def toString(): String = "U128";
  }
  case object U256 extends CLType {
    override def toString(): String = "U256";
  }
  case object U512 extends CLType {
    override def toString(): String = "U512";
  }
  case object Unit extends CLType {
    override def toString(): String = "Unit";
  }
  case object String extends CLType {
    override def toString(): String = "String";
  }
  case object Key extends CLType {
    override def toString(): String = "Key";
  }
  case object URef extends CLType {
    override def toString(): String = "URef";
  }
  case class Option(t: CLType) extends CLType {
    override def toString(): String = "Option(" + t.toString() + ")";
  }
  case class List(t: CLType) extends CLType {
    override def toString(): String = "List(" + t.toString() + ")";
  }
  case class FixedList(t: CLType, length: Int) extends CLType {
    override def toString(): String = "FixedList(" + t.toString() + ")";
  }
  case class Result(ok: CLType, err: CLType) extends CLType {
    override def toString(): String =
      "Result{ ok: " + ok.toString() + ", err: " + err.toString() + "}";
  }
  case class Map(key: CLType, value: CLType) extends CLType {
    override def toString(): String =
      "Map{ key: " + key.toString() + ", value: " + value.toString() + "}";
  }
  case class Tuple1(t: CLType) extends CLType {
    override def toString(): String = "Tuple1(" + t.toString() + ")";
  }
  case class Tuple2(t1: CLType, t2: CLType) extends CLType {
    override def toString(): String = "Tuple2(" + t1.toString() + ", " + t2.toString() + ")";
  }
  case class Tuple3(t1: CLType, t2: CLType, t3: CLType) extends CLType {
    override def toString(): String =
      "Tuple3(" + t1.toString() + ", " + t2.toString() + ", " + t3.toString() + ")";
  }
  case object Any extends CLType {
    override def toString(): String = "Any";
  }

  // Type representing the list of things that need to be appended to the
  // serialized CLType (see `toBytesTailRec` below). The `Left` case represents the
  // length of a `FixedList`, while the `Right` case represents the inner type of
  // something like `List`, `Tuple2`, etc.
  private type LoopState = immutable.List[Either[Int, CLType]]

  @tailrec
  private def toBytesTailRec(state: LoopState, acc: IndexedSeq[Byte]): Array[Byte] = state match {
    case Nil => acc.toArray

    case Left(n) :: tail => toBytesTailRec(tail, acc ++ ToBytes.toBytes(n))

    case Right(t) :: tail =>
      t match {
        case Bool   => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_BOOL)
        case I32    => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_I32)
        case I64    => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_I64)
        case U8     => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_U8)
        case U32    => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_U32)
        case U64    => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_U64)
        case U128   => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_U128)
        case U256   => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_U256)
        case U512   => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_U512)
        case Unit   => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_UNIT)
        case String => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_STRING)
        case Key    => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_KEY)
        case URef   => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_UREF)

        case Option(inner) => toBytesTailRec(Right(inner) :: tail, acc :+ CL_TYPE_TAG_OPTION)

        case List(inner) => toBytesTailRec(Right(inner) :: tail, acc :+ CL_TYPE_TAG_LIST)

        case FixedList(inner, n) =>
          toBytesTailRec(Right(inner) :: Left(n) :: tail, acc :+ CL_TYPE_TAG_FIXED_LIST)

        case Result(ok, err) =>
          toBytesTailRec(Right(ok) :: Right(err) :: tail, acc :+ CL_TYPE_TAG_RESULT)

        case Map(key, value) =>
          toBytesTailRec(Right(key) :: Right(value) :: tail, acc :+ CL_TYPE_TAG_MAP)

        case Tuple1(inner) => toBytesTailRec(Right(inner) :: tail, acc :+ CL_TYPE_TAG_TUPLE1)

        case Tuple2(t1, t2) =>
          toBytesTailRec(Right(t1) :: Right(t2) :: tail, acc :+ CL_TYPE_TAG_TUPLE2)

        case Tuple3(t1, t2, t3) =>
          toBytesTailRec(Right(t1) :: Right(t2) :: Right(t3) :: tail, acc :+ CL_TYPE_TAG_TUPLE3)

        case Any => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_ANY)
      }
  }

  implicit val toBytesCLType: ToBytes[CLType] = new ToBytes[CLType] {
    override def toBytes(t: CLType): Array[Byte] = toBytesTailRec(Right(t) :: Nil, IndexedSeq.empty)
  }

  val deserializer: FromBytes.Deserializer[CLType] =
    FromBytes.byte.flatMap {
      case tag if tag == CL_TYPE_TAG_BOOL   => FromBytes.pure(Bool)
      case tag if tag == CL_TYPE_TAG_I32    => FromBytes.pure(I32)
      case tag if tag == CL_TYPE_TAG_I64    => FromBytes.pure(I64)
      case tag if tag == CL_TYPE_TAG_U8     => FromBytes.pure(U8)
      case tag if tag == CL_TYPE_TAG_U32    => FromBytes.pure(U32)
      case tag if tag == CL_TYPE_TAG_U64    => FromBytes.pure(U64)
      case tag if tag == CL_TYPE_TAG_U128   => FromBytes.pure(U128)
      case tag if tag == CL_TYPE_TAG_U256   => FromBytes.pure(U256)
      case tag if tag == CL_TYPE_TAG_U512   => FromBytes.pure(U512)
      case tag if tag == CL_TYPE_TAG_UNIT   => FromBytes.pure(Unit)
      case tag if tag == CL_TYPE_TAG_STRING => FromBytes.pure(String)
      case tag if tag == CL_TYPE_TAG_KEY    => FromBytes.pure(Key)
      case tag if tag == CL_TYPE_TAG_UREF   => FromBytes.pure(URef)
      case tag if tag == CL_TYPE_TAG_OPTION => deserializer.map(inner => Option(inner))
      case tag if tag == CL_TYPE_TAG_LIST   => deserializer.map(inner => List(inner))

      case tag if tag == CL_TYPE_TAG_FIXED_LIST =>
        for {
          inner  <- deserializer
          length <- FromBytes.int
        } yield FixedList(inner, length)

      case tag if tag == CL_TYPE_TAG_RESULT =>
        for {
          ok  <- deserializer
          err <- deserializer
        } yield Result(ok, err)

      case tag if tag == CL_TYPE_TAG_MAP =>
        for {
          key   <- deserializer
          value <- deserializer
        } yield Map(key, value)

      case tag if tag == CL_TYPE_TAG_TUPLE1 => deserializer.map(inner => Tuple1(inner))

      case tag if tag == CL_TYPE_TAG_TUPLE2 =>
        for {
          t1 <- deserializer
          t2 <- deserializer
        } yield Tuple2(t1, t2)

      case tag if tag == CL_TYPE_TAG_TUPLE3 =>
        for {
          t1 <- deserializer
          t2 <- deserializer
          t3 <- deserializer
        } yield Tuple3(t1, t2, t3)

      case tag if tag == CL_TYPE_TAG_ANY => FromBytes.pure(Any)

      case other => FromBytes.raise(FromBytes.Error.InvalidVariantTag(other, "CLType"))
    }

  val CL_TYPE_TAG_BOOL: Byte       = 0
  val CL_TYPE_TAG_I32: Byte        = 1
  val CL_TYPE_TAG_I64: Byte        = 2
  val CL_TYPE_TAG_U8: Byte         = 3
  val CL_TYPE_TAG_U32: Byte        = 4
  val CL_TYPE_TAG_U64: Byte        = 5
  val CL_TYPE_TAG_U128: Byte       = 6
  val CL_TYPE_TAG_U256: Byte       = 7
  val CL_TYPE_TAG_U512: Byte       = 8
  val CL_TYPE_TAG_UNIT: Byte       = 9
  val CL_TYPE_TAG_STRING: Byte     = 10
  val CL_TYPE_TAG_KEY: Byte        = 11
  val CL_TYPE_TAG_UREF: Byte       = 12
  val CL_TYPE_TAG_OPTION: Byte     = 13
  val CL_TYPE_TAG_LIST: Byte       = 14
  val CL_TYPE_TAG_FIXED_LIST: Byte = 15
  val CL_TYPE_TAG_RESULT: Byte     = 16
  val CL_TYPE_TAG_MAP: Byte        = 17
  val CL_TYPE_TAG_TUPLE1: Byte     = 18
  val CL_TYPE_TAG_TUPLE2: Byte     = 19
  val CL_TYPE_TAG_TUPLE3: Byte     = 20
  val CL_TYPE_TAG_ANY: Byte        = 21
}
