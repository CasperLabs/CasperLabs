package io.casperlabs.models.cltype

import cats.free.Free
import cats.implicits._
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric._
import io.casperlabs.models.bytesrepr.{BytesView, Constants, FromBytes, ToBytes}
import io.casperlabs.models.cltype
import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.{Failure, Success, Try}

sealed trait CLValueInstance { self =>
  val clType: CLType

  final def toValue: Either[CLValueInstance.Error, CLValue] =
    CLValueInstance.valueBytes(self :: Nil, Vector.empty).map(CLValue(clType, _))
}

object CLValueInstance {
  def from(v: CLValue): Either[FromBytes.Error, CLValueInstance] =
    FromBytes.deserialize(deserializer(v.clType), v.value.toArray)

  def deserializer(t: CLType): FromBytes.Deserializer[CLValueInstance] = t match {
    case CLType.Bool => FromBytes.bool.map(b => Bool(b))

    case CLType.I32 => FromBytes.int.map(i => I32(i))

    case CLType.I64 => FromBytes.long.map(i => I64(i))

    case CLType.U8 => FromBytes.byte.map(b => U8(b))

    case CLType.U32 => FromBytes.int.map(i => U32(i))

    case CLType.U64 => FromBytes.long.map(i => U64(i))

    case CLType.U128 =>
      bigIntNN.map(nn => U128(nn))

    case CLType.U256 =>
      bigIntNN.map(nn => U256(nn))

    case CLType.U512 =>
      bigIntNN.map(nn => U512(nn))

    case CLType.Unit => FromBytes.unit.map(_ => Unit)

    case CLType.String => FromBytes.string.map(s => String(s))

    case CLType.Key => cltype.Key.deserializer.map(k => Key(k))

    case CLType.URef => cltype.URef.deserializer.map(u => URef(u))

    case CLType.Option(inner) =>
      FromBytes
        .option(deserializer(inner))
        .flatMap(instance => lift(Option(instance, inner)))

    case CLType.List(inner) =>
      FromBytes
        .seq(deserializer(inner))
        .flatMap(instance => lift(List(instance, inner)))

    case CLType.FixedList(inner, n) =>
      FromBytes
        .fixedSeq(deserializer(inner), n)
        .flatMap(instances => lift(FixedList(instances, inner, n)))

    case CLType.Result(ok, err) =>
      FromBytes
        .either(deserializer(err), deserializer(ok))
        .flatMap(instance => lift(Result(instance, ok, err)))

    case CLType.Map(key, value) =>
      FromBytes
        .map(deserializer(key), deserializer(value))
        .flatMap(instance => lift(Map(instance, key, value)))

    case CLType.Tuple1(t1) =>
      FromBytes
        .tuple1(deserializer(t1))
        .map { case scala.Tuple1(_1) => Tuple1(_1) }

    case CLType.Tuple2(t1, t2) =>
      FromBytes
        .tuple2(
          deserializer(t1),
          deserializer(t2)
        )
        .map {
          case (_1, _2) => Tuple2(_1, _2)
        }

    case CLType.Tuple3(t1, t2, t3) =>
      FromBytes
        .tuple3(
          deserializer(t1),
          deserializer(t2),
          deserializer(t3)
        )
        .map {
          case (_1, _2, _3) => Tuple3(_1, _2, _3)
        }

    case CLType.Any =>
      FromBytes.raise(FromBytes.Error.FormatException("Cannot instantiate CLType.Any"))
  }

  case class Bool(value: Boolean) extends CLValueInstance {
    override val clType: CLType = CLType.Bool
  }

  case class I32(value: Int) extends CLValueInstance {
    override val clType: CLType = CLType.I32
  }

  case class I64(value: Long) extends CLValueInstance {
    override val clType: CLType = CLType.I64
  }

  case class U8(value: Byte) extends CLValueInstance {
    override val clType: CLType = CLType.U8
  }

  case class U32(value: Int) extends CLValueInstance {
    override val clType: CLType = CLType.U32
  }

  case class U64(value: Long) extends CLValueInstance {
    override val clType: CLType = CLType.U64
  }

  case class U128(value: BigInt Refined NonNegative) extends CLValueInstance {
    override val clType: CLType = CLType.U128
  }

  case class U256(value: BigInt Refined NonNegative) extends CLValueInstance {
    override val clType: CLType = CLType.U256
  }

  case class U512(value: BigInt Refined NonNegative) extends CLValueInstance {
    override val clType: CLType = CLType.U512
  }

  case object Unit extends CLValueInstance { override val clType: CLType = CLType.Unit }

  case class String(value: java.lang.String) extends CLValueInstance {
    override val clType: CLType = CLType.String
  }

  case class Key(value: cltype.Key) extends CLValueInstance {
    override val clType: CLType = CLType.Key
  }

  case class URef(value: cltype.URef) extends CLValueInstance {
    override val clType: CLType = CLType.URef
  }

  case class Option private (value: scala.Option[CLValueInstance], inner: CLType)
      extends CLValueInstance {
    override val clType: CLType = CLType.Option(inner)
  }
  object Option {
    def apply(
        value: scala.Option[CLValueInstance],
        innerType: CLType
    ): Either[Error.TypeMismatch, Option] =
      value match {
        case None => Right(new Option(value, innerType))
        case Some(innerValue) =>
          Error.TypeMismatch.detect(innerValue, innerType).map(_ => new Option(value, innerType))
      }
  }

  case class List private (value: Seq[CLValueInstance], inner: CLType) extends CLValueInstance {
    override val clType: CLType = CLType.List(inner)
  }
  object List {
    def apply(value: Seq[CLValueInstance], innerType: CLType): Either[Error.TypeMismatch, List] =
      value.find(_.clType != innerType) match {
        case None => Right(new List(value, innerType))
        case Some(badValue) =>
          Left(Error.TypeMismatch(valueType = badValue.clType, targetType = innerType))
      }
  }

  case class FixedList private (value: Seq[CLValueInstance], inner: CLType, length: Int)
      extends CLValueInstance {
    override val clType: CLType = CLType.FixedList(inner, length)
  }
  object FixedList {
    def apply(
        value: Seq[CLValueInstance],
        innerType: CLType,
        length: Int
    ): Either[Error, FixedList] =
      value.find(_.clType != innerType) match {
        case None =>
          val n = value.size
          if (n != length) Left(Error.InvalidLength(valueLength = n, typeLength = length))
          else Right(new FixedList(value, innerType, length))

        case Some(badValue) =>
          Left(Error.TypeMismatch(valueType = badValue.clType, targetType = innerType))
      }
  }

  case class Result private (
      value: Either[CLValueInstance, CLValueInstance],
      ok: CLType,
      err: CLType
  ) extends CLValueInstance {
    override val clType: CLType = CLType.Result(ok, err)
  }
  object Result {
    def apply(
        value: Either[CLValueInstance, CLValueInstance],
        ok: CLType,
        err: CLType
    ): Either[Error.TypeMismatch, Result] = value match {
      case Left(left)   => Error.TypeMismatch.detect(left, err).map(_ => new Result(value, ok, err))
      case Right(right) => Error.TypeMismatch.detect(right, ok).map(_ => new Result(value, ok, err))
    }
  }

  case class Map(
      value: immutable.Map[CLValueInstance, CLValueInstance],
      keyType: CLType,
      valueType: CLType
  ) extends CLValueInstance {
    override val clType: CLType = CLType.Map(keyType, valueType)
  }
  object Map {
    def apply(
        value: immutable.Map[CLValueInstance, CLValueInstance],
        k: CLType,
        v: CLType
    ): Either[Error.TypeMismatch, Map] =
      value.keys.find(_.clType != k) match {
        case Some(badKey) => Left(Error.TypeMismatch(valueType = badKey.clType, targetType = k))
        case None =>
          value.values.find(_.clType != v) match {
            case Some(badValue) =>
              Left(Error.TypeMismatch(valueType = badValue.clType, targetType = v))
            case None => Right(new Map(value, k, v))
          }
      }
  }

  case class Tuple1(_1: CLValueInstance) extends CLValueInstance {
    override val clType: CLType = CLType.Tuple1(_1.clType)
  }

  case class Tuple2(_1: CLValueInstance, _2: CLValueInstance) extends CLValueInstance {
    override val clType: CLType = CLType.Tuple2(_1.clType, _2.clType)
  }

  case class Tuple3(_1: CLValueInstance, _2: CLValueInstance, _3: CLValueInstance)
      extends CLValueInstance {
    override val clType: CLType = CLType.Tuple3(_1.clType, _2.clType, _3.clType)
  }

  sealed trait Error
  object Error {
    case class TypeMismatch(valueType: CLType, targetType: CLType) extends Error
    object TypeMismatch {
      def detect(instance: CLValueInstance, target: CLType): Either[TypeMismatch, Unit] =
        if (instance.clType == target) Right(())
        else Left(TypeMismatch(valueType = instance.clType, targetType = target))
    }

    case class InvalidLength(valueLength: Int, typeLength: Int) extends Error

    // This error is raised when serializing (as in the `toValue` method) a
    // `Map` with keys that cannot be sorted because no ordering is defined. Keys
    // must be sorted to ensure deterministic serialization.
    case class UnorderedElements(message: java.lang.String) extends Error
  }

  private implicit class OrderingOps[T](x: T)(implicit ev: Ordering[T]) {
    def <(y: T): Boolean = ev.lt(x, y)
  }
  private implicit class SeqOrderingOps[T](x: Seq[T])(implicit ev: Ordering[T]) {
    def <(y: Seq[T]): Boolean = implicitly[Ordering[Iterable[T]]].lt(x.toIterable, y.toIterable)
  }

  implicit val order: Ordering[CLValueInstance] = Ordering.fromLessThan {
    case (Bool(x), Bool(y))     => x < y
    case (I32(x), I32(y))       => x < y
    case (I64(x), I64(y))       => x < y
    case (U8(x), U8(y))         => x < y
    case (U32(x), U32(y))       => x < y
    case (U64(x), U64(y))       => x < y
    case (U128(x), U128(y))     => x.value < y.value
    case (U256(x), U256(y))     => x.value < y.value
    case (U512(x), U512(y))     => x.value < y.value
    case (Unit, Unit)           => false // equal, not less than
    case (String(x), String(y)) => x < y
    case (URef(x), URef(y))     => cltype.URef.lt(x, y)

    case (Key(cltype.Key.Hash(x)), Key(cltype.Key.Hash(y)))       => ByteArray32.lt(x, y)
    case (Key(cltype.Key.Account(x)), Key(cltype.Key.Account(y))) => ByteArray32.lt(x, y)
    case (Key(cltype.Key.URef(x)), Key(cltype.Key.URef(y)))       => cltype.URef.lt(x, y)

    case (Option(_, tx), Option(_, ty)) if tx != ty =>
      throw new IllegalArgumentException(s"Incompatible element types: Option($tx) != Option($ty)")

    case (Option(x, _), Option(y, _)) =>
      x < y

    case (List(_, tx), List(_, ty)) if tx != ty =>
      throw new IllegalArgumentException(s"Incompatible element types: List($tx) != List($ty)")

    case (List(xs, _), List(ys, _)) =>
      xs < ys

    case (FixedList(_, tx, lx), FixedList(_, ty, ly)) if tx != ty || lx != ly =>
      throw new IllegalArgumentException(
        s"Incompatible element types: FixedList($tx, $lx) != FixedList($ty, $ly)"
      )

    case (FixedList(xs, _, _), FixedList(ys, _, _)) =>
      xs < ys

    case (Result(_, txO, txE), Result(_, tyO, tyE)) if txO != tyO || txE != tyE =>
      throw new IllegalArgumentException(
        s"Incompatible element types: Result($txO, $txE) != Result($tyO, $tyE)"
      )

    case (Result(x, _, _), Result(y, _, _)) =>
      (x, y) match {
        case (Left(_), Right(_))  => true
        case (Right(_), Left(_))  => false
        case (Left(x), Left(y))   => x < y
        case (Right(x), Right(y)) => x < y
      }

    case (Map(_, txK, txV), Map(_, tyK, tyV)) if txK != tyK || txV != tyV =>
      throw new IllegalArgumentException(
        s"Incompatible element types: Map($txK, $txV) != Map($tyK, $tyV)"
      )

    case (Map(mx, _, _), Map(my, _, _)) =>
      mx.toList.sorted < my.toList.sorted

    case (Tuple1(x1), Tuple1(y1)) =>
      x1 < y1

    case (Tuple2(x1, x2), Tuple2(y1, y2)) =>
      (x1, x2) < ((y1, y2))

    case (Tuple3(x1, x2, x3), Tuple3(y1, y2, y3)) =>
      (x1, x2, x3) < ((y1, y2, y3))

    case _ =>
      throw new IllegalArgumentException("Incompatible element types.")
  }

  private def lift[T <: CLValueInstance, E <: Error](
      maybeInstance: Either[E, T]
  ): FromBytes.Deserializer[
    CLValueInstance
  ] =
    maybeInstance match {
      case Left(err) =>
        FromBytes.raise(FromBytes.Error.FormatException(s"CLValueInstance.Error: $err"))

      case Right(result) => FromBytes.pure(result)
    }

  private val bigIntNN: FromBytes.Deserializer[BigInt Refined NonNegative] =
    FromBytes.bigInt.flatMap { i =>
      refineV[NonNegative](i) match {
        case Left(_) => FromBytes.raise(FromBytes.Error.FormatException("Negative BigInt"))

        case Right(nn) => FromBytes.pure(nn)
      }
    }

  @tailrec
  private def valueBytes(
      instances: immutable.List[CLValueInstance],
      acc: Vector[Byte]
  ): Either[Error, Vector[Byte]] =
    instances match {
      case Nil => Right(acc)

      case Bool(b) :: tail   => valueBytes(tail, acc ++ ToBytes.toBytes(b))
      case I32(i) :: tail    => valueBytes(tail, acc ++ ToBytes.toBytes(i))
      case I64(i) :: tail    => valueBytes(tail, acc ++ ToBytes.toBytes(i))
      case U8(i) :: tail     => valueBytes(tail, acc ++ ToBytes.toBytes(i))
      case U32(i) :: tail    => valueBytes(tail, acc ++ ToBytes.toBytes(i))
      case U64(i) :: tail    => valueBytes(tail, acc ++ ToBytes.toBytes(i))
      case U128(i) :: tail   => valueBytes(tail, acc ++ ToBytes.toBytes(i.value))
      case U256(i) :: tail   => valueBytes(tail, acc ++ ToBytes.toBytes(i.value))
      case U512(i) :: tail   => valueBytes(tail, acc ++ ToBytes.toBytes(i.value))
      case Unit :: tail      => valueBytes(tail, acc ++ ToBytes.toBytes(()))
      case String(s) :: tail => valueBytes(tail, acc ++ ToBytes.toBytes(s))
      case Key(k) :: tail    => valueBytes(tail, acc ++ ToBytes.toBytes(k))
      case URef(u) :: tail   => valueBytes(tail, acc ++ ToBytes.toBytes(u))

      case Option(None, _) :: tail    => valueBytes(tail, acc :+ Constants.Option.NONE_TAG)
      case Option(Some(x), _) :: tail => valueBytes(x :: tail, acc :+ Constants.Option.SOME_TAG)

      case CLValueInstance.List(values, _) :: tail =>
        valueBytes(values.toList ++ tail, acc ++ ToBytes.toBytes(values.size))

      case CLValueInstance.FixedList(values, _, _) :: tail => valueBytes(values.toList ++ tail, acc)

      case CLValueInstance.Result(Left(x), _, _) :: tail =>
        valueBytes(x :: tail, acc :+ Constants.Either.LEFT_TAG)

      case CLValueInstance.Result(Right(x), _, _) :: tail =>
        valueBytes(x :: tail, acc :+ Constants.Either.RIGHT_TAG)

      case CLValueInstance.Map(values, _, _) :: tail =>
        Try(values.toList.sortBy(_._1)) match {
          case Failure(ex) => Left(Error.UnorderedElements(ex.getMessage))

          case Success(sortedPairs) =>
            val sortedElems = sortedPairs.flatMap { case (k, v) => immutable.List(k, v) }
            valueBytes(sortedElems ++ tail, acc ++ ToBytes.toBytes(values.size))
        }

      case CLValueInstance.Tuple1(x) :: tail       => valueBytes(x :: tail, acc)
      case CLValueInstance.Tuple2(x, y) :: tail    => valueBytes(x :: y :: tail, acc)
      case CLValueInstance.Tuple3(x, y, z) :: tail => valueBytes(x :: y :: z :: tail, acc)
    }
}
