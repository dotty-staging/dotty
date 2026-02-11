package scala.util
import boundary.{break, Label}
import collection.mutable

object Validator {
  extension [T] (x: T)
    def validate[E](op: Validator[T, E] => Unit): Result[T, List[E]] =
      boundary: lbl ?=>
        val v = Validator[T, E]()
        op(v)
        if v.errors.isEmpty then Ok(x) else Err(v.errors.toList)
}

class Validator[T, E] private()(using lbl: Label[Result[T, List[E]]]) {
  private val errors = mutable.ListBuffer[E]()

  def ensure(p: Boolean, e: => E, abort: Boolean = false): Unit =
    if !p then
      errors += e
      if abort then break(Err(errors.toList))
}

