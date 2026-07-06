import scala.language.experimental.recordLiterals
import scala.language.experimental.collectionLiterals

object Test:
  case class Developer(id: String, name: String, url: String = "")
  case class License(name: String)
  case class PomSettings(
    description: String,
    organization: String,
    url: String = "",
    licenses: Seq[License] = Nil,
    developers: Seq[Developer] = Nil)

  // a named tuple literal at a case class type is a constructor call
  val dev: Developer = (id = "jamie", name = "Jamie Thompson")

  // default arguments apply; nested records and collection literals compose
  val pom: PomSettings = (
    description = "Core module",
    organization = "com.example",
    licenses = [License("MIT")],
    developers = [(id = "jamie", name = "Jamie Thompson", url = "https://github.com/bishabosha")]
  )

  // as arguments
  def register(dev: Developer): String = dev.id
  val r = register((id = "a", name = "b"))

  // named tuples are unaffected when the expected type is a named tuple
  val nt: (id: String, name: String) = (id = "jamie", name = "Jamie Thompson")
  val inferred = (id = "jamie", name = "Jamie Thompson")
  val stillNamedTuple: (id: String, name: String) = inferred

  // generic case classes
  case class Box[T](value: T, label: String = "box")
  val box: Box[Int] = (value = 42)
