package java.net

class URL(spec: String) {
  def this(protocol: String, host: String, port: Int, file: String) =
    this(s"$protocol://$host${if (port >= 0) s":$port" else ""}$file")
  def this(protocol: String, host: String, file: String) =
    this(protocol, host, -1, file)

  def toURI: URI = new URI(spec)
  def toExternalForm: String = spec
  def getProtocol: String = {
    val idx = spec.indexOf("://")
    if (idx >= 0) spec.substring(0, idx) else ""
  }
  def getPath: String = {
    val afterScheme = spec.indexOf("://")
    if (afterScheme < 0) spec
    else {
      val afterHost = spec.indexOf('/', afterScheme + 3)
      if (afterHost < 0) "/" else spec.substring(afterHost)
    }
  }

  def openStream(): java.io.InputStream =
    throw new java.io.IOException("URL.openStream not supported on Scala.js")
  def openConnection(): java.net.URLConnection =
    throw new java.io.IOException("URL.openConnection not supported on Scala.js")

  override def toString: String = spec
}
