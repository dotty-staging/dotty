package dotty.tools.dotc
package classpath

import scala.language.unsafeNulls

import java.io.File
import java.net.URL
import java.nio.file.attribute.{BasicFileAttributes, FileTime}

import dotty.tools.io.{AbstractFile, ClassPath, ClassRepresentation, FileZipArchive, ManifestResources}
import dotty.tools.dotc.core.Contexts.*
import FileUtils.*

/** Stub ZipAndJarFileLookupFactory for Scala.js */
sealed trait ZipAndJarFileLookupFactory {
  def create(zipFile: AbstractFile)(using Context): ClassPath =
    throw new UnsupportedOperationException("Zip/Jar classpath not supported on Scala.js")

  protected def createForZipFile(zipFile: AbstractFile, release: Option[String]): ClassPath
}

object ZipAndJarClassPathFactory extends ZipAndJarFileLookupFactory {
  override protected def createForZipFile(zipFile: AbstractFile, release: Option[String]): ClassPath =
    throw new UnsupportedOperationException("Zip classpath not supported on Scala.js")
}

object ZipAndJarSourcePathFactory extends ZipAndJarFileLookupFactory {
  override protected def createForZipFile(zipFile: AbstractFile, release: Option[String]): ClassPath =
    throw new UnsupportedOperationException("Zip source path not supported on Scala.js")
}

final class FileBasedCache[T] {
  private case class Stamp(lastModified: FileTime, fileKey: Object)
  private val cache = collection.mutable.Map.empty[java.nio.file.Path, (Stamp, T)]

  def getOrCreate(path: java.nio.file.Path, create: () => T): T =
    throw new UnsupportedOperationException("FileBasedCache not supported on Scala.js")

  def clear(): Unit = cache.clear()
}
