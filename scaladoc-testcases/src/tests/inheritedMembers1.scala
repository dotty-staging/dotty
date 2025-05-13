package tests
package inheritedMembers1


/*<-*/@annotation.nowarn/*->*/
class A
{
    def A: String
      = ???
    val B: Int
      = ???
    object X
    trait Z
    given B with { val x = 1 }//expected: given given_B: given_B.type
    trait Placeholder//expected: object given_B extends B

    object Y extends Z

    object ZA {
      trait Z
      object Inner1 extends A.this.Z
      object Inner2 extends Z
      def a: I = ???
    }

    type I = Int
    /*<-*/extension (a: A) /*->*/def extension: String
      = ???
}

// class New extends A {
//   trait
// }