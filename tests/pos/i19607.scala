trait Foo
trait Bar[T]

type MatchType[T] = T match
  case Bar[?] => Nothing
  case _ => T

object Test:
  def foo(b: Bar[? >: Foo]): MatchType[b.type] = ???

  def bar(b: Bar[? >: Foo]): Nothing = foo(b)
end Test
