package java.nio.file

enum StandardOpenOption extends OpenOption {
  case READ, WRITE, APPEND, TRUNCATE_EXISTING, CREATE, CREATE_NEW, DELETE_ON_CLOSE, SPARSE, SYNC, DSYNC
}
