package java.nio.file

import java.net.URI
import java.util.{Map => JMap}

object FileSystems {
  private val defaultFs = new DefaultFileSystem

  def getDefault: FileSystem = defaultFs

  def getFileSystem(uri: URI): FileSystem =
    throw new FileSystemNotFoundException(s"File system not found: $uri")

  def newFileSystem(uri: URI, env: JMap[String, ?]): FileSystem =
    throw new UnsupportedOperationException("FileSystems.newFileSystem not supported on Scala.js")

  def newFileSystem(path: Path, loader: ClassLoader | Null): FileSystem =
    throw new UnsupportedOperationException("FileSystems.newFileSystem not supported on Scala.js")

  private class DefaultFileSystem extends FileSystem {
    def provider(): spi.FileSystemProvider =
      throw new UnsupportedOperationException("Default FileSystemProvider not available on Scala.js")
    def close(): Unit = throw new UnsupportedOperationException("Cannot close default file system")
    def isOpen(): Boolean = true
    def isReadOnly(): Boolean = false
    def getSeparator(): String = "/"
    def getRootDirectories: java.lang.Iterable[Path] = java.util.Collections.singletonList(Paths.get("/"))
    def getPath(first: String, more: String*): Path = Paths.get(first, more*)
  }
}
