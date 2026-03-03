package java.io

import scala.scalajs.js
import scala.util.Try

class File(pathname: String) {
  def this(parent: File, child: String) = this(
    if (parent.getPath.isEmpty) child
    else parent.getPath + File.separator + child
  )

  def this(parent: String, child: String) = this(
    if (parent.isEmpty) child
    else parent + File.separator + child
  )

  def getPath: String = pathname
  def getAbsolutePath: String =
    if pathname.startsWith("/") then pathname
    else Try(File.nodeFs.realpathSync(pathname).asInstanceOf[String]).getOrElse("/" + pathname)

  def getName: String = {
    val sep = pathname.lastIndexOf(File.separatorChar)
    if (sep < 0) pathname else pathname.substring(sep + 1)
  }

  def exists: Boolean =
    Try(File.nodeFs.existsSync(pathname).asInstanceOf[Boolean]).getOrElse(false)

  def isDirectory: Boolean =
    Try {
      val stats = File.nodeFs.statSync(pathname)
      stats.isDirectory().asInstanceOf[Boolean]
    }.getOrElse(false)

  def isFile: Boolean =
    Try {
      val stats = File.nodeFs.statSync(pathname)
      stats.isFile().asInstanceOf[Boolean]
    }.getOrElse(false)

  def mkdirs: Boolean =
    Try {
      File.nodeFs.mkdirSync(pathname, js.Dynamic.literal(recursive = true))
      true
    }.getOrElse(false)

  def length: Long =
    Try {
      val stats = File.nodeFs.statSync(pathname)
      stats.size.asInstanceOf[Double].toLong
    }.getOrElse(0L)

  def listFiles(): Array[File] =
    Try {
      val entries = File.nodeFs.readdirSync(pathname).asInstanceOf[js.Array[String]]
      entries.toArray.map(name => new File(this, name))
    }.getOrElse(Array.empty)

  def listFiles(filter: FileFilter): Array[File] =
    listFiles().filter(f => filter.accept(f))

  def toPath: java.nio.file.Path = java.nio.file.Paths.get(pathname)
  def toURI: java.net.URI = {
    val absPath = getAbsolutePath
    new java.net.URI("file", null, if absPath.startsWith("/") then absPath else "/" + absPath, null)
  }

  def delete: Boolean =
    Try { File.nodeFs.unlinkSync(pathname); true }.getOrElse(false)

  override def toString: String = pathname
  override def hashCode(): Int = pathname.hashCode
  override def equals(obj: Any): Boolean = obj match {
    case other: File => pathname == other.getPath
    case _ => false
  }
}

object File {
  val separator: String = "/"
  val separatorChar: Char = '/'
  val pathSeparator: String = ":"
  val pathSeparatorChar: Char = ':'

  private[java] lazy val nodeFs: js.Dynamic =
    try js.Dynamic.global.require("fs")
    catch case _: Throwable => js.Dynamic.literal()
}
