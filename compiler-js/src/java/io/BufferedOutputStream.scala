package java.io

class BufferedOutputStream(out: OutputStream) extends OutputStream {
  def this(out: OutputStream, size: Int) = this(out)

  override def write(b: Int): Unit = out.write(b)
  override def write(b: Array[Byte], off: Int, len: Int): Unit = out.write(b, off, len)
  override def flush(): Unit = out.flush()
  override def close(): Unit = { flush(); out.close() }
}
