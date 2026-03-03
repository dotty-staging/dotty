package java.util.jar

import java.io.{InputStream, OutputStream}
import java.util.zip.{ZipFile, ZipEntry, ZipInputStream, ZipOutputStream}

class Manifest() {
  private val mainAttrs = new Attributes()
  def this(is: InputStream) = {
    this()
    // stub: don't actually read
  }
  def getMainAttributes(): Attributes = mainAttrs
  def getEntries(): java.util.Map[String, Attributes] = new java.util.HashMap()
  def getAttributes(name: String): Attributes | Null = null
  def write(out: OutputStream): Unit = ()
}

class Attributes extends java.util.HashMap[Object, Object] {
  def getValue(name: String): String | Null = {
    val v = get(new Attributes.Name(name))
    if (v == null) null else v.toString
  }
  def getValue(name: Attributes.Name): String | Null = {
    val v = get(name)
    if (v == null) null else v.toString
  }
  def putValue(name: String, value: String): String | Null = {
    val old = put(new Attributes.Name(name), value)
    if (old == null) null else old.toString
  }
}

object Attributes {
  class Name(val name: String) {
    override def toString(): String = name
    override def hashCode(): Int = name.toLowerCase.hashCode
    override def equals(obj: Any): Boolean = obj match {
      case other: Name => name.equalsIgnoreCase(other.name)
      case _ => false
    }
  }
  object Name {
    val MANIFEST_VERSION = new Name("Manifest-Version")
    val MAIN_CLASS = new Name("Main-Class")
    val CLASS_PATH = new Name("Class-Path")
    val SEALED = new Name("Sealed")
    val IMPLEMENTATION_TITLE = new Name("Implementation-Title")
    val IMPLEMENTATION_VERSION = new Name("Implementation-Version")
    val IMPLEMENTATION_VENDOR = new Name("Implementation-Vendor")
    val SPECIFICATION_TITLE = new Name("Specification-Title")
    val SPECIFICATION_VERSION = new Name("Specification-Version")
    val SPECIFICATION_VENDOR = new Name("Specification-Vendor")
    val CONTENT_TYPE = new Name("Content-Type")
    val SIGNATURE_VERSION = new Name("Signature-Version")
    val EXTENSION_LIST = new Name("Extension-List")
    val EXTENSION_NAME = new Name("Extension-Name")
    val EXTENSION_INSTALLATION = new Name("Extension-Installation")
    val IMPLEMENTATION_VENDOR_ID = new Name("Implementation-Vendor-Id")
    val IMPLEMENTATION_URL = new Name("Implementation-URL")
  }
}

class JarFile(file: java.io.File, verify: Boolean, mode: Int, version: Runtime.Version) extends ZipFile(file) {
  def this(file: java.io.File, verify: Boolean, mode: Int) = this(file, verify, mode, Runtime.version())
  def this(file: java.io.File, verify: Boolean) = this(file, verify, ZipFile.OPEN_READ)
  def this(file: java.io.File) = this(file, true)
  def this(name: String) = this(new java.io.File(name))

  def getManifest(): Manifest | Null = null
  def getJarEntry(name: String): JarEntry | Null = null
}

class JarEntry(name: String) extends ZipEntry(name)

class JarInputStream(in: InputStream, verify: Boolean) extends ZipInputStream(in) {
  def this(in: InputStream) = this(in, true)
  def getManifest(): Manifest | Null = null
  def getNextJarEntry(): JarEntry | Null = null
}

class JarOutputStream(out: OutputStream, man: Manifest) extends ZipOutputStream(out) {
  def this(out: OutputStream) = this(out, new Manifest())
}
