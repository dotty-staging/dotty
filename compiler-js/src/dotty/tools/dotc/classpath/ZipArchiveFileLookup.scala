package dotty.tools.dotc.classpath

import java.io.File
import java.net.URL

import dotty.tools.io.{AbstractFile, FileZipArchive}
import FileUtils.*
import dotty.tools.io.{EfficientClassPath, ClassRepresentation}

/** Stub ZipArchiveFileLookup for Scala.js */
trait ZipArchiveFileLookup[FileEntryType <: ClassRepresentation] extends EfficientClassPath {
  val zipFile: File
  def release: Option[String]

  override def asURLs: Seq[URL] = Seq(zipFile.toURI.toURL)
  override def asClassPathStrings: Seq[String] = Seq(zipFile.getPath)

  override private[dotty] def packages(inPackage: PackageName): Seq[PackageEntry] = Nil

  protected def files(inPackage: PackageName): Seq[FileEntryType] = Nil

  protected def file(inPackage: PackageName, name: String): Option[FileEntryType] = None

  override def hasPackage(pkg: PackageName) = false
  def list(inPackage: PackageName, onPackageEntry: PackageEntry => Unit, onClassesAndSources: ClassRepresentation => Unit): Unit = ()

  protected def createFileEntry(file: FileZipArchive#Entry): FileEntryType
  protected def isRequiredFileType(file: AbstractFile): Boolean
}
