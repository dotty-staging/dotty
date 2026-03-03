package java.io

import scala.scalajs.js
import scala.scalajs.js.typedarray.*

class FileOutputStream(file: File, append: Boolean) extends OutputStream {
  def this(file: File) = this(file, false)
  def this(name: String) = this(new File(name), false)
  def this(name: String, append: Boolean) = this(new File(name), append)

  private val fd: js.Dynamic = {
    val flags = if (append) "a" else "w"
    File.nodeFs.openSync(file.getPath, flags)
  }

  override def write(b: Int): Unit = {
    val buf = new Int8Array(1)
    buf(0) = b.toByte
    File.nodeFs.writeSync(fd, buf.asInstanceOf[js.Any])
  }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    val buf = new Int8Array(len)
    var i = 0
    while (i < len) {
      buf(i) = b(off + i)
      i += 1
    }
    File.nodeFs.writeSync(fd, buf.asInstanceOf[js.Any])
  }

  override def close(): Unit = {
    try File.nodeFs.closeSync(fd)
    catch case _: Throwable => ()
  }
}
