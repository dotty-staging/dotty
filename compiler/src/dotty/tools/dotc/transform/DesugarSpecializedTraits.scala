package dotty.tools.dotc.transform

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.transform.MegaPhase.MiniPhase
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.i
import dotty.tools.dotc.core.Decorators.className
import dotty.tools.dotc.core.Symbols.{Symbol, ClassSymbol, newNormalizedClassSymbol}
import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.core.StdNames.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Symbols.newClassSymbol
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
import dotty.tools.dotc.core.Names.Name
import tpd._
import scala.collection.mutable
import scala.annotation.unspecialized
import dotty.tools.dotc.typer.Synthesizer
import dotty.tools.dotc.typer.Typer


class DesugarSpecializedTraits extends MacroTransform:

  override def phaseName: String = DesugarSpecializedTraits.name
  override def description: String = DesugarSpecializedTraits.description
  override def changesMembers: Boolean = false
  override def changesParents: Boolean = true 

  override def run(using Context): Unit =
    try super.run
    catch case _: CompilationUnit.SuspendException => ()

  
  override def newTransformer(using Context): Transformer = new Transformer {

    private def newSpecializedTraitInterfaceTrait(specialization: Specialization) =
      val tm = new TypeMap: // TODO: Can we get this into the specialization ideally.
        def apply(t: Type) = specialization.specializedTypeParamsToTypeArgumentsMap.view.mapValues(_.tpe).applyOrElse(t, mapOver) // TODO: IF we can do just types we can get rid fo this 
    
      // Create new trait
      val parents = defn.ObjectType
                    :: AppliedTypeTree(Ident(specialization.traitSymbol.typeRef), specialization.specialization).tpe // original trait, specialized
                    :: specialization.traitSymbol.denot.info.parents.filterNot(_ eq defn.ObjectType).map(tm(_))          // parents of the original trait, specialized
      
      val traitSymbol = newNormalizedClassSymbol(
        specialization.traitSymbol.owner,
        (DesugarSpecializedTraits.newSpecializedTraitName(specialization)).asTypeName,
        Flags.Synthetic | Flags.Trait,
        parents,
        NoType, // TODO: What happens if the creator of the specialized inline trait provides a self type? 
        specialization.traitSymbol.privateWithin,
        // TODO: Do we need a compUnit info?
      )

      // Create type parameters for new trait
      val tps = newTypeParams(traitSymbol,
                    specialization.unspecializedTypeParams.map(_.typeSymbol.name.asTypeName),
                    EmptyFlags,
                    targets => targets.map(t => specialization.traitSymbol.typeParams.find(_.name == t.name).get.info.bounds)
                )
      tps.foreach(traitSymbol.enter(_, EmptyScope))


      // Replace old type parameters that were copied from original trait with new ones
      // inside the parents of the new trait 
      val tpMap: Map[Type, Type] = specialization.unspecializedTypeParams.zip(tps.map(_.typeRef)).toMap
      val freshTypeVarMap = new TypeMap:
        def apply(t: Type) = tpMap.applyOrElse(t, mapOver)
      traitSymbol.info = ClassInfo(traitSymbol.owner.thisType, traitSymbol, traitSymbol.info.parents.map(freshTypeVarMap(_)), traitSymbol.info.decls) // TODO: What happens if the creator of the specialized inline trait provides a self type?
      traitSymbol.entered

    private def buildSpTraitTree(specialization: Specialization, generatedTraitSymbol: ClassSymbol)(using Context) = {
      val init = newDefaultConstructor(generatedTraitSymbol)
      
      // Fix constructor so that it:
      //    1) Has correct generic type parameters
      //    2) Returns the correct type corresponding to those type parameters applied to this trait
      val rt = generatedTraitSymbol.typeRef.appliedTo(generatedTraitSymbol.typeParams.map(_.typeRef))
      def resultType(tpe: Type): Type = tpe match {
          case mt @ MethodType(paramNames) => mt.derivedLambdaType(paramNames, mt.paramInfos, rt)
          case pt : PolyType => pt.derivedLambdaType(pt.paramNames, pt.paramInfos, resultType(pt.resType))
      }
      init.info = resultType(init.info)
      init.info = PolyType.fromParams(init.owner.typeParams, init.info)

      // TODO: Confirm that we don't need to worry about copying the evidence parameters over from the old constructor
      // These should be dealt with when we instantiate the original trait as a parent of this one. Otherwise we should be
      // able to copy them over, apply the specialization (keeping e.g. Numeric[Int] that arises from this) and 
      // pruning any that belong to Specialized.

      ClassDef(generatedTraitSymbol, DefDef(init.entered), Nil)
    }

    // TODO: Do we want to share some code with the newSpecializedInterfaceTrait and buildSpTraitTree?
    // TODO: Standardise a bit so that we either generate the symbols and later the classes or not.
    private def buildImplClassTree(specialization: Specialization, generatedTraitSymbol: ClassSymbol)(using Context) = {
      // Create new class
      val parents = List(defn.ObjectType,
                         generatedTraitSymbol.typeRef, // new specialized trait
                         AppliedTypeTree(Ident(specialization.traitSymbol.typeRef), specialization.specialization).tpe) // original trait, specialized

      val classSymbol = newNormalizedClassSymbol(
        specialization.traitSymbol.owner,
        (DesugarSpecializedTraits.newImplementationClassName(specialization)).asTypeName,
        Flags.Synthetic,
        parents,
        NoType, // TODO: What happens if the creator of the specialized inline trait provides a self type? 
        specialization.traitSymbol.privateWithin,
        // TODO: Do we need a compUnit info?
      )

      val t = specialization.traitSymbol.primaryConstructor.localReturnType

      val init = newDefaultConstructor(classSymbol)
      init.setParamss(
        List(specialization.traitSymbol.primaryConstructor.rawParamss(1).map(param => param.copy(info = specialization.constructorParamToArgumentTypeMap(param.info)))) // only the value params
      )
      init.info = MethodType(specialization.traitSymbol.primaryConstructor.rawParamss(1).map(_.name.asTermName),
                             specialization.specialization.map(_.tpe),
                             classSymbol.typeRef) 
        
      
      // Fix constructor so that it:
      //    1) Has correct generic type parameters
      //    2) Returns the correct type corresponding to those type parameters applied to this trait
      // val rt = generatedTraitSymbol.typeRef.appliedTo(generatedTraitSymbol.typeParams.map(_.typeRef))
      // def resultType(tpe: Type): Type = tpe match {
      //     case mt @ MethodType(paramNames) => mt.derivedLambdaType(paramNames, mt.paramInfos, rt)
      //     // case pt : PolyType => pt.derivedLambdaType(pt.paramNames, pt.paramInfos, resultType(pt.resType))
      // }
      // val z = init.info
      // init.info = resultType(init.info)
      // init.info = PolyType.fromParams(init.owner.typeParams, init.info)

      // TODO: Confirm that we don't need to worry about copying the evidence parameters over from the old constructor
      // These should be dealt with when we instantiate the original trait as a parent of this one. Otherwise we should be
      // able to copy them over, apply the specialization (keeping e.g. Numeric[Int] that arises from this) and 
      // pruning any that belong to Specialized.


      // TODO: Clean adn robust
      
      val field = specialization.traitSymbol.findMember(init.paramSymss.head.head.name, specialization.traitSymbol.typeRef, Flags.EmptyFlags, Flags.EmptyFlags).symbol.copy(
        owner = classSymbol,
        info = init.paramSymss.head.head.info
      )

      // TODO: probably just copy the whole class symbol to get all the params

      val v = init.paramSymss.head(0).info


      val classDef = ClassDefWithParents(
        classSymbol,
        DefDef(init.asTerm.entered), 
        List(
          New(classSymbol.info.parents.head, classSymbol.info.parents.head.classSymbol.primaryConstructor.asTerm, Nil),
          New(classSymbol.info.parents(1), classSymbol.info.parents(1).classSymbol.primaryConstructor.asTerm, Nil),
          New(parents(2), classSymbol.info.parents(2).classSymbol.primaryConstructor.asTerm, // TODO: Check for other constructors
            
            List(ref(field))
            // init.paramSymss.head.map(ref(_))
            
            ).appliedTo(
              TypeApply(ref(defn.SpecializedModule_apply), List(TypeTree(init.paramSymss.head(0).info)))
            )),
        Nil)
        // println("HALLO MATE")
        // println(classDef)
      (classDef, classSymbol)
    }

    override def transform(tree: Tree)(using Context): Tree = tree match {
      case pkg@PackageDef(pid, stats) => // TODO: If we do everything ourselves and match only on the package then we can get rid of the MacroTransform aspect and just have a Phase with the transformPackageDef method.
        println(pkg)
        val specializedSymbols = generateSpecializedTraitSymbols(pkg)
        val generatedTraitStats = specializedSymbols.getSpecializations.map(buildSpTraitTree)
        
        /// TODO : Make this proper
        val (generatedClassStats, classSymbols) = specializedSymbols.getSpecializationsForImplementation.map(buildImplClassTree).unzip
        val classSymbolMap = specializedSymbols.getSpecializations.map(_._1).zip(classSymbols).toMap


        // Use the TreeTypeMap to replace instances (can we do this without accidentally replacing the definitions? I think it should be ok)
        val typeMap = new TypeMap:
          def apply(t: Type) = {
            // println(t)
            t match {
            case Specialization(spec) => 
              {
                for (specializedSymbol <- specializedSymbols.get(spec))
                yield 
                  spec.toTypeWithNewSymbol(specializedSymbol)
              }.getOrElse(mapOver(t))
            case _ => mapOver(t)
          }
          }
      
        def treeMap(tree: Tree): Tree = tree match {
          // To some extent would need to match this anyway - could we do something to the parent?
          // Say this is fine for now/
          case Block(List(TypeDef(anon, Template(_, parentCalls: List[Tree], _, _))),  
                     Typed(Apply(Select(New(anon1),ctor), _), t: TypeTree)) if anon1.symbol.isAnonymousClass =>
            parentCalls(1) match { // only allowed to extend Object and our specialized trait
              case Apply(Apply(tpe, ctorArgs), _ev) => 
                val spec = Specialization.unapply(t.tpe).get
                val x = classSymbolMap
                val y = specializedSymbols.getSpecializations
                Typed(Apply(Select(New(ref(classSymbolMap(spec))),ctor), ctorArgs), t)
              case _ => tree
            }

          case Apply(TypeApply(fun@Select(New(tpt), _init), args), ev) if fun.symbol.isConstructor => 
            val spec = Specialization(fun.symbol.owner, args)
              // Note: We always drop the evidence params when creating these new specialized traits so we know that there are none, but we may need to revisit this if we decide we do want to copy the evidence parameters over
            TypeApply(Select(New(treeMap(tpt)), _init), spec.unspecializedTypeArgs)
          case Specialization(spec) => {
            for (specializedSymbol <- specializedSymbols.get(spec))
            yield AppliedTypeTree(Ident(specializedSymbol.typeRef), spec.unspecializedTypeArgs) // TODO: Matching on a Specialization and then outputting ATT is weird - maybe have a method on specialization to convert to ATT .toAppliedTypeTree?
          }.getOrElse(tree)

          case tree => tree
        }
        
        val treeTypeMap = new TreeTypeMap(typeMap, treeMap) {
          override def transform(tree: Tree)(using Context): Tree = tree match { // HACK: This seems to do what we want but I don't understand why we don't do this by default? Surely we should apply transformDefs over template body?
            case dd@DefDef(name, paramss, tpt, preRhs) => 
              // println(dd)
              val transformedDef = super.transform(dd)
              transformedDef.symbol.info = mapType(transformedDef.symbol.info)
              transformedDef
            case tree => super.transform(tree)
          }
        }
        cpy.PackageDef(pkg)(pid, generatedTraitStats ++ generatedClassStats ++ stats.map(treeTypeMap(_))) // TODO: Do we also want to apply the map over generatedTraitStats?? 
    }

    // TODO: Try with just generating new Foo(100) with no function to pass it to and no other references to Foo. this may not work because we might not
    // correctly detect it. 

    // TODO : Is it not better to just delete the Specialized?

    private def generateSpecializedTraitSymbols(tree: Tree)(using Context): SpecializedTraitCache = 
      tree.deepFold(SpecializedTraitCache())((foundSpecs, tree) => tree match
        // case Typed(Apply(Select(New(anon),ctor),List()), t: TypeTree) =>
        //   val z = anon.symbol
        //   val f = anon.symbol.isAnonymousClass
          // foundSpecs
        case Typed(Apply(Select(New(anon),ctor),List()), t: TypeTree) if anon.symbol.isAnonymousClass =>
          val maybeSpec = Specialization.unapply(t.tpe)

          maybeSpec.foreach( spec =>
            if (spec.hasSpecializedParams && !foundSpecs.contains(spec)) {
              foundSpecs.add(spec, newSpecializedTraitInterfaceTrait(spec))
            }
            foundSpecs.flagForImplementation(spec) // TODO: Need to think carefully about the behaviour when we are integrating libraries - should the library generate the implementation classes or the user?
            // In any case we need to read back in either the $sp$ classes or the $impl$ traits to be able to work with them.
          )
          foundSpecs

        // Is this fold going to be a problem? Or juist a good thing? Because we hit the child first
        //   // I guess ideally do this after already processing it down to the ArrayIterator$sp$Int then we just replace that with ArraytIterator
        
        // TODO: In theory since we are going to apply the tree type map anyway we can surely just collect up the specialisations we need and then later generate the new symbols?
        // I think that's slightly cleaner.
        case Specialization(spec) if (spec.hasSpecializedParams && !foundSpecs.contains(spec)) =>
          foundSpecs.add(spec, newSpecializedTraitInterfaceTrait(spec))
        case _ => foundSpecs
      )
  }

object DesugarSpecializedTraits:
  val name: String = "desugarSpecializedTraits"
  val description: String = "Replaces traits having type parameters that have the Specialized annotation with specialized versions"

  // TODO: What happens with this name generation if we have Vec[Vec[T]] for example? We potentially don't have an Ident
  // TODO: Check what happens here when we have a case where the types being specialized into are user defined instead of primitives or type vars.
  private def generateName(specialization: Specialization, suffix: String)(using Context) = 
    specialization.specializedTypeArgs.collect(t => t match {
      case Ident(tpe) => tpe ++ str.SPECIALIZED_TRAIT_TYPE_SEP
    }).fold(specialization.traitSymbol.name ++ suffix)((n1, n2) => n1 ++ n2)

  private[transform] def newSpecializedTraitName(specialization: Specialization)(using Context) = 
    generateName(specialization, str.SPECIALIZED_TRAIT_SUFFIX)

  private[transform] def newImplementationClassName(specialization: Specialization)(using Context) = 
    generateName(specialization, str.SPECIALIZED_TRAIT_IMPL_SUFFIX)


class SpecializedTraitCache:
  private val specializationMap: mutable.Map[Specialization, ClassSymbol] = mutable.Map.empty 
  private val flaggedForImplementation: mutable.Set[Specialization] = mutable.Set.empty

  def contains(specialization: Specialization)(using Context) =
    specializationMap.contains(specialization)

  def add(specialization: Specialization, specializedSymbol: ClassSymbol)(using Context): SpecializedTraitCache = {
    specializationMap(specialization) = specializedSymbol
    this
  }

  def get(specialization: Specialization)(using Context) = specializationMap.get(specialization)

  def getSpecializations: List[(Specialization, ClassSymbol)] = specializationMap.toList

  def flagForImplementation(spec: Specialization) = flaggedForImplementation.add(spec)

  def getSpecializationsForImplementation = flaggedForImplementation.toList.map(spec => (spec, specializationMap(spec)))

end SpecializedTraitCache


/* Represents an application traitSymbol[typeArguments] */
class Specialization(val traitSymbol: Symbol, val typeArguments: List[Tree])(using Context): // TODO: Can we get away with List[Type]
  object SpecializedEvidence {
    def unapply(tpe: Type)(using Context): Option[Type] = tpe match {
      case AppliedType(tycon, List(tpeArg)) if tycon =:= ctx.definitions.SpecializedClass.typeRef => Some(tpeArg)
      case _ => None
    }
  }

  val specializedTypeParams: List[Type] = traitSymbol.unforcedDecls.implicitDecls.collect(_.info match { case SpecializedEvidence(typeVar) => typeVar })
  
  private val specializedTypeParamsSet = specializedTypeParams.toSet
  private val paramToArgList = traitSymbol.typeParams.map(_.typeRef.asInstanceOf[Type]).zip(typeArguments)

  val unspecializedTypeParams: List[Type] = paramToArgList.filterNot((tParam, tArg) => specializedTypeParamsSet(tParam)).map(_._1)
  val specializedTypeArgs: List[Tree] = paramToArgList.filter((tParam, tArg) => specializedTypeParamsSet(tParam)).map(_._2)
  val unspecializedTypeArgs: List[Tree] = paramToArgList.filterNot((tParam, tArg) => specializedTypeParamsSet(tParam)).map(_._2)

  val specializedTypeParamsToTypeArgumentsMap: Map[Type, Tree] = paramToArgList.toMap.filter((k, v) => specializedTypeParamsSet(k))
  val specialization: List[Tree] = traitSymbol.typeParams.map(_.typeRef).map(specializedTypeParamsToTypeArgumentsMap.applyOrElse(_, TypeTree(_))) // TODO: Don't really like this name
  // val constructorParamToArgumentTypeMap: Map[Type, Type] = traitSymbol.primaryConstructor.typeParams.zip(paramToArgList).filter((constrParam, paramArg) => specializedTypeParamsSet(paramArg._1)).map((constrParam, paramArg) => (constrParam.typeRef, paramArg._1)).toMap

  // TODO: Potentially can get this out of the specialization.specialization directly given we make the same assumption about one primary constructor and param ordering. 
  def constructorParamToArgumentTypeMap: Map[Type, Type] = 
    traitSymbol.primaryConstructor.rawParamss.head.map(_.typeRef).zip(typeArguments.map(_.tpe)).toMap

  def hasSpecializedParams: Boolean = specializedTypeParams.nonEmpty

  def toTypeWithNewSymbol(newSymbol: Symbol) = unspecializedTypeArgs match {
    case head :: next => AppliedType(newSymbol.typeRef, unspecializedTypeArgs.map(_.tpe))
    case Nil => newSymbol.typeRef
  }
                   

  // Note: We only care about the specialized arguments for equality; a specialization of Vec[A: Specialized, B] with B = Int and one
  // with B = String can be considered to be the same as they use the same specialized trait
  // TODO: I don't really like this logic being in Specialization because they are really different
  // We should really put that logic in the SpecializedTraitCache because it's at that point that we treat them as the same.
  override def equals(obj: Any): Boolean = 
    obj.isInstanceOf[Specialization] && obj.asInstanceOf[Specialization].traitSymbol == traitSymbol
    && specializedTypeArgs.zip(obj.asInstanceOf[Specialization].specializedTypeArgs).forall((a1, a2) => a1.tpe =:= a2.tpe)

  override def hashCode(): Int = (traitSymbol, specializedTypeArgs.map(_.tpe)).hashCode()

object Specialization:
  def unapply(tpt: Tree)(using Context) = tpt match {
    case AppliedTypeTree(specializedTrait: Ident, concreteTypeTrees: List[Tree]) => Some(Specialization(specializedTrait.denot.symbol, concreteTypeTrees))
    case _ => None
  }
  
  def unapply(tpe: Type)(using Context) = tpe match {
    case AppliedType(tycon: Type, args: List[Type]) => Some(Specialization(tycon.typeSymbol, args.map(TypeTree(_))))
    case _ => None
  }


// Would be nice to define a Specialization class I think
//   -> Map the specialized type params to Int etc
//   -> Map the non-specialized type params to new type params
//   -> Be a canonical representation so we can store that in a set
//   -> Generate a name / string representation for use in new traits
//   -> Get the specialized list to apply


// TODO: Fix name generation which doesn't work if the tpye isn't provided explicitly




// Generate impl instead of generating anonymous classes every time to avoid insane code bloat
  // Do we really want the method definitions to live in the implementation classes or in the trait?|
  // I think in the trait is fine but note that this only actually saves any space if we don't use anonymous classes (because those copy parent members automatically it seems)
// Need to make sure all my examples are up to date, consistent with what we do and what we want to do so that they are actually useful for the future.
// Need to somehow make my naming a lot more consistent as well.
// Correctly generate names
// generate classes as well
// do we actually want to generate Iteratorsp$Int
// should we be worried about the results that we generate causing more stuff to be generated?
// figure out why we generate the T version.
// Try to see if we can do with only types and not trees
// Synthesise Specialized instances so that people can't do stupid stuff like Specialized[Array[T]]. type x = Specialized[Array[Array[Int]]]
// Set the Synthetic flags somewhere
// Cache / only generate once instead of multiple times.
// Ideally standardise on either specialization or specializationMap

// TODO: Think carefully about use of primaryConstructor and the other appropriateConstructors call or whatever it was.

// Probably (tree)typemap

// Would it be better to just copy rather than creating everything from scratch? I think this is right

// 1. Figure out which specialisations we need to generate
// 2. Generate ArrayIterator$sp$Int and ArrayIterator$impl$Int wherever they live
// 3. Replace ArrayIterator[Int] with ArrayIterator$sp$Int
// 4. Replace new ArrayIterator[Int](xs) {} with new ArrayIterator$impl$Int(xs) {}
// 5. Somehow figure out the caching
// 6. Delete references to Specialized I guess

// Synthesize Specialized[T] instances.
// TODO: Need to try with a bigger project with multiple packages later on to see if we get the behaviour that we are expecting to get in terms of the classes that we generate.

// Need to ban all of these but we will do that earlier I guess?
// Vec[Vec[Int]] hehe <- fine
// Vec[S, S[T]: Specialized] <- banned
// Vec[S, T[T]: Specialized] <- banned
// Vec[Array[T]: Specialized] <- banned


// TODO: Prune the generated anonymous classes.


// need to test with explicit evidence / our own custom type classes
// TODO: Make sure name encoding is fully qualified - e.g. potential for conflicts if we define our own class Int.
  // // TODO: check that we have a single type var only
