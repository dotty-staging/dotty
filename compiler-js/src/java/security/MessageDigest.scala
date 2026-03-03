package java.security

/** Stub MessageDigest for Scala.js — only supports basic operations needed by the compiler. */
abstract class MessageDigest(private val algorithm: String) {
  def update(input: Byte): Unit = ()
  def update(input: Array[Byte]): Unit = ()
  def update(input: Array[Byte], offset: Int, len: Int): Unit = ()
  def update(input: java.nio.ByteBuffer): Unit = ()
  def digest(): Array[Byte] = Array.emptyByteArray
  def digest(input: Array[Byte]): Array[Byte] = { update(input); digest() }
  def reset(): Unit = ()
  def getAlgorithm(): String = algorithm
}

object MessageDigest {
  def getInstance(algorithm: String): MessageDigest = new MessageDigest(algorithm) {}
}
