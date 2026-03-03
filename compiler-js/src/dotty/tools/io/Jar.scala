package dotty.tools
package io

import scala.language.unsafeNulls

import java.io.{InputStream, OutputStream, DataOutputStream}
import java.util.jar.*
import scala.collection.mutable
import Attributes.Name

class Jar(file: File) {
  def this(jfile: JFile) = this(File(jfile.toPath))
  def this(path: String) = this(File(path))

  protected def errorFn(msg: String): Unit = Console println msg

  import Jar.*

  lazy val jarFile: JarFile = throw new UnsupportedOperationException("JarFile not supported on Scala.js")
  lazy val manifest: Option[Manifest] = None

  def mainClass: Option[String] = None
  def classPathString: Option[String] = None
  def classPathElements: List[String] = Nil

  def withJarInput[T](f: JarInputStream => T): T =
    throw new UnsupportedOperationException("JarInputStream not supported on Scala.js")

  def jarWriter(mainAttrs: (Attributes.Name, String)*): JarWriter =
    throw new UnsupportedOperationException("JarWriter not supported on Scala.js")

  def toList: List[JarEntry] = Nil

  def getEntryStream(entry: JarEntry): java.io.InputStream =
    throw new UnsupportedOperationException("getEntryStream not supported on Scala.js")

  override def toString: String = "" + file
}

class JarWriter(val file: File, val manifest: Manifest) {
  def newOutputStream(path: String): DataOutputStream =
    throw new UnsupportedOperationException("JarWriter not supported on Scala.js")
  def writeAllFrom(dir: Directory): Unit =
    throw new UnsupportedOperationException("JarWriter not supported on Scala.js")
  def addStream(entry: JarEntry, in: InputStream): Unit =
    throw new UnsupportedOperationException("JarWriter not supported on Scala.js")
  def addFile(file: File, prefix: String): Unit =
    throw new UnsupportedOperationException("JarWriter not supported on Scala.js")
  def addEntry(entry: Path, prefix: String): Unit =
    throw new UnsupportedOperationException("JarWriter not supported on Scala.js")
  def addDirectory(entry: Directory, prefix: String): Unit =
    throw new UnsupportedOperationException("JarWriter not supported on Scala.js")
  def close(): Unit = ()
}

object Jar {
  type AttributeMap = java.util.Map[Attributes.Name, String]

  object WManifest {
    def apply(mainAttrs: (Attributes.Name, String)*): WManifest = {
      val m = WManifest(new JManifest)
      for ((k, v) <- mainAttrs)
        m(k) = v
      m
    }
  }
  implicit class WManifest(val manifest: JManifest) {
    for ((k, v) <- initialMainAttrs)
      this(k) = v

    def underlying: JManifest = manifest
    def attrs: mutable.Map[Name, String] = {
      import scala.jdk.CollectionConverters.*
      manifest.getMainAttributes().asInstanceOf[AttributeMap].asScala withDefaultValue null
    }
    def initialMainAttrs: Map[Attributes.Name, String] = {
      Map(
        Name.MANIFEST_VERSION -> "1.0",
        new Attributes.Name("Scala-Compiler-Version") -> scala.util.Properties.versionNumberString
      )
    }

    def apply(name: Attributes.Name): String = attrs(name)
    def apply(name: String): String = apply(new Attributes.Name(name))
    def update(key: Attributes.Name, value: String): Option[String] = attrs.put(key, value)
    def update(key: String, value: String): Option[String] = attrs.put(new Attributes.Name(key), value)

    def mainClass: String = apply(Name.MAIN_CLASS)
    def mainClass_=(value: String): Option[String] = update(Name.MAIN_CLASS, value)
  }

  private val ZipMagicNumber = List[Byte](80, 75, 3, 4)
  private def magicNumberIsZip(f: Path) = f.isFile && (f.toFile.bytes().take(4).toList == ZipMagicNumber)

  def isJarOrZip(f: Path): Boolean = isJarOrZip(f, true)
  def isJarOrZip(f: Path, examineFile: Boolean): Boolean =
    f.ext.isJarOrZip || (examineFile && magicNumberIsZip(f))

  def create(file: File, sourceDir: Directory, mainClass: String): Unit =
    throw new UnsupportedOperationException("Jar.create not supported on Scala.js")
}
