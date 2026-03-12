inline trait Iterator[T: Specialized]:
  def hasNext: Boolean
  def next(): T

// They do this: (with Specialized type class)
inline trait ArrayIterator[T: Specialized](elems: Array[T]) extends Iterator[T]:
  private var current = 0
  def hasNext: Boolean = current < elems.length
  def next(): T = try elems(current) finally current += 1


// We should generate these:
// inline trait ArrayIterator$sp$Int extends ArrayIterator[Int], Iterator[Int]
// class ArrayIterator$impl$Int(elems: Array[Int]) extends ArrayIterator$sp$Int, ArrayIterator[Int](elems)

// Inline traits does the magic of actually inlining the code and specialising from T to Int in that step.


// They do this:
def foo(x: ArrayIterator[Int]): Int = x.next()
// We convert this to:
// def foo(x: ArrayIterator$sp$Int): Int = x.next()
// As long as we generate this (i.e. "do the special erasure") before we run inline traits we should be fine because then the reference will be replaced.


// They do this:
// class MyClassA
// class MyClassB extends MyClassA, ArrayIterator[Int]

// // We convert this to:
// class MyClassA
// class MyClassB extends MyClassA, ArrayIterator$sp$Int

// @main def main = 
//     val xs: Array[Int] = Array(1, 2, 3)

//     // They do this:
//     // new ArrayIterator[Int](xs) {}

//     // We convert this to:
//     val ai = new ArrayIterator$impl$Int(xs) {}
//     println(ai.next())



      // println(genericTrait.denot.info.appliedTo(genericTrait.denot.info.typeParams))
      // println(genericTrait.denot.info.widenDealias.)
// instantiateWithTypeVars
      // instantiateWithTypeVars()

      // newNormalizedClassSymbol(
      //   genericTrait.owner,
      //   "CopiedSymbol",
      //    Flags.Synthetic | Flags.Inline | Flags.Trait,
      //    parents,
      //   NoType, // TODO: Work out what to do about self types; for now just ban them 
      //   genericTrait.privateWithin,
      //   compUnitInfo = genericTrait.compUnitInfo
      // )



      // // Now add the constructor

      // selfInfo: Type = NoType,
      // // Need to figure out how we leave some of the necessary generics for unspecialized params.
      // ctx.
      
      
      // genericTrait.copy(
      //   name=
      //   flags=genericTrait.flags | Flags.Synthetic,

      // )
      // newNormalizedClassSymbol()
      // inline trait ArrayIterator$sp$Int extends ArrayIterator[Int], Iterator[Int]
      


    // private def inlinedClassSym(sym: ClassSymbol, withoutFlags: FlagSet = EmptyFlags)(using Context): ClassSymbol =
    //   sym.info match {
    //     case clsInfo: ClassInfo =>
    //       val typeParams: List[Type] = sym.primaryConstructor.info match {
    //         case poly: PolyType => poly.paramRefs
    //         case _ => Nil
    //       }
    //       // Extend inner class from inline trait to preserve typing
    //       val newParent = ctx.owner.thisType.select(sym).appliedTo(typeParams)
    //       val inlinedSym = (
    //         ctx.owner,
    //         sym.name,
    //         (sym.flags | Synthetic) &~ withoutFlags,
    //         newCls => {
    //           val ClassInfo(prefix, _, parents, _, selfInfo) = inlinerTypeMap.mapClassInfo(clsInfo)
    //           ClassInfo(prefix, newCls, parents :+ newParent, Scopes.newScope, selfInfo) // TODO fix selfInfo (what to use?)
    //         },
    //         sym.privateWithin,
    //         spanCoord(parent.span)
    //       )
    //       // ctx.inlineTraitState.registerInlinedInnerClassSymbol(sym, inlinedSym, childThisType)
    //       ctx.inlineTraitState.registerInlinedSymbol(sym, inlinedSym, childThisType.widenDealias)
    //       inlinedSym.entered
    //     case _ =>
    //       report.error(s"Class symbol ${sym.show} does not have class info")
    //       sym
    //   }

// specializedTraitSymbol.copy(
//         name="GeneratedSpecializedSymbol",

//       )

  //     Symbols.new
  // def (
  //     owner: Symbol,
  //     name: TypeName,
  //     flags: FlagSet,
  //     parentTypes: List[Type],
  //     selfInfo: Type = NoType,
  //     privateWithin: Symbol = NoSymbol,
  //     coord: Coord = NoCoord,
  //     compUnitInfo: CompilationUnitInfo | Null = null)(using Context): ClassSymbol = {


  // def newClass(owner: Symbol, name: String, parents: List[TypeRepr], decls: Symbol => List[Symbol], selfType: Option[TypeRepr]): Symbol =
  //       assert(parents.nonEmpty && !parents.head.typeSymbol.is(dotc.core.Flags.Trait), "First parent must be a class")
  //       val cls = dotc.core.Symbols.newNormalizedClassSymbol(
  //         owner,
  //         name.toTypeName,
  //         dotc.core.Flags.EmptyFlags,
  //         parents,
  //         selfType.getOrElse(Types.NoType),
  //         dotc.core.Symbols.NoSymbol)
  //       cls.enter(dotc.core.Symbols.newConstructor(cls, dotc.core.Flags.Synthetic, Nil, Nil))
  //       for sym <- decls(cls) do cls.enter(sym)
  //       cls


  //   }

  //   private def newSpecializedTraitImplementationClass() {
  //   //    class ArrayIterator$impl$Int(elems: Array[Int]) extends ArrayIterator$sp$Int, ArrayIterator[Int](elems)

  //   }