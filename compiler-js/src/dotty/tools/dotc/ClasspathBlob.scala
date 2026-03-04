package dotty.tools.dotc

import scala.scalajs.js
import scala.scalajs.js.typedarray._

import dotty.tools.io.{VirtualDirectory, VirtualFile}

/** Loads a classpath binary archive into a VirtualDirectory tree.
 *
 *  Archive format:
 *    [4 bytes: index length L, big-endian uint32]
 *    [L bytes: JSON index, UTF-8]
 *    [data bytes: concatenated file contents]
 *
 *  JSON index maps relative paths to [offset, size] pairs
 *  (offsets relative to data section start).
 */
object ClasspathBlob:

  def load(buffer: ArrayBuffer): VirtualDirectory =
    val view = new DataView(buffer)

    // Read 4-byte big-endian index length
    val indexLen = view.getUint32(0).toInt

    // Decode JSON index
    val indexBytes = new Uint8Array(buffer, 4, indexLen)
    val decoder = js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)("utf-8")
    val indexJson = decoder.decode(indexBytes).asInstanceOf[String]
    val index = js.JSON.parse(indexJson).asInstanceOf[js.Dictionary[js.Array[Int]]]

    val dataOffset = 4 + indexLen
    val root = new VirtualDirectory("(classpath)", None)

    index.foreach { case (path, arr) =>
      val fileOffset = arr(0)
      val fileSize = arr(1)

      // Extract file bytes from the data section
      val fileBytes = new Int8Array(buffer, dataOffset + fileOffset, fileSize)
      val byteArray = new Array[Byte](fileSize)
      var i = 0
      while i < fileSize do
        byteArray(i) = fileBytes(i)
        i += 1

      // Create directory tree and file
      val parts = path.split('/')
      var dir: VirtualDirectory = root
      // Navigate/create intermediate directories
      var j = 0
      while j < parts.length - 1 do
        dir = dir.subdirectoryNamed(parts(j)).asInstanceOf[VirtualDirectory]
        j += 1

      // Create the file and write content
      val file = dir.fileNamed(parts.last)
      val out = file.output
      out.write(byteArray)
      out.close()
    }

    root
