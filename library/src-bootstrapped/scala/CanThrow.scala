package scala
import language.experimental.erasedTerms
import annotation.implicitNotFound

/** A capability class that allows to throw exception `E`. When used with the
 *  experimental.saferThrows feature, a `throw Ex` expression will require
 *  a given of class `CanThrow[Ex]` to be available.
 */
@implicitNotFound("The capability to throw exception ${E} is missing.\nThe capability can be provided by one of the following:\n - A using clause `(using CanThrow[${E}])`\n - A `canThrow` clause in a result type such as `X canThrow ${E}`\n - an enclosing `try` that catches ${E}")
erased class CanThrow[-E <: Exception]

/** A helper type to allow syntax like
 *
 *    def f(): T throws Ex
 */
infix type canThrow[R, +E <: Exception] = CanThrow[E] ?=> R

object unsafeExceptions:
  given canThrowAny[E <: Exception]: CanThrow[E] = ???
