inline trait A: // At the moment this works with an ordinary trait but throws a TypeError with inline traits 
	sealed class InnerA:
		val x = new InnerB

	sealed class InnerB:
		val x = 10

class B extends A:
	val y = 10
