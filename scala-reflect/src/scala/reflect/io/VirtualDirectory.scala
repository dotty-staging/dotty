/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc. dba Akka
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala
package reflect
package io

import scala.language.`2.13`

import scala.collection.mutable

/**
 * An in-memory directory.
 *
 * ''Note:  This library is considered experimental and should not be used unless you know what you are doing.''
 */
class VirtualDirectory(val name: String, maybeContainer: Option[VirtualDirectory])
extends AbstractFile {
  def path: String =
    maybeContainer match {
      case None => name
      case Some(parent) => parent.path+'/'+ name
    }

  def absolute: VirtualDirectory = this

  def container: VirtualDirectory = maybeContainer.get
  def isDirectory = true
  override def isVirtual = true
  val lastModified: Long = System.currentTimeMillis

  override def file: Null = null
  override def input: Nothing = throw new IllegalStateException("directories cannot be read")
  override def output: Nothing = throw new IllegalStateException("directories cannot be written")

  /** Does this abstract file denote an existing file? */
  def create(): Unit = { unsupported() }

  /** Delete the underlying file or directory (recursively). */
  def delete(): Unit = { unsupported() }

  /** Returns an abstract file with the given name. It does not
   *  check that it exists.
   */
  def lookupNameUnchecked(name: String, directory: Boolean): AbstractFile = unsupported()

  private[this] val files = mutable.Map.empty[String, AbstractFile]

  // the toList is so that the directory may continue to be
  // modified while its elements are iterated
  def iterator = files.values.toList.iterator

  override def lookupName(name: String, directory: Boolean): AbstractFile =
    // Scala 3 port: .orNull would link against a new overload absent from the 2.13 stdlib
    (files get name filter (_.isDirectory == directory)).getOrElse(null)

  override def fileNamed(name: String): AbstractFile =
    Option(lookupName(name, directory = false)) getOrElse {
      val newFile = new VirtualFile(name, path+'/'+name)
      files(name) = newFile
      newFile
    }

  override def subdirectoryNamed(name: String): AbstractFile =
    Option(lookupName(name, directory = true)) getOrElse {
      val dir = new VirtualDirectory(name, Some(this))
      files(name) = dir
      dir
    }

  def clear(): Unit = {
    files.clear()
  }
}
