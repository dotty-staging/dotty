object O1:
    val a = O2.b()

object O2:
    val b = f()
    def f(): () => Int =
        var local = 0
        () => local // warn