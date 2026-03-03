/* NSC -- new Scala compiler
 * Copyright 2005-2013 LAMP/EPFL
 * @author  Martin Odersky
 */

package dotty.tools.io

import scala.language.unsafeNulls

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream, InputStream, OutputStream }

/** This class implements an in-memory file.
 *
 *  ''Note:  This library is considered experimental and should not be used unless you know what you are doing.''
 */
class VirtualFile(val name: String, override val path: String) extends AbstractFile {

  def this(name: String) = this(name, name)

  def this(path: String, content: Array[Byte]) = {
    this(VirtualFile.nameOf(path), path)
    this.content = content
  }

  def this(path: JPath, content: Array[Byte]) = {
    this(path.getFileName.toString(), path.toString())
    this.content = content
    this.jpath_ = path
  }

  private var content = Array.emptyByteArray

  private var jpath_ : JPath = null

  def absolute: AbstractFile = this

  /** Returns path, which might be a non-existing file or null. */
  def jpath: JPath = jpath_

  override def sizeOption: Option[Int] = Some(content.length)

  /** Always returns true, even if jpath is a non-existing file. */
  override def exists: Boolean = true

  def input : InputStream = new ByteArrayInputStream(content)

  override def output: OutputStream = {
    new ByteArrayOutputStream() {
      override def close() = {
        super.close()
        content = toByteArray()
      }
    }
  }

  def container: AbstractFile = NoAbstractFile

  /** Is this abstract file a directory? */
  def isDirectory: Boolean = false

  /** @inheritdoc */
  override def isVirtual: Boolean = true

  /** Returns the time that this abstract file was last modified. */
  def lastModified: Long = 0

  /** Returns all abstract subfiles of this abstract directory. */
  def iterator: Iterator[AbstractFile] = {
    assert(isDirectory, "not a directory '" + this + "'")
    Iterator.empty
  }

  def lookupName(name: String, directory: Boolean): AbstractFile = {
    assert(isDirectory, "not a directory '" + this + "'")
    null
  }

  def lookupNameUnchecked(name: String, directory: Boolean): AbstractFile = unsupported()
}
object VirtualFile:
  private def nameOf(path: String): String =
    val i = path.lastIndexOf('/')
    if i >= 0 && i < path.length - 1 then path.substring(i + 1) else path
