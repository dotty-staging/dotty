import scala.language.experimental.recordLiterals
import scala.language.experimental.collectionLiterals

case class Developer(id: String, name: String, url: String = "<none>")
case class Project(name: String, tags: Seq[String] = Nil, developers: Seq[Developer] = Nil)

@main def Test =
  val dev: Developer = (id = "jamie", name = "Jamie Thompson")
  assert(dev == Developer("jamie", "Jamie Thompson"))
  assert(dev.url == "<none>") // default argument applied

  val proj: Project = (
    name = "example",
    tags = ["scala", "build"],
    developers = [(id = "a", name = "A"), (id = "b", name = "B", url = "https://b.example")]
  )
  assert(proj.developers.map(_.id) == Seq("a", "b"))
  assert(proj.developers.head.url == "<none>")
  assert(proj.developers(1).url == "https://b.example")

  // named tuples still work untargeted
  val nt = (id = "x", name = "y")
  assert(nt.id == "x")

  println("ok")
