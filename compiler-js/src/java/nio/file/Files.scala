package java.nio.file

import java.io.{InputStream, OutputStream, BufferedReader, BufferedWriter, IOException}
import java.nio.charset.Charset
import java.nio.file.attribute.{BasicFileAttributes, FileAttribute, FileTime}
import java.util.stream

/** Stub implementation of java.nio.file.Files for Scala.js.
 *  Most operations throw UnsupportedOperationException.
 */
object Files {
  private def toFile(path: Path): java.io.File = new java.io.File(path.toString)

  def exists(path: Path, options: LinkOption*): Boolean = toFile(path).exists
  def notExists(path: Path, options: LinkOption*): Boolean = !toFile(path).exists
  def isDirectory(path: Path, options: LinkOption*): Boolean = toFile(path).isDirectory
  def isRegularFile(path: Path, options: LinkOption*): Boolean = toFile(path).isFile
  def isSymbolicLink(path: Path): Boolean = false
  def isReadable(path: Path): Boolean = false
  def isWritable(path: Path): Boolean = false
  def isExecutable(path: Path): Boolean = false
  def size(path: Path): Long = toFile(path).length
  def getLastModifiedTime(path: Path, options: LinkOption*): FileTime =
    throw new IOException("Files.getLastModifiedTime not supported on Scala.js")
  def readSymbolicLink(path: Path): Path =
    throw new IOException("Files.readSymbolicLink not supported on Scala.js")

  def newInputStream(path: Path, options: OpenOption*): InputStream = {
    val bytes = readAllBytes(path)
    new java.io.ByteArrayInputStream(bytes)
  }
  def newOutputStream(path: Path, options: OpenOption*): OutputStream =
    new java.io.FileOutputStream(path.toString)
  def newBufferedReader(path: Path, cs: Charset): BufferedReader =
    throw new IOException("Files.newBufferedReader not supported on Scala.js")
  def newBufferedWriter(path: Path, cs: Charset, options: OpenOption*): BufferedWriter =
    throw new IOException("Files.newBufferedWriter not supported on Scala.js")
  def newBufferedWriter(path: Path, options: OpenOption*): BufferedWriter =
    throw new IOException("Files.newBufferedWriter not supported on Scala.js")

  def createDirectories(dir: Path, attrs: FileAttribute[?]*): Path = {
    toFile(dir).mkdirs; dir
  }
  def createDirectory(dir: Path, attrs: FileAttribute[?]*): Path = {
    toFile(dir).mkdirs; dir
  }
  def createFile(path: Path, attrs: FileAttribute[?]*): Path = path
  def createTempFile(prefix: String, suffix: String, attrs: FileAttribute[?]*): Path =
    throw new IOException("Files.createTempFile not supported on Scala.js")
  def createTempDirectory(prefix: String, attrs: FileAttribute[?]*): Path =
    throw new IOException("Files.createTempDirectory not supported on Scala.js")
  def createTempDirectory(dir: Path, prefix: String, attrs: FileAttribute[?]*): Path =
    throw new IOException("Files.createTempDirectory not supported on Scala.js")

  def delete(path: Path): Unit = throw new IOException("Files.delete not supported on Scala.js")
  def deleteIfExists(path: Path): Boolean = false

  def copy(source: Path, target: Path, options: CopyOption*): Path =
    throw new IOException("Files.copy not supported on Scala.js")
  def move(source: Path, target: Path, options: CopyOption*): Path =
    throw new IOException("Files.move not supported on Scala.js")

  def readAllBytes(path: Path): Array[Byte] = {
    import scala.scalajs.js
    import scala.scalajs.js.typedarray.*
    try {
      val buf = java.io.File.nodeFs.readFileSync(path.toString).asInstanceOf[js.typedarray.Uint8Array]
      val arr = new Array[Byte](buf.length)
      var i = 0
      while (i < arr.length) {
        arr(i) = buf(i).toByte
        i += 1
      }
      arr
    } catch {
      case ex: NoSuchFileException => throw ex
      case _: Throwable => throw new NoSuchFileException(path.toString)
    }
  }
  def readAllLines(path: Path): java.util.List[String] =
    throw new IOException("Files.readAllLines not supported on Scala.js")
  def readAllLines(path: Path, cs: Charset): java.util.List[String] =
    throw new IOException("Files.readAllLines not supported on Scala.js")
  def write(path: Path, bytes: Array[Byte], options: OpenOption*): Path = {
    import scala.scalajs.js
    import scala.scalajs.js.typedarray.*
    val buf = new Int8Array(bytes.length)
    var i = 0
    while (i < bytes.length) {
      buf(i) = bytes(i)
      i += 1
    }
    java.io.File.nodeFs.writeFileSync(path.toString, buf.asInstanceOf[js.Any])
    path
  }

  def readAttributes[A <: BasicFileAttributes](path: Path, tpe: Class[A], options: LinkOption*): A =
    throw new IOException("Files.readAttributes not supported on Scala.js")

  def walkFileTree(start: Path, visitor: FileVisitor[_ >: Path]): Path =
    throw new UnsupportedOperationException("Files.walkFileTree not supported on Scala.js")

  def walkFileTree(start: Path, options: java.util.Set[FileVisitOption], maxDepth: Int, visitor: FileVisitor[_ >: Path]): Path =
    throw new UnsupportedOperationException("Files.walkFileTree not supported on Scala.js")

  def walk(start: Path, options: FileVisitOption*): stream.Stream[Path] =
    throw new UnsupportedOperationException("Files.walk not supported on Scala.js")

  def list(dir: Path): stream.Stream[Path] =
    throw new UnsupportedOperationException("Files.list not supported on Scala.js")

  def newDirectoryStream(dir: Path): DirectoryStream[Path] =
    throw new UnsupportedOperationException("Files.newDirectoryStream not supported on Scala.js")

  // Array-based overloads for JVM-compiled callers
  def exists(path: Path, options: Array[LinkOption]): Boolean = toFile(path).exists
  def isDirectory(path: Path, options: Array[LinkOption]): Boolean = toFile(path).isDirectory
  def isRegularFile(path: Path, options: Array[LinkOption]): Boolean = toFile(path).isFile
  def newInputStream(path: Path, options: Array[OpenOption]): InputStream = {
    val bytes = readAllBytes(path)
    new java.io.ByteArrayInputStream(bytes)
  }
  def newOutputStream(path: Path, options: Array[OpenOption]): OutputStream =
    new java.io.FileOutputStream(path.toString)
  def createDirectories(dir: Path, attrs: Array[FileAttribute[?]]): Path = dir
  def createDirectory(dir: Path, attrs: Array[FileAttribute[?]]): Path = dir
  def createFile(path: Path, attrs: Array[FileAttribute[?]]): Path = path
  def getLastModifiedTime(path: Path, options: Array[LinkOption]): attribute.FileTime =
    throw new IOException("Files.getLastModifiedTime not supported on Scala.js")
}
