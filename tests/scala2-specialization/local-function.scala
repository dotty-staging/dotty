object Obj:
  def method[T](x: Array[T]): T =
    def local[@specialized(Int) U](y: Array[U]): U = y(0)

    if x.isInstanceOf[Array[Int]] then local(x.asInstanceOf[Array[Int]])
    else local(x)
