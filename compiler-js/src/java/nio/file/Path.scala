package java.nio.file

import java.io.File
import java.net.URI

trait Path extends Comparable[Path] with java.lang.Iterable[Path] {
  def getFileSystem: FileSystem
  def isAbsolute: Boolean
  def getRoot: Path | Null
  def getFileName: Path | Null
  def getParent: Path | Null
  def getNameCount: Int
  def getName(index: Int): Path
  def subpath(beginIndex: Int, endIndex: Int): Path
  def startsWith(other: Path): Boolean
  def startsWith(other: String): Boolean = startsWith(Path.of(other))
  def endsWith(other: Path): Boolean
  def endsWith(other: String): Boolean = endsWith(Path.of(other))
  def normalize: Path
  def resolve(other: Path): Path
  def resolve(other: String): Path = resolve(Path.of(other))
  def resolveSibling(other: Path): Path = {
    val parent = getParent
    if (parent == null) other
    else parent.resolve(other)
  }
  def resolveSibling(other: String): Path = resolveSibling(Path.of(other))
  def relativize(other: Path): Path
  def toUri: URI
  def toAbsolutePath: Path
  def toRealPath(options: LinkOption*): Path = toAbsolutePath
  def toFile: File
  def iterator: java.util.Iterator[Path]
  override def toString: String
}

object Path {
  def of(first: String, more: String*): Path = Paths.get(first, more*)
  def of(uri: URI): Path = throw new UnsupportedOperationException("Path.of(URI) is not supported on Scala.js")
}
