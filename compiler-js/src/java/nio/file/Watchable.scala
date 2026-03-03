package java.nio.file

/** Stub Watchable for Scala.js */
trait Watchable {
  def register(watcher: WatchService, events: Array[WatchEvent.Kind[?]]): WatchKey
}
