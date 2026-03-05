package dotty.tools.dotc

import scala.scalajs.js
import scala.scalajs.js.typedarray._
import scala.scalajs.js.JSConverters._
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

import org.scalajs.linker._
import org.scalajs.linker.interface._
import org.scalajs.linker.standard.MemIRFileImpl
import org.scalajs.logging._

object BrowserLinker:

  private var librarySjsirFiles: Map[String, Array[Byte]] = Map.empty

  def loadLibraries(buffer: ArrayBuffer): Unit =
    librarySjsirFiles = parseArchive(buffer)

  def link(userSjsirFiles: Map[String, Array[Byte]],
           mainClass: String,
           mainMethod: String = "main"): js.Promise[String] =

    val config = StandardConfig()
      .withModuleKind(ModuleKind.NoModule)
      .withOptimizer(false)
      .withBatchMode(true)
      .withSourceMap(false)
      .withESFeatures(_.withESVersion(ESVersion.ES2018))

    val linker = StandardImpl.linker(config)

    val allSjsir = librarySjsirFiles ++ userSjsirFiles
    val irFiles: Seq[IRFile] = allSjsir.map { case (path, bytes) =>
      new MemIRFileImpl(path, org.scalajs.ir.Version.Unversioned, bytes): IRFile
    }.toSeq

    val moduleInits = Seq(
      ModuleInitializer.mainMethodWithArgs(mainClass, mainMethod, Nil)
    )
    val outDir = MemOutputDirectory()

    val result: Future[String] = linker
      .link(irFiles, moduleInits, outDir, new ScalaConsoleLogger(Level.Warn))
      .map { report =>
        val jsFileName = report.publicModules.head.jsFileName
        new String(outDir.content(jsFileName).get, "UTF-8")
      }

    result.toJSPromise

  private def parseArchive(buffer: ArrayBuffer): Map[String, Array[Byte]] =
    val view = new DataView(buffer)
    val indexLen = view.getUint32(0).toInt
    val indexBytes = new Uint8Array(buffer, 4, indexLen)
    val decoder = js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)("utf-8")
    val indexJson = decoder.decode(indexBytes).asInstanceOf[String]
    val index = js.JSON.parse(indexJson).asInstanceOf[js.Dictionary[js.Array[Int]]]
    val dataOffset = 4 + indexLen

    index.map { case (path, arr) =>
      val fileOffset = arr(0)
      val fileSize = arr(1)
      val fileBytes = new Int8Array(buffer, dataOffset + fileOffset, fileSize)
      val byteArray = new Array[Byte](fileSize)
      var i = 0
      while i < fileSize do
        byteArray(i) = fileBytes(i)
        i += 1
      (path, byteArray)
    }.toMap
