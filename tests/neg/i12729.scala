class Test(i: Int):
  val `<init>` = "init" // error: Illegal backquoted identifier: `<init>` and `<clinit>` are forbidden
  val `<clinit>` = "clinit" // error: Illegal backquoted identifier: `<init>` and `<clinit>` are forbidden
