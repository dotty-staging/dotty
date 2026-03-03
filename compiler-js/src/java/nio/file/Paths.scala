package java.nio.file

import java.net.URI

object Paths {
  def get(first: String, more: String*): Path = {
    val combined = if (more.isEmpty) first else (first +: more).mkString("/")
    StringPath(combined)
  }

  // Array-based overload for JVM-compiled callers
  def get(first: String, more: Array[String]): Path = {
    val combined = if (more.isEmpty) first else (first +: more.toSeq).mkString("/")
    StringPath(combined)
  }

  def get(uri: URI): Path =
    throw new UnsupportedOperationException("Paths.get(URI) is not supported on Scala.js")

  /** Minimal Path implementation backed by a string path */
  private[file] class StringPath(val pathString: String) extends Path {
    private val normalizedPath = pathString.replace('\\', '/')

    private def segments: Array[String] =
      normalizedPath.split("/").filter(_.nonEmpty)

    def getFileSystem: FileSystem = FileSystems.getDefault
    def isAbsolute: Boolean = normalizedPath.startsWith("/")
    def getRoot: Path | Null = if (isAbsolute) new StringPath("/") else null
    def getFileName: Path | Null = {
      val segs = segments
      if (segs.isEmpty) null else new StringPath(segs.last)
    }
    def getParent: Path | Null = {
      val idx = normalizedPath.lastIndexOf('/')
      if (idx <= 0 && isAbsolute) {
        if (normalizedPath == "/") null else new StringPath("/")
      } else if (idx < 0) null
      else new StringPath(normalizedPath.substring(0, idx))
    }
    def getNameCount: Int = segments.length
    def getName(index: Int): Path = new StringPath(segments(index))
    def subpath(beginIndex: Int, endIndex: Int): Path =
      new StringPath(segments.slice(beginIndex, endIndex).mkString("/"))
    def startsWith(other: Path): Boolean = normalizedPath.startsWith(other.toString)
    def endsWith(other: Path): Boolean = normalizedPath.endsWith(other.toString)
    def normalize: Path = this // simplified
    def resolve(other: Path): Path = {
      val otherStr = other.toString
      if (otherStr.startsWith("/")) other
      else if (normalizedPath.endsWith("/")) new StringPath(normalizedPath + otherStr)
      else new StringPath(normalizedPath + "/" + otherStr)
    }
    def relativize(other: Path): Path = {
      val thisSegs = segments
      val otherSegs = other.toString.replace('\\', '/').split("/").filter(_.nonEmpty)
      val common = thisSegs.zip(otherSegs).takeWhile { case (a, b) => a == b }.length
      val ups = Array.fill(thisSegs.length - common)("..")
      val rest = otherSegs.drop(common)
      new StringPath((ups ++ rest).mkString("/"))
    }
    def toUri: URI = {
      val abs = toAbsolutePath.toString
      new URI("file", null, if (abs.startsWith("/")) abs else "/" + abs, null)
    }
    def toAbsolutePath: Path =
      if (isAbsolute) this else new StringPath("/" + normalizedPath)
    override def toFile: java.io.File = new java.io.File(pathString)
    override def iterator: java.util.Iterator[Path] = {
      val segs = segments
      new java.util.Iterator[Path] {
        var idx = 0
        def hasNext: Boolean = idx < segs.length
        def next(): Path = { val p = new StringPath(segs(idx)); idx += 1; p }
      }
    }
    def register(watcher: WatchService | Null, events: Array[WatchEvent.Kind[?] | Null] | Null, modifiers: Array[? <: (WatchEvent.Modifier | Null)]): WatchKey | Null =
      throw new UnsupportedOperationException("register not supported on Scala.js")
    override def toRealPath(options: LinkOption*): Path = toAbsolutePath
    def compareTo(other: Path): Int = normalizedPath.compareTo(other.toString)
    override def toString: String = pathString
    override def hashCode(): Int = normalizedPath.hashCode
    override def equals(obj: Any): Boolean = obj match {
      case other: StringPath => normalizedPath == other.normalizedPath
      case _ => false
    }
  }
}
