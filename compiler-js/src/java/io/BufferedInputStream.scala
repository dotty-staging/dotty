package java.io

class BufferedInputStream(in: InputStream) extends InputStream {
  def this(in: InputStream, size: Int) = this(in)

  override def read(): Int = in.read()
  override def read(b: Array[Byte], off: Int, len: Int): Int = in.read(b, off, len)
  override def available(): Int = in.available()
  override def close(): Unit = in.close()
}
