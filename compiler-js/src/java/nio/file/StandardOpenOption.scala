package java.nio.file

/** Stub StandardOpenOption — using sealed abstract class to match Java enum accessor patterns. */
sealed abstract class StandardOpenOption(name: String, ordinal: Int) extends OpenOption {
  override def toString: String = name
}

object StandardOpenOption {
  val READ: StandardOpenOption = new StandardOpenOption("READ", 0) {}
  val WRITE: StandardOpenOption = new StandardOpenOption("WRITE", 1) {}
  val APPEND: StandardOpenOption = new StandardOpenOption("APPEND", 2) {}
  val TRUNCATE_EXISTING: StandardOpenOption = new StandardOpenOption("TRUNCATE_EXISTING", 3) {}
  val CREATE: StandardOpenOption = new StandardOpenOption("CREATE", 4) {}
  val CREATE_NEW: StandardOpenOption = new StandardOpenOption("CREATE_NEW", 5) {}
  val DELETE_ON_CLOSE: StandardOpenOption = new StandardOpenOption("DELETE_ON_CLOSE", 6) {}
  val SPARSE: StandardOpenOption = new StandardOpenOption("SPARSE", 7) {}
  val SYNC: StandardOpenOption = new StandardOpenOption("SYNC", 8) {}
  val DSYNC: StandardOpenOption = new StandardOpenOption("DSYNC", 9) {}
}
