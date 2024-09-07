trait Foo:
    def f(): Int

object O1 extends Foo:
    var a1: Int = bar(this).f()

    def bar(param: Foo): Foo =
        var local = param
        bar(O2)
        local
    override def f(): Int = 0

object O2 extends Foo:
    var a2 = 0
    override def f(): Int = a2
