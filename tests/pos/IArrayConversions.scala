import scala.collection.immutable.ArraySeq

object Holder {
  opaque type Id <: Int = Int
  def apply(a: Int): Id = a
}

val arr: IArray[Holder.Id] = IArray.tabulate(10)(Holder.apply)
val x: Set[Holder.Id] = arr.toSet // ok

val refConv: ArraySeq.ofRef[String] = IArray("a", "b").toSeq
val booleanConv: ArraySeq.ofBoolean = IArray(true, false).toSeq
val byteConv: ArraySeq.ofByte = IArray(1.toByte, 2.toByte).toSeq
val charConv: ArraySeq.ofChar = IArray('a', 'b').toSeq
val doubleConv: ArraySeq.ofDouble = IArray(1.0, 2.0).toSeq
val floatConv: ArraySeq.ofFloat = IArray(1.0f, 2.0f).toSeq
val intConv: ArraySeq.ofInt = IArray(1, 2, 3).toSeq
val longConv: ArraySeq.ofLong = IArray(1L, 2L).toSeq
val shortConv: ArraySeq.ofShort = IArray(1.toShort, 2.toShort).toSeq
val unitConv: ArraySeq.ofUnit = IArray((), ()).toSeq
