package dotty.tools
package io

import scala.language.unsafeNulls

import dotty.tools.io.AbstractFile

/** Stub AbstractFileClassLoader for Scala.js - ClassLoader is not available */
class AbstractFileClassLoader(val root: AbstractFile, parent: ClassLoader) extends ClassLoader(parent):
  override def findClass(name: String): Class[?] =
    throw new ClassNotFoundException(s"AbstractFileClassLoader is not supported on Scala.js: $name")

  override def loadClass(name: String): Class[?] =
    try findClass(name)
    catch case _: ClassNotFoundException => super.loadClass(name)
end AbstractFileClassLoader
