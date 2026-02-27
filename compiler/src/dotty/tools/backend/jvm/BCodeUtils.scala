package dotty.tools.backend.jvm

object BCodeUtils {
  // The JVM enforces a max length of 65535 bytes per UTF-8 constant.
  // Java uses "Modified UTF-8", in which the null character specifically is two bytes,
  // and the rest of the BMP is as usual, see https://docs.oracle.com/javase/8/docs/api/java/io/DataInput.html#modified-utf-8
  // Outside the BMP, characters are represented as surrogate pairs, i.e., 2+2 bytes, but `charAt` sees them as separate "characters".
  // This means if we see a surrogate pair in a string, we should count each half as 2 bytes, since the encoded UTF-8 character will be 4 bytes.
  // One consequence of this is that the maximum number of UTF-8 bytes for a single Java `char` (not codepoint!) is 3.
  private val MAX_BYTES_PER_UTF8_CONSTANT = 65535
  private val MAX_BYTES_PER_CHAR = 3

  /** Checks that the given name, or the concatenation of the given two names and descriptor if present, do not exceed the JVM's UTF-8 text size limits. */
  def checkConstantStringLength(name: String, other: String = ""): Boolean = {
    var byteCount = 0
    def check(str: String): Boolean =
      var i = 0
      while i < str.length do
        val c = str.charAt(i)
        byteCount += (
          if c == 0x00 then 2
          else if c <= 0x7F then 1
          else if c <= 0x7FF then 2
          else if Character.isHighSurrogate(c) || Character.isLowSurrogate(c) then 2
          else 3 
        )
        if byteCount > MAX_BYTES_PER_UTF8_CONSTANT then
          return false
        i += 1
      true
    // For performance, since we expect few large strings, check if the string is obviously fine first.
    name.length + other.length <= MAX_BYTES_PER_UTF8_CONSTANT / MAX_BYTES_PER_CHAR || (check(name) && check(other))
  }
}