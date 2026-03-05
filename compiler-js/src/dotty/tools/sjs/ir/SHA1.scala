package dotty.tools.sjs.ir

import scala.language.unsafeNulls

/** Pure-Scala SHA-1 implementation for browser environments.
 *
 *  Replaces the JVM version that delegates to java.security.MessageDigest,
 *  which in Scala.js uses Node.js `require("crypto")` — unavailable in browsers.
 */
object SHA1 {
  final class DigestBuilder {
    private var buf = new Array[Byte](64)
    private var bufLen = 0
    private var totalLen = 0L

    // SHA-1 state
    private var h0 = 0x67452301
    private var h1 = 0xEFCDAB89.toInt
    private var h2 = 0x98BADCFE.toInt
    private var h3 = 0x10325476
    private var h4 = 0xC3D2E1F0.toInt

    def update(b: Byte): Unit = {
      buf(bufLen) = b
      bufLen += 1
      totalLen += 1
      if (bufLen == 64) {
        processBlock(buf, 0)
        bufLen = 0
      }
    }

    def update(b: Array[Byte]): Unit =
      update(b, 0, b.length)

    def update(b: Array[Byte], off: Int, len: Int): Unit = {
      var i = off
      val end = off + len
      totalLen += len

      // Fill current buffer
      if (bufLen > 0) {
        val toCopy = Math.min(64 - bufLen, end - i)
        System.arraycopy(b, i, buf, bufLen, toCopy)
        bufLen += toCopy
        i += toCopy
        if (bufLen == 64) {
          processBlock(buf, 0)
          bufLen = 0
        }
      }

      // Process full blocks directly
      while (i + 64 <= end) {
        processBlock(b, i)
        i += 64
      }

      // Buffer remainder
      if (i < end) {
        val rem = end - i
        System.arraycopy(b, i, buf, 0, rem)
        bufLen = rem
      }
    }

    def updateUTF8String(str: UTF8String): Unit =
      update(str.bytes)

    def finalizeDigest(): Array[Byte] = {
      val totalBits = totalLen * 8

      // Padding: append 1-bit, then zeros, then 64-bit length
      update(0x80.toByte)
      while (bufLen != 56) {
        update(0.toByte)
      }

      // Append length in big-endian
      val lenBytes = new Array[Byte](8)
      var i = 7
      var bits = totalBits
      while (i >= 0) {
        lenBytes(i) = (bits & 0xff).toByte
        bits >>>= 8
        i -= 1
      }
      // Undo the totalLen additions from the length bytes themselves
      val savedTotal = totalLen
      update(lenBytes)
      totalLen = savedTotal

      // Produce result
      val result = new Array[Byte](20)
      intToBytes(h0, result, 0)
      intToBytes(h1, result, 4)
      intToBytes(h2, result, 8)
      intToBytes(h3, result, 12)
      intToBytes(h4, result, 16)
      result
    }

    private def intToBytes(v: Int, out: Array[Byte], off: Int): Unit = {
      out(off)     = (v >>> 24).toByte
      out(off + 1) = (v >>> 16).toByte
      out(off + 2) = (v >>> 8).toByte
      out(off + 3) = v.toByte
    }

    private def processBlock(data: Array[Byte], offset: Int): Unit = {
      val w = new Array[Int](80)

      // Load 16 words from block (big-endian)
      var i = 0
      while (i < 16) {
        val j = offset + i * 4
        w(i) = ((data(j) & 0xff) << 24) |
               ((data(j + 1) & 0xff) << 16) |
               ((data(j + 2) & 0xff) << 8) |
               (data(j + 3) & 0xff)
        i += 1
      }

      // Extend to 80 words
      while (i < 80) {
        w(i) = Integer.rotateLeft(w(i-3) ^ w(i-8) ^ w(i-14) ^ w(i-16), 1)
        i += 1
      }

      var a = h0
      var b = h1
      var c = h2
      var d = h3
      var e = h4

      i = 0
      while (i < 80) {
        val (f, k) =
          if (i < 20) ((b & c) | (~b & d), 0x5A827999)
          else if (i < 40) (b ^ c ^ d, 0x6ED9EBA1)
          else if (i < 60) ((b & c) | (b & d) | (c & d), 0x8F1BBCDC.toInt)
          else (b ^ c ^ d, 0xCA62C1D6.toInt)

        val temp = Integer.rotateLeft(a, 5) + f + e + k + w(i)
        e = d
        d = c
        c = Integer.rotateLeft(b, 30)
        b = a
        a = temp
        i += 1
      }

      h0 += a
      h1 += b
      h2 += c
      h3 += d
      h4 += e
    }
  }
}
