package dotty.tools.io

import dotty.tools.uncheckedNN
import dotty.tools.dotc.util.EnumFlags.FlagSet

enum FileExtension(val toLowerCase: String):
  case Tasty extends FileExtension("tasty")
  case Class extends FileExtension("class")
  case Jar extends FileExtension("jar")
  case Scala extends FileExtension("scala")
  case Java extends FileExtension("java")
  case Zip extends FileExtension("zip")
  case Inc extends FileExtension("inc")
  case Empty extends FileExtension("")

  /** Fallback extension */
  case External(override val toLowerCase: String) extends FileExtension(toLowerCase)

  override def toString: String = toLowerCase

  def isJarOrZip: Boolean = FileExtension.JarOrZip.is(this)

object FileExtension:

  private val JarOrZip: FlagSet[FileExtension] = FlagSet.empty | Zip | Jar

  // this will be optimised to a single hashcode + equality check, and then fallback to slowLookup,
  // keep in sync with slowLookup.
  private def initialLookup(s: String): FileExtension = s match
    case "tasty" => Tasty
    case "class" => Class
    case "jar" => Jar
    case "scala" => Scala
    case "java" => Java
    case "zip" => Zip
    case "inc" => Inc
    case _ => slowLookup(s)

  // slower than initialLookup, keep in sync with initialLookup
  private def slowLookup(s: String): FileExtension =
    if s.equalsIgnoreCase("tasty") then Tasty
    else if s.equalsIgnoreCase("class") then Class
    else if s.equalsIgnoreCase("jar") then Jar
    else if s.equalsIgnoreCase("scala") then Scala
    else if s.equalsIgnoreCase("java") then Java
    else if s.equalsIgnoreCase("zip") then Zip
    else if s.equalsIgnoreCase("inc") then Inc
    else External(s)

  def from(s: String): FileExtension =
    if s.isEmpty then Empty
    else initialLookup(s)
