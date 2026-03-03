package dotty.tools.io

import scala.language.unsafeNulls

import java.net.URL
import java.io.{IOException, InputStream, OutputStream, FilterInputStream}
import java.nio.file.Files
import java.util.zip.{ZipEntry, ZipFile}
import java.util.jar.{Manifest, JarFile}
import scala.collection.mutable

object ZipArchive {
  private[io] val closeZipFile: Boolean = false

  def fromFile(file: File): FileZipArchive = fromPath(file.jpath)
  def fromPath(jpath: JPath): FileZipArchive = new FileZipArchive(jpath, release = None)

  def fromManifestURL(url: URL): AbstractFile =
    throw new UnsupportedOperationException("ZipArchive.fromManifestURL not supported on Scala.js")

  private def dirName(path: String) = splitPath(path, front = true)
  private def baseName(path: String) = splitPath(path, front = false)
  private def splitPath(path0: String, front: Boolean): String = {
    val isDir = path0.charAt(path0.length - 1) == '/'
    val path = if (isDir) path0.substring(0, path0.length - 1) else path0
    val idx = path.lastIndexOf('/')

    if (idx < 0)
      if (front) "/"
      else path
    else
      if (front) path.substring(0, idx + 1)
      else path.substring(idx + 1)
  }
}

import ZipArchive.*

abstract class ZipArchive(override val jpath: JPath, release: Option[String]) extends AbstractFile with Equals {
  self =>

  override def underlyingSource: Option[ZipArchive] = Some(this)
  def isDirectory: Boolean = true
  def lookupName(name: String, directory: Boolean): AbstractFile = unsupported()
  def lookupNameUnchecked(name: String, directory: Boolean): AbstractFile = unsupported()
  def output: OutputStream = unsupported()
  def container: AbstractFile = unsupported()
  def absolute: AbstractFile = unsupported()

  sealed abstract class Entry(path: String, val parent: Entry) extends VirtualFile(baseName(path), path) {
    def getArchive: ZipFile = null
    override def underlyingSource: Option[ZipArchive] = Some(self)
    override def container: Entry = parent
    override def toString: String = self.path + "(" + path + ")"
  }

  class DirEntry(path: String, parent: Entry) extends Entry(path, parent) {
    val entries: mutable.HashMap[String, Entry] = mutable.HashMap()

    override def isDirectory: Boolean = true
    override def iterator: Iterator[Entry] = entries.valuesIterator
    override def lookupName(name: String, directory: Boolean): Entry = {
      if (directory) entries.get(name + "/").orNull
      else entries.get(name).orNull
    }
  }

  private def ensureDir(dirs: mutable.Map[String, DirEntry], path: String): DirEntry =
    dirs get path match {
      case Some(v) => v
      case None =>
        val parent = ensureDir(dirs, dirName(path))
        val dir = new DirEntry(path, parent)
        parent.entries(baseName(path)) = dir
        dirs(path) = dir
        dir
    }

  protected def getDir(dirs: mutable.Map[String, DirEntry], entry: ZipEntry): DirEntry = {
    if (entry.isDirectory) ensureDir(dirs, entry.getName)
    else ensureDir(dirs, dirName(entry.getName))
  }

  def close(): Unit
}

final class FileZipArchive(jpath: JPath, release: Option[String]) extends ZipArchive(jpath, release) {
  lazy val (root, allDirs): (DirEntry, collection.Map[String, DirEntry]) = {
    val root = new DirEntry("/", null)
    val dirs = mutable.HashMap[String, DirEntry]("/" -> root)
    (root, dirs)
  }

  def iterator: Iterator[Entry] = root.iterator

  def name: String = jpath.getFileName.toString
  def path: String = jpath.toString
  def input: InputStream =
    throw new UnsupportedOperationException("FileZipArchive.input not supported on Scala.js")
  def lastModified: Long = 0

  override def sizeOption: Option[Int] = None
  override def canEqual(other: Any): Boolean = other.isInstanceOf[FileZipArchive]
  override def hashCode(): Int = jpath.hashCode
  override def equals(that: Any): Boolean = that match {
    case x: FileZipArchive => jpath.toAbsolutePath == x.jpath.toAbsolutePath
    case _ => false
  }

  override def close(): Unit = ()
}

final class ManifestResources(val url: URL) extends ZipArchive(null, None) {
  def iterator: Iterator[AbstractFile] = Iterator.empty

  def name: String = path
  def path: String = {
    val s = url.getPath
    val n = s.lastIndexOf('!')
    s.substring(0, n)
  }
  def input: InputStream = url.openStream()
  def lastModified: Long = 0

  override def canEqual(other: Any): Boolean = other.isInstanceOf[ManifestResources]
  override def hashCode(): Int = url.hashCode
  override def equals(that: Any): Boolean = that match {
    case x: ManifestResources => url == x.url
    case _ => false
  }

  def toURLs(): Seq[URL] = Seq(url)

  override def close(): Unit = ()
}
