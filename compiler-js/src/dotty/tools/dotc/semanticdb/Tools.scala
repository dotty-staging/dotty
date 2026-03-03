package dotty.tools.dotc.semanticdb

import java.nio.file.*
import java.nio.charset.StandardCharsets
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.semanticdb.Scala3.given

/** Stub Tools for Scala.js - SemanticDB not needed for MVP */
private[semanticdb] object Tools:

  def mkURIstring(path: Path): String =
    import scala.jdk.CollectionConverters.*
    val uriParts = for part <- path.asScala yield new java.net.URI(null, null, "/" + part.toString, null)
    uriParts.mkString.stripPrefix("/")

  def loadTextDocument(
    scalaAbsolutePath: Path,
    scalaRelativePath: Path,
    semanticdbAbsolutePath: Path
  ): TextDocument =
    throw new UnsupportedOperationException("SemanticDB loading not supported on Scala.js")
