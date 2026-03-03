package java.io

class BufferedWriter(out: Writer) extends Writer {
  def this(out: Writer, sz: Int) = this(out)

  override def write(c: Int): Unit = out.write(c)
  override def write(cbuf: Array[Char], off: Int, len: Int): Unit = out.write(cbuf, off, len)
  override def write(str: String, off: Int, len: Int): Unit = out.write(str, off, len)
  def newLine(): Unit = write("\n")
  override def flush(): Unit = out.flush()
  override def close(): Unit = { flush(); out.close() }
}
