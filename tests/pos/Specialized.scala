import reflect.ClassTag

class Specialized[T](val ct: ClassTag[T]):
  import Specialized.Tag
  lazy val tag: Tag = ct match
    case ClassTag.Byte => Tag.Byte
    case ClassTag.Short => Tag.Short
    case ClassTag.Char => Tag.Char
    case ClassTag.Int => Tag.Int
    case ClassTag.Long => Tag.Long
    case ClassTag.Float => Tag.Float
    case ClassTag.Double => Tag.Double
    case ClassTag.Boolean => Tag.Boolean
    case ClassTag.Unit => Tag.Unit
    case ClassTag.AnyRef => Tag.AnyRef
    case _ => Tag.Other

type SpecializedAt[S] = [T] =>> Specialized[T]

object Specialized:
  enum Tag:
    case Byte, Short, Char, Int, Long, Float, Double, Boolean, Unit, AnyRef, Other

  given fromClassTag[T](using ct: ClassTag[T]): Specialized[T] = Specialized(ct)
end Specialized

given toClassTag[T](using sp: Specialized[T]): ClassTag[T] = sp.ct

def foo[T: ClassTag]: Unit =
  bar[T]
  baz[T]

def bar[T: Specialized]: Unit =
  foo[T]
  baz[T]

def baz[T: SpecializedAt[Int | Boolean]] =
  foo[T]
  bar[T]

