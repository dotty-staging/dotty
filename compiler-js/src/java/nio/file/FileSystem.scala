package java.nio.file

import java.io.Closeable

abstract class FileSystem extends Closeable {
  def provider(): spi.FileSystemProvider
  def close(): Unit
  def isOpen(): Boolean
  def isReadOnly(): Boolean
  def getSeparator(): String
  def getRootDirectories: java.lang.Iterable[Path]
  def getPath(first: String, more: String*): Path
}
