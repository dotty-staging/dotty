/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc. dba Akka
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala
package reflect
package runtime

import scala.language.`2.13`

// scala/bug#6240: test thread-safety, make trees synchronized as well
private[reflect] trait SynchronizedOps extends internal.SymbolTable
                         with SynchronizedSymbols
                         with SynchronizedTypes { self: SymbolTable =>

// Names

  override protected def synchronizeNames = true

// BaseTypeSeqs

  // Scala 3 port: named subclasses instead of anonymous `new BaseTypeSeq(...) with
  // SynchronizedBaseTypeSeq`: dotc's ExplicitOuter cannot construct the outer path for an
  // anonymous class that mixes inner members of two different layers of the cake.
  private final class SynchronizedBaseTypeSeqImpl(parents: List[Type], elems: Array[Type])
    extends BaseTypeSeq(parents, elems) with SynchronizedBaseTypeSeq
  private final class SynchronizedMappedBaseTypeSeqImpl(orig: BaseTypeSeq, f: Type => Type)
    extends MappedBaseTypeSeq(orig, f) with SynchronizedBaseTypeSeq

  override protected def newBaseTypeSeq(parents: List[Type], elems: Array[Type]) =
    // only need to synchronize BaseTypeSeqs if they contain refined types
    if (elems.exists(_.isInstanceOf[RefinedType])) new SynchronizedBaseTypeSeqImpl(parents, elems)
    else new BaseTypeSeq(parents, elems)

  override protected def newMappedBaseTypeSeq(orig: BaseTypeSeq, f: Type => Type) =
    // MappedBaseTypeSeq's are used rarely enough that we unconditionally mixin the synchronized
    // wrapper, rather than doing this conditionally. A previous attempt to do that broke the "late"
    // part of the "lateMap" contract in inspecting the mapped elements.
    new SynchronizedMappedBaseTypeSeqImpl(orig, f)

  trait SynchronizedBaseTypeSeq extends BaseTypeSeq {
    override def apply(i: Int): Type = gilSynchronized { super.apply(i) }
    override def rawElem(i: Int) = gilSynchronized { super.rawElem(i) }
    override def typeSymbol(i: Int): Symbol = gilSynchronized { super.typeSymbol(i) }
    override def toList: List[Type] = gilSynchronized { super.toList }
    override def copy(head: Type, offset: Int): BaseTypeSeq = gilSynchronized { super.copy(head, offset) }
    override def map(f: Type => Type): BaseTypeSeq = gilSynchronized { super.map(f) }
    override def exists(p: Type => Boolean): Boolean = gilSynchronized { super.exists(p) }
    override lazy val maxDepth = gilSynchronized { maxDepthOfElems }
    override def toString = gilSynchronized { super.toString }
  }

// Scopes

  override def newScope = new Scope with SynchronizedScope

  trait SynchronizedScope extends Scope {
    // we can keep this lock fine-grained, because methods of Scope don't do anything extraordinary, which makes deadlocks impossible
    // fancy subclasses of internal.Scopes#Scope should do synchronization themselves (e.g. see PackageScope for an example)
    private lazy val syncLock = new Object
    def syncLockSynchronized[T](body: => T): T = if (isCompilerUniverse) body else syncLock.synchronized { body }
    override def isEmpty: Boolean = syncLockSynchronized { super.isEmpty }
    override def size: Int = syncLockSynchronized { super.size }
    override def enter[T <: Symbol](sym: T): sym.type = syncLockSynchronized { super.enter(sym) }
    override def rehash(sym: Symbol, newname: Name) = syncLockSynchronized { super.rehash(sym, newname) }
    override def unlink(e: ScopeEntry) = syncLockSynchronized { super.unlink(e) }
    override def unlink(sym: Symbol) = syncLockSynchronized { super.unlink(sym) }
    override def lookupAll(name: Name) = syncLockSynchronized { super.lookupAll(name) }
    override def lookupEntry(name: Name) = syncLockSynchronized { super.lookupEntry(name) }
    override def lookupNextEntry(entry: ScopeEntry) = syncLockSynchronized { super.lookupNextEntry(entry) }
    override def toList: List[Symbol] = syncLockSynchronized { super.toList }
  }
}
