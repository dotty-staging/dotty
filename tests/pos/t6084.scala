package object foo { type X[T, U] = (T => U) }

package foo {
  // Note that Foo must be final because of #3989.
  final class Foo[T, U](val d: T => U) extends (T => U) {
    def f1(r: X[T, U])           = r match { case x: Foo[?,?] => x.d }  // inferred ok
    def f2(r: X[T, U]): (T => U) = r match { case x: Foo[?,?] => x.d }  // dealiased ok
    def f3(r: X[T, U]): X[T, U]  = r match { case x: Foo[?,?] => x.d }  // alias not ok

    def apply(x: T): U = d(x)

    // x.d : foo.this.package.type.X[?scala.reflect.internal.Types$NoPrefix$?.T, ?scala.reflect.internal.Types$NoPrefix$?.U] ~>scala.this.Function1[?scala.reflect.internal.Types$NoPrefix$?.T, ?scala.reflect.internal.Types$NoPrefix$?.U]
    //  at scala.Predef$.assert(Predef.scala:170)
    //  at scala.tools.nsc.Global.assert(Global.scala:235)
    //  at scala.tools.nsc.ast.TreeGen.mkCast(TreeGen.scala:252)
    //  at scala.tools.nsc.typechecker.Typers$Typer.typedCase(Typers.scala:2263)
  }
}
