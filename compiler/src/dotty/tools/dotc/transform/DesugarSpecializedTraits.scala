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
      val att = AppliedTypeTree(Ident(specialization.traitSymbol.typeRef), specialization.specialization)
      val v = att.symbol
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

    private def buildClassTree(specialization: Specialization, generatedTraitSymbol: ClassSymbol)(using Context) = {
      val originalTraitSymbol = specialization.traitSymbol
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

      assert(originalTraitSymbol.primaryConstructor.rawParamss.length >= 2) // we know we at least have type params and evidences
      val evidences = (originalTraitSymbol.primaryConstructor.rawParamss: @unchecked) match { 
        case List(_, _, evidences) => evidences
        case List(_, evidences) => evidences
      }

      // TODO: Confirm that we don't need to worry about copying the evidence parameters over from the old constructor
      // These should be dealt with when we instantiate the original trait as a parent of this one. Otherwise we should be
      // able to copy them over, apply the specialization (keeping e.g. Numeric[Int] that arises from this) and 
      // pruning any that belong to Specialized.

      // TODO: Tidy this up and also figure out why we don't do this by default in the ClassDef? 
      val tm = new TypeMap: // TODO: Can we get this into the specialization ideally.
        def apply(t: Type) = specialization.specializedTypeParamsToTypeArgumentsMap.view.mapValues(_.tpe).applyOrElse(t, mapOver) // TODO: IF we can do just types we can get rid fo this 


    // specialization.traitSymbol.info.
      // HACK: This doesn't work for if A is inline for example because in general it misses the type for A
    // specialization.traitSymbol.denot.info.paren


      // val customParentTrees = 
      //   AppliedTypeTree(Ident(specialization.traitSymbol.typeRef), specialization.specialization) // original trait, specialized
      //   :: specialization.traitSymbol.denot.info.parents.filterNot(_ eq defn.ObjectType).map(a => TypeTree(tm(a)))          // parents of the original trait, specialized
      
      ClassDef(generatedTraitSymbol, DefDef(init.entered), Nil)
    } 

    override def transform(tree: Tree)(using Context): Tree = tree match {
      case pkg@PackageDef(pid, stats) => // TODO: If we do everything ourselves and match only on the package then we can get rid of the MacroTransform aspect and just have a Phase with the transformPackageDef method.
        val specializedSymbols = generateSpecializedTraitSymbols(pkg)
        val generatedTraitStats = specializedSymbols.getSpecializations.map(buildClassTree)
        
        // Use the TreeTypeMap to replace instances (can we do this without accidentally replacing the definitions? I think it should be ok)
        val typeMap = new TypeMap:
          def apply(t: Type) = t match {
            case Specialization(spec) => 
              {
                for (specializedSymbol <- specializedSymbols.get(spec))
                yield AppliedType(specializedSymbol.typeRef, spec.unspecializedTypeArgs.map(_.tpe)) 
              }.getOrElse(mapOver(t))
            case _ => mapOver(t)
          }
      
        def treeMap(tree: Tree): Tree = tree match {
          case Apply(TypeApply(fun@Select(New(tpt), _init), args), ev) if fun.symbol.isConstructor => 
            val spec = Specialization(fun.symbol.owner, args)
              // Note: We always drop the evidence params when creating these new specialized traits so we know that there are none, but we may need to revisit this if we decide we do want to copy the evidence parameters over
            TypeApply(Select(New(treeMap(tpt)), _init), spec.unspecializedTypeArgs)
          case Specialization(spec) => {
            for (specializedSymbol <- specializedSymbols.get(spec))
            yield AppliedTypeTree(Ident(specializedSymbol.typeRef), spec.unspecializedTypeArgs) // TODO: Matching on a Specialization and then outputting ATT is weird - maybe have a method on specialization to convert to ATT
          }.getOrElse(tree)

          case tree => tree
        }
        
        val treeTypeMap = new TreeTypeMap(typeMap, treeMap) {
          override def transform(tree: Tree)(using Context): Tree = tree match { // HACK: This seems to do what we want but I don't understand why we don't do this by default? Surely we should apply transformDefs over template body?
            case dd@DefDef(name, paramss, tpt, preRhs) => 
              val transformedDef = super.transform(dd)
              transformedDef.symbol.info = mapType(transformedDef.symbol.info)
              transformedDef
            case tree => super.transform(tree)
          }
        }
        cpy.PackageDef(pkg)(pid, generatedTraitStats ++ stats.map(treeTypeMap(_))) // TODO: Do we also want to apply the map over generatedTraitStats?? 
    }

    private def generateSpecializedTraitSymbols(tree: Tree)(using Context): SpecializedTraitCache = 
      tree.deepFold(SpecializedTraitCache())((foundSpecs, tree) => tree match
        // case New(something) => // if tycon.denot.symbol => // Is this fold going to be a problem? Or juist a good thing? Because we hit the child first
        //   // AppliedTypeTree(tycon: Ident, concreteTypeTrees: List[Tree])
        //   // I guess ideally do this after already processing it down to the ArrayIterator$sp$Int then we just replace that with ArraytIterator
        //   println(s"Found something ${something}")
        //   foundSpecs
        // case New(AppliedTypeTree etc) -> need to output the impl class -> do we wantto generate that when we see Foo[Int] or not?
        
        // TODO: In theory since we are going to apply the tree type map anyway we can surely just collect up the specialisations we need and then later generate the new symbols?
        // I think that's slightly cleaner.
        case Specialization(spec) if (spec.hasSpecializedParams && !foundSpecs.contains(spec)) =>
          val newSpecializedTraitInterfaceTraitSymbol = newSpecializedTraitInterfaceTrait(spec)
          foundSpecs.add(spec, newSpecializedTraitInterfaceTraitSymbol)
        case _ => foundSpecs
      )
  }

object DesugarSpecializedTraits:
  val name: String = "desugarSpecializedTraits"
  val description: String = "Replaces traits having type parameters that have the Specialized annotation with specialized versions"

  // TODO: What happens with this name generation if we have Vec[Vec[T]] for example? We potentially don't have an Ident
  // TODO: Check what happens here when we have a case where the types being specialized into are user defined instead of primitives or type vars.
  private[transform] def newSpecializedTraitName(specialization: Specialization)(using Context) = 
    specialization.specializedTypeArgs.collect(t => t match {
      case Ident(tpe) => tpe ++ str.SPECIALIZED_TRAIT_TYPE_SEP
    }).fold(specialization.traitSymbol.name ++ str.SPECIALIZED_TRAIT_SUFFIX)((n1, n2) => n1 ++ n2)


// TODO: Potentially we can just replace this with a map?
class SpecializedTraitCache:
  private val specializationMap: mutable.Map[Specialization, ClassSymbol] = mutable.Map.empty 

  def contains(specialization: Specialization)(using Context) =
    specializationMap.contains(specialization)

  def add(specialization: Specialization, specializedSymbol: ClassSymbol)(using Context): SpecializedTraitCache = {
    specializationMap(specialization) = specializedSymbol
    this
  }

  def get(specialization: Specialization)(using Context) = specializationMap.get(specialization)

  def getSpecializations: List[(Specialization, ClassSymbol)] = specializationMap.toList

end SpecializedTraitCache


/* Represents an application traitSymbol[typeArguments] */
class Specialization(val traitSymbol: Symbol, val typeArguments: List[Tree])(using Context): // TODO: Can we get away with List[Type]
  object SpecializedEvidence {
    def unapply(tpe: Type)(using Context): Option[Type] = tpe match {
      case AppliedType(tycon, List(tpeArg)) if tycon =:= ctx.definitions.SpecializedBoundRef => Some(tpeArg)
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
  
  def hasSpecializedParams: Boolean = specializedTypeParams.nonEmpty

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


// need to test with explicit evidence / our own custom type classes
 