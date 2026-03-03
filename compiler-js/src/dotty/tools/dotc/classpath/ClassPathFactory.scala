/*
 * Copyright (c) 2014 Contributor. All rights reserved.
 */
package dotty.tools.dotc.classpath

import dotty.tools.io.{AbstractFile, VirtualDirectory}
import FileUtils.*
import dotty.tools.io.ClassPath
import dotty.tools.dotc.core.Contexts.*
import java.nio.file.Files

class ClassPathFactory {
  def newClassPath(file: AbstractFile)(using Context): ClassPath = ClassPathFactory.newClassPath(file)

  def sourcesInPath(path: String)(using Context): List[ClassPath] =
    for
      file <- expandPath(path, expandStar = false)
      dir <- Option(AbstractFile.getDirectory(file))
    yield createSourcePath(dir)

  def expandPath(path: String, expandStar: Boolean = true): List[String] = dotty.tools.io.ClassPath.expandPath(path, expandStar)

  def expandDir(extdir: String): List[String] = dotty.tools.io.ClassPath.expandDir(extdir)

  def contentsOfDirsInPath(path: String)(using Context): List[ClassPath] =
    for {
      dir <- expandPath(path, expandStar = false)
      name <- expandDir(dir)
      entry <- Option(AbstractFile.getDirectory(name))
    }
    yield newClassPath(entry)

  def classesInExpandedPath(path: String)(using Context): IndexedSeq[ClassPath] =
    classesInPathImpl(path, expand = true).toIndexedSeq

  def classesInPath(path: String)(using Context): List[ClassPath] = classesInPathImpl(path, expand = false)

  def classesInManifest(useManifestClassPath: Boolean)(using Context): List[ClassPath] =
    if useManifestClassPath
    then dotty.tools.io.ClassPath.manifests.map(url => newClassPath(AbstractFile.getResources(url)))
    else Nil

  protected def classesInPathImpl(path: String, expand: Boolean)(using Context): List[ClassPath] =
    val files = for {
      file <- expandPath(path, expand)
      dir <- {
        def asImage = if (file.endsWith(".jimage")) Some(AbstractFile.getFile(file)) else None
        Option(AbstractFile.getDirectory(file)).orElse(asImage)
      }
    }
    yield dir

    val expanded =
      if false then /* scala.util.Properties not available on Scala.js */
        for
          file <- files
          a <- ClassPath.expandManifestPath(file.absolutePath)
          path = java.nio.file.Paths.get(a.toURI.toString)
          if Files.exists(path)
        yield
          newClassPath(AbstractFile.getFile(path))
      else
        Seq.empty

    files.map(newClassPath) ++ expanded

  end classesInPathImpl

  private def createSourcePath(file: AbstractFile)(using Context): ClassPath =
    if (file.isJarOrZip)
      ZipAndJarSourcePathFactory.create(file)
    else if (file.isDirectory)
      new DirectorySourcePath(file.file.nn)
    else
      sys.error(s"Unsupported sourcepath element: $file")
}

object ClassPathFactory {
  def newClassPath(file: AbstractFile)(using Context): ClassPath = file match {
    case vd: VirtualDirectory => VirtualDirectoryClassPath(vd)
    case _ =>
      if (file.isJarOrZip)
        ZipAndJarClassPathFactory.create(file)
      else if (file.isDirectory)
        new DirectoryClassPath(file.file.nn)
      else
        sys.error(s"Unsupported classpath element: $file")
  }
}
