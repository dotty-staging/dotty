package java.nio.file

import java.io.IOException

class FileSystemException(file: String, other: String | Null, reason: String | Null)
    extends IOException(reason) {
  def this(file: String) = this(file, null, null)
  def getFile(): String = file
  def getOtherFile(): String | Null = other
  def getReason(): String | Null = reason
  override def getMessage(): String = {
    val sb = new StringBuilder()
    if (file != null) sb.append(file)
    if (other != null) sb.append(" -> ").append(other)
    if (reason != null) sb.append(": ").append(reason)
    sb.toString
  }
}

class FileAlreadyExistsException(file: String, other: String | Null, reason: String | Null)
    extends FileSystemException(file, other, reason) {
  def this(file: String) = this(file, null, null)
}

class NoSuchFileException(file: String, other: String | Null, reason: String | Null)
    extends FileSystemException(file, other, reason) {
  def this(file: String) = this(file, null, null)
}

class FileSystemNotFoundException(msg: String) extends RuntimeException(msg) {
  def this() = this(null.asInstanceOf[String])
}

class FileSystemAlreadyExistsException(msg: String) extends RuntimeException(msg) {
  def this() = this(null.asInstanceOf[String])
}

class ProviderNotFoundException(msg: String) extends RuntimeException(msg) {
  def this() = this(null.asInstanceOf[String])
}

class AccessDeniedException(file: String, other: String | Null, reason: String | Null)
    extends FileSystemException(file, other, reason) {
  def this(file: String) = this(file, null, null)
}
