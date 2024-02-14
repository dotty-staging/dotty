//> using options -language:experimental.erasedDefinitions

class Ev extends Phantom

object Test {

  fun // error because evImplicit is not provably realizable

  def fun(implicit a: Ev): Int = 42

  implicit def evImplicit: Ev = new Ev

}
