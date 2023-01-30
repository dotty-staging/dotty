import jsonlib.*

@main def Test: Unit =
  val n: Null = json"null"
  val t: Boolean = json"true"
  val f: Boolean = json"false"
  val o: JsonObject = json"{}"
  val a: JsonArray = json"[]"
  val s: String = json"""""""" // empty string
  val s2: String = json"\"\"" // empty string

  println(json" null ")
  println(json"[true]")
  println(json"[true, true]")
  println(json"""{ "name": true }""".name)
  println(json"""{ "name": true, "name2": false }""")

  println(json"${n}")
  println(json"[${t}]")
  println(json"[${f}, ${o}]")
  println(json"""{ "a": ${a}, "b": ${s}}""")


  val user = json"""{
    "firstName": "John",
    "lastName": "Doe"
  }"""
  val bool = json"true"
  val account = json"""{
    "user": $user,
    "active": $bool
  }"""
  account.active: Boolean
  account.user.firstName: String


  (account: Json) match
    case json"""{ "user": $x, "a": true }""" => println("case 1: " + x)
    case json"""{ "user": $x, "active": $y }""" => println("case 2: " + (x, y))

  // json"n u l l"
  // json"""n u l l"""
  // json"a"
  // json"a${n}"
  // json"[${n}a ${n}"
  // json"[${n},a ${n}"
  // json"[${n} ${n}"
  // json"[${n} ${n},"
  // json"[true, "
  // json"[true, ,"
