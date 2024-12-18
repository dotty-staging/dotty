package scala

object Init:
  /** Marker for `var`s that are initialized to `null`, but cannot be assigned `null` after initialization.
   *  @example {{{
   *    var cache: String | Uninitialized = initiallyNull
   *    def readCache: String =
   *      if cache == null then cache = "hello"
   *      cache
   *  }}}
   */
  type Uninitialized <: Null
  /** Initializer for `var`s of type `Uninitialized`
   *  Can only be used on the RHS of definitions.
   */
  val initiallyNull: Uninitialized = null.asInstanceOf[Uninitialized]
