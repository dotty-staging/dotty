package java.security

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.*

/** MessageDigest for Scala.js backed by Node.js crypto module. */
abstract class MessageDigest(private val algorithm: String) {
  private val hash: js.Dynamic = MessageDigest.crypto.createHash(MessageDigest.algorithmName(algorithm))

  def update(input: Byte): Unit = {
    val buf = new Int8Array(1)
    buf(0) = input
    hash.update(new Uint8Array(buf.buffer))
  }

  def update(input: Array[Byte]): Unit =
    if input.length > 0 then
      hash.update(new Uint8Array(input.toTypedArray.buffer))

  def update(input: Array[Byte], offset: Int, len: Int): Unit =
    if len > 0 then
      val slice = new Int8Array(len)
      var i = 0
      while i < len do
        slice(i) = input(offset + i)
        i += 1
      hash.update(new Uint8Array(slice.buffer))

  def update(input: java.nio.ByteBuffer): Unit = ()

  def digest(): Array[Byte] = {
    val buf = hash.digest().asInstanceOf[js.Dynamic]
    val len = buf.length.asInstanceOf[Int]
    val result = new Array[Byte](len)
    var i = 0
    while i < len do
      result(i) = buf.selectDynamic(i.toString).asInstanceOf[Int].toByte
      i += 1
    result
  }

  def digest(input: Array[Byte]): Array[Byte] = { update(input); digest() }

  def reset(): Unit = ()

  def getAlgorithm(): String = algorithm
}

object MessageDigest {
  private lazy val crypto: js.Dynamic =
    js.Dynamic.global.require("crypto")

  private def algorithmName(alg: String): String = alg.toLowerCase.replace("-", "")

  def getInstance(algorithm: String): MessageDigest =
    new MessageDigest(algorithm) {}
}
