//> using options -Yunboxed-options -Ycheck:all

import scala.runtime.UnboxedOptions

class Base:
  def find(x: Int): Option[String] =
    if x > 0 then Some("pos" + x) else None
  def describe(o: Option[String]): String = o match
    case Some(s) => "some:" + s
    case None => "none"
  def roundtrip(o: Option[String]): Option[String] = o
  def early(o: Option[String]): Option[String] =
    if o.isEmpty then return None // exercises explicit-return retargeting
    Some(o.get + "!")

class Sub extends Base:
  override def find(x: Int): Option[String] =
    if x == 0 then Some("zero") else super.find(x) // super call into a moved body
  override def describe(o: Option[String]): String = "sub:" + super.describe(o)

trait Greeter:
  def greet(name: Option[String]): String = "hello " + name.getOrElse("world")
  def opt: Option[Int] // abstract, Option result with primitive payload

class GreeterImpl extends Greeter:
  override def greet(name: Option[String]): String = super.greet(name) + "!" // super into trait default
  def opt: Option[Int] = Some(42)

case class Person(nickname: Option[String])

class ValHolder:
  val stored: Option[Int] = Some(7)
  var mutable: Option[Int] = None
  lazy val lz: Option[String] = Some("lazy")

class Overloaded:
  // These two used to collide on an all-Object descriptor; with specialized
  // slots they get distinct descriptors (Integer)Integer and (Object)Integer.
  def f(x: Option[Int]): Option[Int] = x
  def f(x: AnyRef): Option[Int] = Some(0)
  // These two still collide on (Object)Integer: variants suppressed for both.
  def g(x: Option[Any]): Option[Int] = Some(1)
  def g(x: AnyRef): Option[Int] = Some(2)

trait Repo[T]:
  def get(id: Int): Option[T] // unbounded T: generic Object slot

class StrRepo extends Repo[String]:
  def get(id: Int): Option[String] = // precise String slot + inherited Object-descriptor bridge
    if id > 0 then Some("s" + id) else None

object Tricky:
  def give(x: Int): Option[Option[Int]] = // nested: slot is Option, representation flattens
    if x == 0 then None else if x == 1 then Some(None) else Some(Some(x))
  def nested(o: Option[Option[Int]]): Option[Option[Int]] = o.map(identity)
  def nul: Option[String] = Some(null) // unrepresentable in a String slot: throws under the flag
  def id[T](o: Option[T]): Option[T] = o

object Test:
  def assertEq[T](a: T, b: T): Unit = assert(a == b, s"$a != $b")

  def assertThrows[E <: Throwable](cls: Class[E])(op: => Any): Unit =
    try { op; assert(false, s"expected ${cls.getName} but nothing was thrown") }
    catch
      case e: java.lang.reflect.InvocationTargetException =>
        assert(cls.isInstance(e.getCause), s"expected ${cls.getName}, got ${e.getCause}")
      case e: Throwable =>
        assert(cls.isInstance(e), s"expected ${cls.getName}, got $e")

  def main(args: Array[String]): Unit =
    val b = new Base
    assertEq(b.find(1), Some("pos1"))
    assertEq(b.find(-1), None)
    assertEq(b.describe(Some("x")), "some:x")
    assertEq(b.describe(None), "none")
    assertEq(b.roundtrip(Some("a")), Some("a"))
    assertEq(b.early(None), None)
    assertEq(b.early(Some("hi")), Some("hi!"))

    val s = new Sub
    assertEq(s.find(0), Some("zero"))
    assertEq(s.find(2), Some("pos2"))
    assertEq(s.find(-2), None)
    assertEq(s.describe(None), "sub:none")
    assertEq((s: Base).find(3), Some("pos3"))
    assertEq((s: Base).describe(Some("y")), "sub:some:y")

    val g = new GreeterImpl
    assertEq(g.greet(Some("scala")), "hello scala!")
    assertEq(g.greet(None), "hello world!")
    assertEq((g: Greeter).opt, Some(42))

    assertEq(Person(Some("Jay")).nickname, Some("Jay"))
    assertEq(Person(None).nickname, None)
    assertEq(Person(Some("Jay")).copy(nickname = None).nickname, None)

    val vh = new ValHolder
    assertEq(vh.stored, Some(7))
    assertEq(vh.mutable, None)
    vh.mutable = Some(3)
    assertEq(vh.mutable, Some(3))
    assertEq(vh.lz, Some("lazy"))

    val ov = new Overloaded
    assertEq(ov.f(Some(1): Option[Int]), Some(1))
    assertEq(ov.f("s": AnyRef), Some(0))

    assertEq(Tricky.give(0), None)
    assertEq(Tricky.give(1), Some(None))
    assertEq(Tricky.give(5), Some(Some(5)))
    assertEq(Tricky.nested(Some(Some(1))), Some(Some(1)))
    assertEq(Tricky.nested(None), None)
    assertEq(Tricky.id(Some(1)), Some(1))
    assertEq(Tricky.id(None), None)
    assertEq(Tricky.id(Some(null)), Some(null)) // Some(null) survives generic slots (Wrapped)

    val repo = new StrRepo
    assertEq(repo.get(1), Some("s1"))
    assertEq((repo: Repo[String]).get(-1), None)

    // behavior changes under the flag: values unrepresentable in a precise slot throw
    assertThrows(classOf[IllegalArgumentException])(Tricky.nul)
    assertThrows(classOf[NullPointerException])(b.roundtrip(null))

    // --- direct checks of the generated ABI via reflection ---

    // precise slots: the descriptor uses the Option argument's erasure; None is null
    val findU = classOf[Base].getMethod("find$unboxed", classOf[Int])
    assertEq(findU.getReturnType, classOf[String])
    assertEq(findU.invoke(b, Int.box(1)), "pos1") // Some("pos1") is the value itself
    assertEq(findU.invoke(b, Int.box(-1)), null)  // None is null

    val descU = classOf[Base].getMethod("describe$unboxed", classOf[String])
    assertEq(descU.invoke(b, "x"), "some:x")
    assertEq(descU.invoke(b, null.asInstanceOf[AnyRef]), "none")
    // virtual dispatch on the unboxed entry point
    assertEq(descU.invoke(s, "z"), "sub:some:z")

    // primitive payloads use the box class as slot
    val optU = classOf[GreeterImpl].getMethod("opt$unboxed")
    assertEq(optU.getReturnType, classOf[Integer])
    assertEq(optU.invoke(g), Int.box(42))

    // accessors get a reverse bridge with a precise slot
    val nickU = classOf[Person].getMethod("nickname$unboxed")
    assertEq(nickU.getReturnType, classOf[String])
    assertEq(nickU.invoke(Person(Some("Jay"))), "Jay")
    assertEq(nickU.invoke(Person(None)), null)

    // nested options flatten: the slot of Option[Option[Int]] is Option
    val giveU = Tricky.getClass.getMethod("give$unboxed", classOf[Int])
    assertEq(giveU.getReturnType.getName, "scala.Option")
    assertEq(giveU.invoke(Tricky, Int.box(0)), null)                          // None
    assert(giveU.invoke(Tricky, Int.box(1)).asInstanceOf[AnyRef] eq None)     // Some(None)
    assertEq(giveU.invoke(Tricky, Int.box(5)), Some(5))                       // Some(Some(5))

    // unbounded type parameters keep the total generic encoding on Object slots
    val idU = Tricky.getClass.getMethod("id$unboxed", classOf[Object])
    assert(idU.invoke(Tricky, None).asInstanceOf[AnyRef] eq None) // None object is its own sentinel
    assertEq(idU.invoke(Tricky, "v"), "v")

    // overloads that used to clash now differ by slot type; true clashes are suppressed
    assertEq(classOf[Overloaded].getMethods.count(_.getName == "f$unboxed"), 2)
    assert(!classOf[Overloaded].getMethods.exists(_.getName == "g$unboxed"))

    // override refinement: the subclass carries both its own precise descriptor
    // and the inherited generic descriptor as a bridge
    val getUs = classOf[StrRepo].getDeclaredMethods.filter(_.getName == "get$unboxed")
    assertEq(getUs.map(_.getReturnType.getName).sorted.toList, List("java.lang.Object", "java.lang.String"))
    val repoGetU = Class.forName("Repo").getMethod("get$unboxed", classOf[Int])
    assertEq(repoGetU.invoke(repo, Int.box(1)), "s1")
    assert(repoGetU.invoke(repo, Int.box(-1)).asInstanceOf[AnyRef] eq None) // generic encoding
    val strGetU = getUs.find(_.getReturnType == classOf[String]).get
    assertEq(strGetU.invoke(repo, Int.box(2)), "s2")
    assertEq(strGetU.invoke(repo, Int.box(-1)), null) // precise encoding

    // boxed bridges still exist with the original signatures
    assertEq(classOf[Base].getMethod("find", classOf[Int]).invoke(b, Int.box(1)), Some("pos1"))

    println("ok")
