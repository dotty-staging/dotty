package java.nio.file

import java.io.{InputStream, OutputStream, BufferedReader, BufferedWriter, IOException}
import java.nio.charset.Charset
import java.nio.file.attribute.{BasicFileAttributes, FileAttribute, FileTime}
import java.util.stream

/** Stub implementation of java.nio.file.Files for Scala.js.
 *  Most operations throw UnsupportedOperationException.
 */
object Files {
  def exists(path: Path, options: LinkOption*): Boolean = false
  def notExists(path: Path, options: LinkOption*): Boolean = true
  def isDirectory(path: Path, options: LinkOption*): Boolean = false
  def isRegularFile(path: Path, options: LinkOption*): Boolean = false
  def isSymbolicLink(path: Path): Boolean = false
  def isReadable(path: Path): Boolean = false
  def isWritable(path: Path): Boolean = false
  def isExecutable(path: Path): Boolean = false
  def size(path: Path): Long = throw new IOException("Files.size not supported on Scala.js")
  def getLastModifiedTime(path: Path, options: LinkOption*): FileTime =
    throw new IOException("Files.getLastModifiedTime not supported on Scala.js")
  def readSymbolicLink(path: Path): Path =
    throw new IOException("Files.readSymbolicLink not supported on Scala.js")

  def newInputStream(path: Path, options: OpenOption*): InputStream =
    throw new IOException("Files.newInputStream not supported on Scala.js")
  def newOutputStream(path: Path, options: OpenOption*): OutputStream =
    throw new IOException("Files.newOutputStream not supported on Scala.js")
  def newBufferedReader(path: Path, cs: Charset): BufferedReader =
    throw new IOException("Files.newBufferedReader not supported on Scala.js")
  def newBufferedWriter(path: Path, cs: Charset, options: OpenOption*): BufferedWriter =
    throw new IOException("Files.newBufferedWriter not supported on Scala.js")
  def newBufferedWriter(path: Path, options: OpenOption*): BufferedWriter =
    throw new IOException("Files.newBufferedWriter not supported on Scala.js")

  def createDirectories(dir: Path, attrs: FileAttribute[?]*): Path = dir
  def createDirectory(dir: Path, attrs: FileAttribute[?]*): Path = dir
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

  def readAllBytes(path: Path): Array[Byte] =
    throw new IOException("Files.readAllBytes not supported on Scala.js")
  def readAllLines(path: Path): java.util.List[String] =
    throw new IOException("Files.readAllLines not supported on Scala.js")
  def readAllLines(path: Path, cs: Charset): java.util.List[String] =
    throw new IOException("Files.readAllLines not supported on Scala.js")
  def write(path: Path, bytes: Array[Byte], options: OpenOption*): Path =
    throw new IOException("Files.write not supported on Scala.js")

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
}
