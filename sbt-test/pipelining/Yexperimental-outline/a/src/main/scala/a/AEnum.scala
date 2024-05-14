package a

enum AEnum extends java.io.Serializable {
  case A1, A2, A3
  case A4 extends AEnum with AEnum.Mixin
}

object AEnum {
  trait Mixin
}
