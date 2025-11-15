import annotation.constructorOnly

class A(using @constructorOnly x: A): // ok
  val y = x
  def f() = y

class B(using @constructorOnly x: A): // error
  def f() = summon[A]

class C(using @constructorOnly x: A): // error
  def f() =
    val a = summon[A]
    println(a)

class D(using @constructorOnly x: A): // error
  lazy val a = summon[A]

class E(using @constructorOnly x: A): // error
  class Inner:
    println(summon[A])

