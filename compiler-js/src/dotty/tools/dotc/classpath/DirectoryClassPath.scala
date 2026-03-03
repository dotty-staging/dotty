package dotty.tools.dotc.classpath

import java.io.{File => JFile}
import java.net.{URI, URL}

import dotty.tools.dotc.classpath.PackageNameUtils.{packageContains, separatePkgAndClassNames}
import dotty.tools.io.{AbstractFile, PlainFile, ClassPath, ClassRepresentation, EfficientClassPath}
import FileUtils.*
import PlainFile.toPlainFile

import scala.collection.immutable.ArraySeq
import scala.util.control.NonFatal

trait DirectoryLookup[FileEntryType <: ClassRepresentation] extends EfficientClassPath {
  type F

  val dir: F

  protected def emptyFiles: Array[F]
  protected def getSubDir(dirName: String): Option[F]
  protected def listChildren(dir: F, filter: Option[F => Boolean] = None): Array[F]
  protected def getName(f: F): String
  protected def toAbstractFile(f: F): AbstractFile
  protected def isPackage(f: F): Boolean

  protected def createFileEntry(file: AbstractFile): FileEntryType
  protected def isMatchingFile(f: F): Boolean

  private def getDirectory(forPackage: PackageName): Option[F] =
    if (forPackage.isRoot)
      Some(dir)
    else
      getSubDir(forPackage.dirPathTrailingSlash)

  override private[dotty] def hasPackage(pkg: PackageName): Boolean = getDirectory(pkg).isDefined

  private[dotty] def packages(inPackage: PackageName): Seq[PackageEntry] = {
    val dirForPackage = getDirectory(inPackage)
    val nestedDirs: Array[F] = dirForPackage match {
      case None => emptyFiles
      case Some(directory) => listChildren(directory, Some(isPackage))
    }
    ArraySeq.unsafeWrapArray(nestedDirs).map(f => PackageEntryImpl(inPackage.entryName(getName(f))))
  }

  protected def files(inPackage: PackageName): Seq[FileEntryType] = {
    val dirForPackage = getDirectory(inPackage)
    val files: Array[F] = dirForPackage match {
      case None => emptyFiles
      case Some(directory) => listChildren(directory, Some(isMatchingFile))
    }
    files.iterator.map(f => createFileEntry(toAbstractFile(f))).toSeq
  }

  override def list(inPackage: PackageName, onPackageEntry: PackageEntry => Unit, onClassesAndSources: ClassRepresentation => Unit): Unit = {
    val dirForPackage = getDirectory(inPackage)
    dirForPackage match {
      case None =>
      case Some(directory) =>
        for (file <- listChildren(directory)) {
          if (isPackage(file))
            onPackageEntry(PackageEntryImpl(inPackage.entryName(getName(file))))
          else if (isMatchingFile(file))
            onClassesAndSources(createFileEntry(toAbstractFile(file)))
        }
    }
  }
}

trait JFileDirectoryLookup[FileEntryType <: ClassRepresentation] extends DirectoryLookup[FileEntryType] {
  type F = JFile

  protected def emptyFiles: Array[JFile] = Array.empty
  protected def getSubDir(packageDirName: String): Option[JFile] = {
    val packageDir = new JFile(dir, packageDirName)
    if (packageDir.exists && packageDir.isDirectory) Some(packageDir)
    else None
  }
  protected def listChildren(dir: JFile, filter: Option[JFile => Boolean]): Array[JFile] = {
    val listing = filter match {
      case Some(f) => dir.listFiles(mkFileFilter(f))
      case None => dir.listFiles()
    }

    if (listing != null) {
      java.util.Arrays.sort(listing,
        new java.util.Comparator[JFile] {
          def compare(o1: JFile, o2: JFile) = o1.getName.compareTo(o2.getName)
        })
      listing
    }
    else Array()
  }
  protected def getName(f: JFile): String = f.getName
  protected def toAbstractFile(f: JFile): AbstractFile = f.toPath.toPlainFile
  protected def isPackage(f: JFile): Boolean = f.isPackage

  assert(dir.asInstanceOf[JFile | Null] != null, "Directory file in DirectoryFileLookup cannot be null")

  def asURLs: Seq[URL] = Seq(dir.toURI.toURL)
  def asClassPathStrings: Seq[String] = Seq(dir.getPath)
}

/** Stub JrtClassPath for Scala.js - JRT filesystem not available */
object JrtClassPath {
  def apply(release: Option[String]): Option[ClassPath] = None
}

case class DirectoryClassPath(dir: JFile) extends JFileDirectoryLookup[BinaryFileEntry] with NoSourcePaths {

  def findClassFile(className: String): Option[AbstractFile] = {
    val relativePath = FileUtils.dirPath(className)
    val classFile = new JFile(dir, relativePath + ".class")
    if classFile.exists then Some(classFile.toPath.toPlainFile)
    else None
  }

  protected def createFileEntry(file: AbstractFile): BinaryFileEntry = BinaryFileEntry(file)

  protected def isMatchingFile(f: JFile): Boolean =
    f.isTasty || f.isBestEffortTasty || (f.isClass && !f.hasSiblingTasty)

  private[dotty] def classes(inPackage: PackageName): Seq[BinaryFileEntry] = files(inPackage)
}

case class DirectorySourcePath(dir: JFile) extends JFileDirectoryLookup[SourceFileEntry] with NoClassPaths {
  def asSourcePathString: String = asClassPathString

  protected def createFileEntry(file: AbstractFile): SourceFileEntry = SourceFileEntry(file)
  protected def isMatchingFile(f: JFile): Boolean = endsScalaOrJava(f.getName)

  private[dotty] def sources(inPackage: PackageName): Seq[SourceFileEntry] = files(inPackage)
}
