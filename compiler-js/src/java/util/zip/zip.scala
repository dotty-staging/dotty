package java.util.zip

import java.io.{InputStream, OutputStream, FilterInputStream, FilterOutputStream}

class ZipEntry(val name: String) {
  private var _time: Long = 0
  private var _size: Long = -1
  private var _crc: Long = -1
  private var _method: Int = -1

  def getName: String = name
  def getTime: Long = _time
  def setTime(time: Long): Unit = _time = time
  def getSize: Long = _size
  def setSize(size: Long): Unit = _size = size
  def getCrc: Long = _crc
  def setCrc(crc: Long): Unit = _crc = crc
  def getMethod: Int = _method
  def setMethod(method: Int): Unit = _method = method
  def isDirectory: Boolean = name.endsWith("/")
  override def toString: String = name
}

class ZipFile(file: java.io.File, mode: Int) {
  def this(file: java.io.File) = this(file, ZipFile.OPEN_READ)
  def this(name: String) = this(new java.io.File(name))

  def entries(): java.util.Enumeration[_ <: ZipEntry] =
    java.util.Collections.emptyEnumeration()

  def getEntry(name: String): ZipEntry | Null = null
  def getInputStream(entry: ZipEntry): InputStream | Null = null
  def getName(): String = file.getPath
  def size(): Int = 0
  def close(): Unit = ()
}

object ZipFile {
  val OPEN_READ = 1
  val OPEN_DELETE = 4
}

class ZipInputStream(in: InputStream) extends FilterInputStream(in) {
  def getNextEntry(): ZipEntry | Null = null
  def closeEntry(): Unit = ()
}

class ZipOutputStream(out: OutputStream) extends FilterOutputStream(out) {
  def putNextEntry(e: ZipEntry): Unit = ()
  def closeEntry(): Unit = ()
  def setLevel(level: Int): Unit = ()
  def setMethod(method: Int): Unit = ()

  override def write(b: Array[Byte], off: Int, len: Int): Unit = out.write(b, off, len)
  override def flush(): Unit = out.flush()
  override def close(): Unit = out.close()
}

object ZipOutputStream {
  val DEFLATED = 8
  val STORED = 0
}

class Deflater(level: Int, nowrap: Boolean) {
  def this(level: Int) = this(level, false)
  def this() = this(Deflater.DEFAULT_COMPRESSION)
  def end(): Unit = ()
}

object Deflater {
  val DEFLATED = 8
  val NO_COMPRESSION = 0
  val BEST_SPEED = 1
  val BEST_COMPRESSION = 9
  val DEFAULT_COMPRESSION = -1
}

class CRC32 {
  private var _value: Long = 0
  def update(b: Array[Byte], off: Int, len: Int): Unit = ()
  def update(b: Array[Byte]): Unit = update(b, 0, b.length)
  def update(b: Int): Unit = ()
  def getValue(): Long = _value
  def reset(): Unit = _value = 0
}
