package dotty.tools
package dotc
package cc

import core.*
import printing.*
import Phases.*, DenotTransformers.*, SymDenotations.*
import Contexts.*, Names.*, Flags.*, Symbols.*, Decorators.*
import Hashable.Binders
import Types.*, StdNames.*, Denotations.*
import annotation.constructorOnly

object MutableCaptures:
  case class MutableRef(owner: Symbol, isRead: Boolean) extends CaptureRef, Showable:
    def underlying(using Context): Type = owner.typeRef
    def canBeTracked(using Context): Boolean = true
    def computeHash(bs: Binders): Int = hash
    def hash: Int = System.identityHashCode(this)

    def derivedMutableRef(owner: Symbol, isRead: Boolean)(using Context): MutableRef =
      if (owner eq this.owner) && (isRead == this.isRead) then this
      else MutableRef(owner, isRead)

