package java.nio.file

import java.io.Closeable

trait DirectoryStream[T] extends Closeable with java.lang.Iterable[T]
