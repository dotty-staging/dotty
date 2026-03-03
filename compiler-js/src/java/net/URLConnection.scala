package java.net

abstract class URLConnection(val url: URL) {
  def getInputStream: java.io.InputStream =
    throw new java.io.IOException("URLConnection not supported on Scala.js")
  def connect(): Unit =
    throw new java.io.IOException("URLConnection not supported on Scala.js")
}
