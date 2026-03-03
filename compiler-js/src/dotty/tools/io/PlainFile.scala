/* NSC -- new Scala compiler
 * Copyright 2005-2013 LAMP/EPFL
 * @author  Martin Odersky
 */

package dotty.tools
package io

import scala.language.unsafeNulls

import java.io.{InputStream, OutputStream}
import java.nio.file.{InvalidPathException, Paths}

/** ''Note:  This library is considered experimental and should not be used unless you know what you are doing.'' */
class PlainDirectory(givenPath: Directory) extends PlainFile(givenPath) {
  override val isDirectory: Boolean = true
  override def iterator: Iterator[PlainFile] = givenPath.list.filter(_.exists).map(new PlainFile(_))
}

/** This class implements an abstract file backed by a File.
 *
 * ''Note:  This library is considered experimental and should not be used unless you know what you are doing.''
 */
class PlainFile(val givenPath: Path) extends AbstractFile {
  def name: String = givenPath.name
  def path: String = givenPath.path
  assert(path ne null)

  dotc.util.Stats.record("new PlainFile")

  def jpath: JPath = givenPath.jpath

  def lastModified: Long =
    try java.nio.file.Files.getLastModifiedTime(jpath).toMillis
    catch { case _: Exception => 0L }

  override def underlyingSource  = {
    // Simplified for Scala.js - no jar/jrt scheme support
    None
  }

  /** Returns the length of this file. */
  override def sizeOption: Option[Int] =
    if (exists) Some(java.nio.file.Files.size(jpath).toInt)
    else None

  def absolute: AbstractFile =
    if (isAbsolute) this else new PlainFile(givenPath.toAbsolute)

  def isAbsolute: Boolean = givenPath.isAbsolute

  def container: AbstractFile = new PlainFile(givenPath.parent)
  def input: InputStream     = java.nio.file.Files.newInputStream(jpath)
  def output: OutputStream   = java.nio.file.Files.newOutputStream(jpath)

  def isDirectory: Boolean = java.nio.file.Files.isDirectory(jpath)
  override def exists: Boolean = try java.nio.file.Files.exists(jpath) catch { case _: SecurityException => false }

  /** Returns all abstract subfiles of this abstract directory. */
  def iterator: Iterator[AbstractFile] = {
    try {
      import scala.jdk.CollectionConverters.*
      val stream = java.nio.file.Files.list(jpath)
      try
        val xs = stream.iterator().asScala.toList
        xs.iterator.map(x => new PlainFile(new Path(x)))
      finally
        stream.close()
    } catch {
      case _: Exception => Iterator.empty
    }
  }

  /**
   * Returns the abstract file in this abstract directory with the
   * specified name. If there is no such file, returns null. The
   * argument "directory" tells whether to look for a directory or
   * or a regular file.
   */
  def lookupName(name: String, directory: Boolean): AbstractFile = {
    val child = jpath.resolve(name)
    if ((if (directory) java.nio.file.Files.isDirectory(child) else java.nio.file.Files.isRegularFile(child)))
      new PlainFile(new Path(child))
    else null
  }

  /** Returns an abstract file with the given name. It does not
   *  check that it exists.
   */
  def lookupNameUnchecked(name: String, directory: Boolean): AbstractFile =
    new PlainFile(new Path(jpath.resolve(name)))
}

object PlainFile {
  extension (jPath: JPath)
    def toPlainFile = new PlainFile(new Path(jPath))
}
