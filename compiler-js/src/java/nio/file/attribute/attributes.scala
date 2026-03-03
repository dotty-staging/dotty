package java.nio.file.attribute

import java.util.concurrent.TimeUnit

trait FileAttribute[T] {
  def name(): String
  def value(): T
}

trait BasicFileAttributes {
  def lastModifiedTime: FileTime
  def lastAccessTime: FileTime
  def creationTime: FileTime
  def isRegularFile: Boolean
  def isDirectory: Boolean
  def isSymbolicLink: Boolean
  def isOther: Boolean
  def size: Long
  def fileKey: Object
}

class FileTime private (val millis: Long) extends Comparable[FileTime] {
  def toMillis: Long = millis
  def to(unit: TimeUnit): Long = unit.convert(millis, TimeUnit.MILLISECONDS)
  def compareTo(other: FileTime): Int = java.lang.Long.compare(millis, other.millis)
  override def equals(obj: Any): Boolean = obj match {
    case other: FileTime => millis == other.millis
    case _ => false
  }
  override def hashCode(): Int = millis.hashCode
  override def toString(): String = s"FileTime[${millis}ms]"
}

object FileTime {
  def fromMillis(value: Long): FileTime = new FileTime(value)
  def from(value: Long, unit: TimeUnit): FileTime = new FileTime(unit.toMillis(value))
}
