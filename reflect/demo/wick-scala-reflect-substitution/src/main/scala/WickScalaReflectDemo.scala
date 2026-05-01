import com.netflix.wick.{*, given}
import org.apache.spark.sql.SparkSession

case class Department(id: Int, name: String)
case class Employee(id: Int, name: String, dept_id: Int, title_id: Int)

@main def wickScalaReflectDemo(): Unit =
  val javaUniverseClass = Class.forName("scala.reflect.api.JavaUniverse")
  println(s"scala-reflect loaded from: ${javaUniverseClass.getProtectionDomain.getCodeSource.getLocation}")

  val spark = SparkSession.builder()
    .appName("wick-scala-reflect-substitution-demo")
    .master("local[1]")
    .config("spark.ui.enabled", "false")
    .config("spark.sql.shuffle.partitions", "1")
    .getOrCreate()

  try
    spark.sparkContext.setLogLevel("ERROR")

    // Force the Spark path from scala/scala3#25896 before running the Wick demo.
    val scalaReflection = Class.forName("org.apache.spark.sql.catalyst.ScalaReflection$", true, getClass.getClassLoader)
    scalaReflection.getField("MODULE$").get(null)

    val employees = spark.createDataSeq(
      Seq(
        Employee(id = 1, name = "Alice", dept_id = 1, title_id = 2),
        Employee(id = 2, name = "Bob", dept_id = 2, title_id = 1),
        Employee(id = 3, name = "Charlie", dept_id = 1, title_id = 2)
      )
    )
    val departments = spark.createDataSeq(
      Seq(
        Department(id = 1, name = "Engineering"),
        Department(id = 2, name = "Marketing")
      )
    )

    employees.show()
    departments.show()
  finally spark.close()
