package java.nio.file

enum FileVisitResult:
  case CONTINUE, TERMINATE, SKIP_SUBTREE, SKIP_SIBLINGS
