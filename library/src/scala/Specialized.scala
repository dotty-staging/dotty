package scala

trait Specialized[T]
object Specialized:
    def apply[T] = new Specialized[T] {}
