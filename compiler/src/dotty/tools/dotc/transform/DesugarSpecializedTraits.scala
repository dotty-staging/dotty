package dotty.tools.dotc.transform

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.transform.MegaPhase.MiniPhase
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.i
import dotty.tools.dotc.{transform => Vec}
import dotty.tools.dotc.{transform => foo}
import dotty.tools.dotc.{transform => v}
import dotty.tools.dotc.core.Decorators.className
import dotty.tools.dotc.core.Symbols.{Symbol, ClassSymbol, newNormalizedClassSymbol}
import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.core.StdNames.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Symbols.newClassSymbol
import dotty.tools.dotc.typer.ProtoTypes.instantiateWithTypeVars
import scala.Function.const
import dotty.tools.dotc.core.Names.TypeName
import dotty.tools.dotc.core.Symbols.TypeSymbol
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Symbols.defn
import dotty.tools.dotc.core.Flags.EmptyFlags
import dotty.tools.dotc.ast.TreeTypeMap
import dotty.tools.dotc.core.Scopes.EmptyScope
import dotty.tools.dotc.core.StdNames.str.SPECIALIZED_TRAIT_SUFFIX
import Vec.DesugarSpecializedTraits.newSpecializedTraitName
import dotty.tools.dotc.core.Names.Name
import tpd._

class DesugarSpecializedTraits extends MacroTransform:

  override def phaseName: String = DesugarSpecializedTraits.name
  override def description: String = DesugarSpecializedTraits.description
  override def changesMembers: Boolean = false
  override def changesParents: Boolean = true 
  // override def transformTemplate(tree: Template)(using Context): Tree = 
  //   // println(s"template ${tree}")
  //   tree.deepFold()
  //   tree

  // override def transformTyped(tree: Typed)(using Context): Tree = ???
// 
  // override def transformTypeApply(tree: TypeApply)(using Context): Tree = ???
  
  // override def transformTypeTree(tree: TypeTree)(using Context): Tree = tree match {

  // }
  // override def transformDefDef(tree: DefDef)(using Context): Tree = 
  //   println(s"defdef ${tree}")
  //   if (tree.name.toString() == "foo") {
      // val ValDef(v, , EmptyTree) = tree.paramss.head.head
  //     // DefDef(foo,List(List(ValDef(v,AppliedTypeTree(Ident(Vec),List(Ident(Int))),EmptyTree))),TypeTree[AppliedType(TypeRef(ThisType(TypeRef(NoPrefix,module class <empty>)),trait Vec),List(TypeRef(TermRef(ThisType(TypeRef(NoPrefix,module class <root>)),object scala),class Int)))],Ident(v))
  //   }
  //   tree

  // need some short circuit logic for if we've already processed this one. 

  override def run(using Context): Unit =
    try super.run
    catch case _: CompilationUnit.SuspendException => ()

  
  override def newTransformer(using Context): Transformer = new Transformer {

    object SpecializedEvidence {
      def unapply(tpe: Type)(using Context): Option[Type] = tpe match {
        case AppliedType(tycon, List(tpeArg)) if tycon =:= ctx.definitions.SpecializedBoundRef => Some(tpeArg)
        case _ => None
      }
    }

    private def newSpecializedTraitInterfaceTrait(specializedTrait: Ident, specializationMap: Map[Type, Tree]) =
      println(specializationMap)
      val specializedTraitSymbol = specializedTrait.denot.symbol

      val tm = new TypeMap:
        def apply(t: Type) = specializationMap.view.mapValues(_.tpe).applyOrElse(t, mapOver)

      val specialization = specializedTraitSymbol.typeParams.map(_.typeRef).map(specializationMap.applyOrElse(_, TypeTree(_)))

      val parents = defn.ObjectType
                    :: AppliedTypeTree(cpy.Ident(specializedTrait)(specializedTrait.name), specialization).tpe
                    :: specializedTrait.denot.info.parents.filterNot(_ eq defn.ObjectType).map(tm(_)) // parents of the original trait **but then specialized** 





      val traitSymbol = newNormalizedClassSymbol(
        specializedTraitSymbol.owner,
        (newSpecializedTraitName(specializedTraitSymbol.name, specializationMap)).asTypeName,
        Flags.Synthetic | Flags.Inline | Flags.Trait,
        parents,
        NoType, // TODO: Work out what to do about self types; for now just ban them 
        specializedTraitSymbol.privateWithin,
        // compUnitInfo = specializedTraitSymbol.compUnitInfo // TODO: Do we need a compUnit info?
      )

      println(s"Owner of tpparam ${specializedTraitSymbol.typeParams.head.owner}")

      val old_type_params = specializedTraitSymbol.typeParams.filterNot(t => specializationMap.contains(t.typeRef))
      val tps = newTypeParams(traitSymbol,
                    old_type_params.map(_.name),
                    EmptyFlags,
                    targets => targets.map(t => specializedTraitSymbol.typeParams.find(_.name == t.name).get.info.bounds)
                )
      tps.foreach(traitSymbol.enter(_, EmptyScope))
      println(i"Got new tps ${tps}")

      val tpMap: Map[Type, Type] = old_type_params.map(_.typeRef).zip(tps.map(_.typeRef)).toMap
      val freshTypeVarMap = new TypeMap:
        def apply(t: Type) = tpMap.applyOrElse(t, mapOver)

      println("the following is the tpMap")
      println(tpMap)

      // val List(traitSymbol1) = mapSymbols(List(traitSymbol),  ttmap)

      // val traitSymbol2 = traitSymbol1.asInstanceOf[ClassSymbol]

      // val init = newDefaultConstructor(traitSymbol)
      // val tmpl = untpd.Template(
      //   DefDef(init),
      //   parents.map(TypeTree(_)),
      //   EmptyValDef,
      //   Nil
      // )

  
      val traitSymbol2 = traitSymbol.subst(old_type_params, tps).asInstanceOf[ClassSymbol]
      traitSymbol.info = 
        ClassInfo(traitSymbol.owner.thisType, traitSymbol, traitSymbol.info.parents.map(freshTypeVarMap(_)), traitSymbol.info.decls, traitSymbol.info.self)
        
      // .info.parents = 
        // denot.info = ClassInfo(owner.thisType, cls, parentTypes.map(_.dealias), decls, selfInfo)

      println("THESE ARE THE parents")
      // println(traitSym, bol2.parentTypes)
      traitSymbol2.entered //, tmpl)

      // println("GOT The following resulting parents")
      // println(parents)


    // // For the given type application of concreteTypes to specializedTraitSymbol, return a list of types 
    // // retaining the type variable from the trait definition if the type variable is not Specialized,
    // // and the concrete type from the application if the type variable is Specialized. 
    // private def getSelectedSpecialization(specializedTraitSymbol: Symbol, concreteTypeTrees: List[Tree]): (List[Tree], Map[Type, Type]) = 



    //   println("HELLO")
    //   println(specializedTraitSymbol.typeParams.map(_.typeRef.symbol))
    //   println(specializedTraitSymbol.paramSymss)
    //   println(specializedTypeVars)
    //   println(i"${}")
      
    //   (List.empty, Map.empty)



    //   specializedTraitSymbol.primaryConstructor.paramSymss match {
    //       case List(typeVars: List[Symbol], implicits: List[Symbol], params: List[Symbol]) =>
    //         print("BUNGLING BAFFLING")
    //         print(i"${typeVars}")
    //         print(typeVars.map(_.owner)) // typeRef.symbol
    //         val concreteTypes = concreteTypeTrees.map(_.tpe)

    //         val indicesWithSpecializedAnnotation = implicits.flatMap(sym => isSpecializedEvidence(sym.denot.info, typeVars))
    //         val typeVarTypes: List[Type] = typeVars.map(_.typeRef)

    //         val typeVarToConcreteTypeMap = Map.from(indicesWithSpecializedAnnotation.map(typeVarTypes.zip(concreteTypes)(_)))
    //         val typeVarToConcreteTypeMapTrees: Map[Symbol, Tree] = Map.from(indicesWithSpecializedAnnotation.map(typeVars.zip(concreteTypeTrees)(_)))

            
    //         (specializationTypeTrees, typeVarToConcreteTypeMap)
    //       case _ => (List.empty, Map.empty)
    //   }

    private def buildClassTree(originalTraitSymbol: Symbol, generatedTraitSymbol: ClassSymbol)(using Context) = {
      val init = newDefaultConstructor(generatedTraitSymbol)
      
      // init.setParamss(List(generatedTraitSymbol.typeParams))
    
      // val init = originalTraitSymbol.primaryConstructor.copy(owner = generatedTraitSymbol,
      //                                        flags = originalTraitSymbol.primaryConstructor.flags | Flags.Synthetic)
      //                                        .asInstanceOf[TermSymbol]
      println(i"GOT PARAM NAMES OG ${originalTraitSymbol.primaryConstructor.info.paramNamess}")
      println(i"GOT PARAM NAMES ${init.info.paramNamess}")

      // println(s"Got init2 constructor info ${init2.info}")
      println(s"Got init constructor info ${init.info}")

      val rt = generatedTraitSymbol.typeRef.appliedTo(generatedTraitSymbol.typeParams.map(_.typeRef))
      def resultType(tpe: Type): Type = tpe match {
          case mt @ MethodType(paramNames) => mt.derivedLambdaType(paramNames, mt.paramInfos, rt)
          case pt : PolyType => pt.derivedLambdaType(pt.paramNames, pt.paramInfos, resultType(pt.resType))
      }

      init.info = resultType(init.info)
      init.info = PolyType.fromParams(init.owner.typeParams, init.info)

      assert(originalTraitSymbol.primaryConstructor.rawParamss.length >= 2) // we know we at least have type params and evidences
      val evidences = (originalTraitSymbol.primaryConstructor.rawParamss: @unchecked) match { 
        case List(_, _, evidences) => evidences
        case List(_, evidences) => evidences
      }
      println(s"Evidences ${evidences.head.info}")

      // val tpMap: Map[Type, Type] = old_type_params.map(_.typeRef).zip(tps.map(_.typeRef)).toMap
      // val typeParamMap = new TypeMap:
      //   def apply(t: Type) = tpMap.applyOrElse(t, mapOver.

      // Claim that we simply don't need to worry about the evidences because they will be dealt with when instantiating the parent
      // and we always have the inheritance invariance that we discussed with Hamza.
      // val newEvidences = evidences.collect(_.info match {
      //   case a@AppliedType(tycon, args) if (tycon =:= ctx.definitions.SpecializedBoundRef) => None
      //   case tpe => typeParamMap(tpe)
      // })
      // println(s"Generated resulting evidences ${newEvidences}")
      // println("Resulted in the following")
      // println(init.paramSymss)

      // println(paramss)

      // val constrTps = 
      //     newTypeParams(init,
      //             generatedTraitSymbol.typeParams.map(_.name),
      //             EmptyFlags,
      //             targets => targets.map(t => generatedTraitSymbol.typeParams.find(_.name == t.name).get.info.bounds)
      //           )

      // List(List(type T, type S, type Q, type R, type D), List(val arr), List(val evidence$1, val evidence$2, val evidence$3, val evidence$4, val evidence$5, val evidence$6))

// List(constrTps)
// ,, generatedTraitSymbol.typeRef, EmptyTree/
//  paramss, generatedTraitSymbol.typeRef, EmptyTree
      // init.paramS
//  paramss, generatedTraitSymbol.typeRef, EmptyTree

      // Would it be easier just to copy and then remove instead of constructing from scratch?
      // println(init.)
      ClassDef(generatedTraitSymbol, DefDef(init.entered), Nil)
    } 

    override def transform(tree: Tree)(using Context): Tree = 
      tree match {
        case pkg@PackageDef(pid, stats) =>
          val stats1 = collectNecessaryGeneratedSymbols(pkg).map(buildClassTree)

          // Template
          println("GENERATED")
          println(stats1)
          println(stats)

          cpy.PackageDef(pkg)(pid, stats1 ++ stats)
      }
    
    private def collectNecessaryGeneratedSymbols(tree: Tree)(using Context): List[(Symbol, ClassSymbol)] = 
      val result: List[(Symbol, ClassSymbol)] = tree.deepFold(List.empty)((found, tree) => tree match
        // case New(AppliedTypeTree etc) -> need to output the impl class -> do we wantto generate that when we see Foo[Int] or not?

        case AppliedTypeTree(specializedTrait: Ident, concreteTypeTrees: List[Tree]) =>
          val specializedTraitSymbol = specializedTrait.denot.symbol

          val specializedTypeVars = specializedTraitSymbol.unforcedDecls.implicitDecls.collect(_.info match { case SpecializedEvidence(typeVar) => typeVar }).toSet
          val specializationMap = specializedTraitSymbol.typeParams.map(_.typeRef.asInstanceOf[Type]).zip(concreteTypeTrees).toMap.filter((k, v) => specializedTypeVars(k))

          if (specializationMap.nonEmpty) {
            val specializedTraitInterfaceTraitSymbol = newSpecializedTraitInterfaceTrait(specializedTrait, specializationMap)
            println(i"Got the following primary constructor {specializedTraitInterfaceTraitSymbol.primaryConstructor}")

            println("OG:")
            println(specializedTraitSymbol.primaryConstructor.paramSymss)
            (specializedTraitSymbol, specializedTraitInterfaceTraitSymbol) :: found
            // specializedTraitInterfaceTraitSymbol.def
            // ctx.
            // TypeDef(specializedTraitInterfaceTraitSymbol)
          }
          else found
        case tree: TypeDef =>
          found
        case _ => found
      )
      print(s"Got the following result ${result}")
      result
      
      // generate the classes
      // do the tree type map
  }


        // val impl1 = cpy.Template(impl)(body = newDefs._1 ::: newDefs._2)
        // cpy.TypeDef(cls)(rhs = impl1)

  // override def newTransformer(using Context): Transformer = new Transformer {
  //   override def transform(tree: Tree)(using Context): Tree = tree match {
  //     case tree: TypeDef if tree.symbol.isInlineTrait =>
  //       transformInlineTrait(tree)
  //     case tree: TypeDef if Inlines.needsInlining(tree) =>
  //       val tree1 = super.transform(tree).asInstanceOf[TypeDef]
  //       if tree1.tpe.isError then tree1
  //       else if tree1.symbol.isInlineTrait then transformInlineTrait(tree1)
  //       else Inlines.inlineParentInlineTraits(tree1)
  //     case _ => super.transform(tree)
  //   }
  // }



          // And then apply this map twice; once to the completely generic [T, S, R, P, Q] to get specialization, and once (moyennant le fait que we need to apply .tpe to throw away the tree portion to produce the parent type map) 




// how are we building the AST portion?


          // val specialization = specializedTraitSymbol.typeParams.map(_.typeRef).zip(concreteTypeTrees).map(_ match {
          //   case (tpe, concreteTypeTree) if specializedTypeVars(tpe) => concreteTypeTree
          //   case (tpe, _) => TypeTree(tpe)
          // })


          // val specializedTypeParamIndices = specializedTypeVars.map(specializedTraitSymbol.typeParams.indexOf(_))

          // specializedTypeVars.zip(concreteTypeTrees)




          // val specializationTypeTrees = typeVars.map(typeVar => typeVarToConcreteTypeMapTrees.applyOrElse(typeVar, const(TypeRef(NoPrefix, typeVar))))) // , 




          // val (selectedSpecialization, typeMap) = getSelectedSpecialization(specializedTraitSymbol, concreteTypes)
          


// Todo: What happens with the name generation if we have Vec[Vec[T]] for example?

// AppliedType(TypeRef(TermRef(ThisType(TypeRef(NoPrefix,module class <root>)),object scala),trait Specialized),List(TypeRef(NoPrefix,type T)))



// traverse with a fold - how to dealw ith other compilation units? A new phase?
// set flags?

object DesugarSpecializedTraits:
  val name: String = "desugarSpecializedTraits"
  val description: String = "Replaces traits having type parameters that have the Specialized annotation with specialized versions"

  // TODO: Check what happens here when we have a case where the types being specialized into are user defined instead of primitives or type vars.
  private[transform] def newSpecializedTraitName(name: Name, specialization: Map[Type, Tree]) = 
    specialization.values.collect(t => t match {
      case Ident(tpe) => tpe ++ str.SPECIALIZED_TRAIT_TYPE_SEP
    }).fold(name ++ str.SPECIALIZED_TRAIT_SUFFIX)((n1, n2) => n1 ++ n2)

    //.flatten
    // ++ 
    // specialization.map(_.description).concat  //SPECIALIZED_TRAIT_TYPE_SEP

// Cleanup
// Correctly generate names
// generate classes as well
// do we actually want to generate Iteratorsp$Int
// should we be worried about the results that we generate causing more stuff to be generated?
// figure out why we generate the T version.
// Try to see if we can do with only types and not trees
// Synthesise Specialized instances so that people can't do stupid stuff like Specialized[Array[T]]. type x = Specialized[Array[Array[Int]]]

// Potentially we can just go through and find every place which needs one, do a direct replacement and spit it out directly into some kind of list buffer and then 
// copy it out later

// Need to test with one parameter that is specialized and one that isn't
// can we do 

// Probably (tree)typemap

// 1. Figure out which specialisations we need to generate
// 2. Generate ArrayIterator$sp$Int and ArrayIterator$impl$Int wherever they live
// 3. Replace ArrayIterator[Int] with ArrayIterator$sp$Int
// 4. Replace new ArrayIterator[Int](xs) {} with new ArrayIterator$impl$Int(xs) {}
// 5. Somehow figure out the caching
// 6. Delete references to Specialized I guess

// Synthesize Specialized[T] instances.

// template [T#4477 >: scala#22.this.Nothing#1468 <: Any#462](using 
//   evidence$1#4478: <root>#2.this.scala#21.Specialized#338[T#4477],
//   evidence$2#4479: scala#22.this.package#123.Numeric#5766[T#4477]) extends 
//   Object#744 {
//   T#4473
//   private[this] given val evidence$1#4474:
//     <root>#2.this.scala#21.Specialized#338[T#4473]
//   private[this] given val evidence$2#4475: Numeric#5821[T#4473]
// }
// [[syntax tree


// template Template(DefDef(<init>,List(List(TypeDef(T,TypeBoundsTree(TypeTree[TypeRef(ThisType(TypeRef(NoPrefix,module class scala)),class Nothing)],TypeTree[TypeRef(ThisType(TypeRef(NoPrefix,module class scala)),class Any)],EmptyTree)))
// , List(ValDef(evidence$1,AppliedTypeTree(Ident(Specialized),List(Ident(T))),EmptyTree), 
//   ValDef(evidence$2,AppliedTypeTree(Ident(Numeric),List(Ident(T))),EmptyTree))),TypeTree[TypeRef(ThisType(TypeRef(NoPrefix,module class scala)),class Unit)],EmptyTree),List(TypeTree[TypeRef(ThisType(TypeRef(NoPrefix,module class lang)),class Object)]),ValDef(_,EmptyTree,EmptyTree),List(TypeDef(T,TypeTree[TypeBounds(TypeRef(ThisType(TypeRef(NoPrefix,module class scala)),class Nothing),TypeRef(ThisType(TypeRef(NoPrefix,module class scala)),class Any))]), ValDef(evidence$1,TypeTree[AppliedType(TypeRef(TermRef(ThisType(TypeRef(NoPrefix,module class <root>)),object scala),trait Specialized),List(TypeRef(ThisType(TypeRef(ThisType(TypeRef(NoPrefix,module class <empty>)),trait Vec)),type T)))],EmptyTree), ValDef(evidence$2,TypeTree[AppliedType(TypeRef(TermRef(TermRef(ThisType(TypeRef(NoPrefix,module class <root>)),object scala),object math),trait Numeric),List(TypeRef(ThisType(TypeRef(ThisType(TypeRef(NoPrefix,module class <empty>)),trait Vec)),type T)))],EmptyTree)))

// defdef DefDef(foo,List(List(ValDef(v,AppliedTypeTree(Ident(Vec),List(Ident(Int))),EmptyTree))),TypeTree[AppliedType(TypeRef(ThisType(TypeRef(NoPrefix,module class <empty>)),trait Vec),List(TypeRef(TermRef(ThisType(TypeRef(NoPrefix,module class <root>)),object scala),class Int)))],Ident(v))


// class DesugarSpecializedTraits extends  MacroTransform, SymTransformer:
//   import tpd._

//   override def phaseName: String = DesugarSpecializedTraits.name
//   override def description: String = DesugarSpecializedTraits.description
//   override def changesMembers: Boolean = false
//   override def changesParents: Boolean = true 

