package java.nio.file

enum StandardCopyOption extends CopyOption {
  case REPLACE_EXISTING, COPY_ATTRIBUTES, ATOMIC_MOVE
}
