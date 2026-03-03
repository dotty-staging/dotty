package java.nio.file

import java.nio.file.attribute.BasicFileAttributes

class SimpleFileVisitor[T] extends FileVisitor[T] {
  def preVisitDirectory(dir: T, attrs: BasicFileAttributes): FileVisitResult =
    FileVisitResult.CONTINUE
  def visitFile(file: T, attrs: BasicFileAttributes): FileVisitResult =
    FileVisitResult.CONTINUE
  def visitFileFailed(file: T, exc: java.io.IOException): FileVisitResult =
    FileVisitResult.CONTINUE
  def postVisitDirectory(dir: T, exc: java.io.IOException): FileVisitResult =
    FileVisitResult.CONTINUE
}

trait FileVisitor[T] {
  def preVisitDirectory(dir: T, attrs: BasicFileAttributes): FileVisitResult
  def visitFile(file: T, attrs: BasicFileAttributes): FileVisitResult
  def visitFileFailed(file: T, exc: java.io.IOException): FileVisitResult
  def postVisitDirectory(dir: T, exc: java.io.IOException): FileVisitResult
}
