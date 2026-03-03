package dotty.tools.io

import scala.language.unsafeNulls

class JarArchive private (val jarPath: Path, root: Directory) extends PlainDirectory(root) {
  override val name: String = jarPath.name
  override val path: String = jarPath.path
  override def lastModified: Long = 0L
  def close(): Unit = ()
  override def exists: Boolean = false
  def allFileNames(): Iterator[String] = Iterator.empty
  override def toString: String = jarPath.toString
}

object JarArchive {
  def create(path: Path): JarArchive =
    throw new UnsupportedOperationException("JarArchive.create not supported on Scala.js")

  def open(path: Path, create: Boolean = false): JarArchive =
    throw new UnsupportedOperationException("JarArchive.open not supported on Scala.js")
}
