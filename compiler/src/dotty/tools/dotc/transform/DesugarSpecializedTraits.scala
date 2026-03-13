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

    object SpecializedEvidence {
      def unapply(tpe: Type)(using Context): Option[Type] = tpe match {
        case AppliedType(tycon, List(tpeArg)) if tycon =:= ctx.definitions.SpecializedBoundRef => Some(tpeArg)
        case _ => None
      }
    }

    private def newSpecializedTraitInterfaceTrait(specializedTrait: Ident, specializationMap: Map[Type, Tree]) =
      // Define specialization we want to apply
      val specializedTraitSymbol = specializedTrait.denot.symbol
      val tm = new TypeMap:
        def apply(t: Type) = specializationMap.view.mapValues(_.tpe).applyOrElse(t, mapOver)
      val specialization = specializedTraitSymbol.typeParams.map(_.typeRef).map(specializationMap.applyOrElse(_, TypeTree(_)))
      
      // Create new trait
      val parents = defn.ObjectType
                    :: AppliedTypeTree(cpy.Ident(specializedTrait)(specializedTrait.name), specialization).tpe // original trait; specialized
                    :: specializedTrait.denot.info.parents.filterNot(_ eq defn.ObjectType).map(tm(_))          // parents of the original trait, specialized
      val traitSymbol = newNormalizedClassSymbol(
        specializedTraitSymbol.owner,
        (newSpecializedTraitName(specializedTraitSymbol.name, specializationMap)).asTypeName,
        Flags.Synthetic | Flags.Inline | Flags.Trait,
        parents,
        NoType, // TODO: Work out what to do about self types; for now just ban them 
        specializedTraitSymbol.privateWithin,
        // TODO: Do we need a compUnit info?
      )

      // Create type parameters for new trait
      val old_type_params = specializedTraitSymbol.typeParams.filterNot(t => specializationMap.contains(t.typeRef))
      val tps = newTypeParams(traitSymbol,
                    old_type_params.map(_.name),
                    EmptyFlags,
                    targets => targets.map(t => specializedTraitSymbol.typeParams.find(_.name == t.name).get.info.bounds)
                )
      tps.foreach(traitSymbol.enter(_, EmptyScope))


      // Replace old type parameters that were copied from original trait with new ones
      // inside the parents of the new trait  
      val tpMap: Map[Type, Type] = old_type_params.map(_.typeRef).zip(tps.map(_.typeRef)).toMap
      val freshTypeVarMap = new TypeMap:
        def apply(t: Type) = tpMap.applyOrElse(t, mapOver)
      traitSymbol.info = ClassInfo(traitSymbol.owner.thisType, traitSymbol, traitSymbol.info.parents.map(freshTypeVarMap(_)), traitSymbol.info.decls, traitSymbol.info.self)
      traitSymbol.entered

    private def buildClassTree(originalTraitSymbol: Symbol, generatedTraitSymbol: ClassSymbol)(using Context) = {
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

      ClassDef(generatedTraitSymbol, DefDef(init.entered), Nil)
    } 

    override def transform(tree: Tree)(using Context): Tree = 
      tree match {
        case pkg@PackageDef(pid, stats) => // TODO: If we do everything ourselves and match only on the package then we can get rid of the MacroTransform aspect and just have a Phase with the transformPackageDef method.
          val stats1 = generateSpecializedTraitSymbols(pkg).map(buildClassTree)
          
          // Use the TreeTypeMap to replace instances (can we do this without accidentally replacing the definitions? I think it should be ok)
          // val treeTypeMap = new TreeTypeMap()

          cpy.PackageDef(pkg)(pid, stats1 ++ stats)
      }
    
    private def generateSpecializedTraitSymbols(tree: Tree)(using Context): List[(Symbol, ClassSymbol)] = 
      tree.deepFold(SpecializedTraitCache())((foundSpecializations, tree) => tree match
        // case New(AppliedTypeTree etc) -> need to output the impl class -> do we wantto generate that when we see Foo[Int] or not?

        case AppliedTypeTree(specializedTrait: Ident, concreteTypeTrees: List[Tree]) =>
          val specializedTraitSymbol = specializedTrait.denot.symbol
          val specializedTypeVars = specializedTraitSymbol.unforcedDecls.implicitDecls.collect(_.info match { case SpecializedEvidence(typeVar) => typeVar }).toSet
          val specializationMap = specializedTraitSymbol.typeParams.map(_.typeRef.asInstanceOf[Type]).zip(concreteTypeTrees).toMap.filter((k, v) => specializedTypeVars(k))

          if (specializationMap.nonEmpty && !foundSpecializations.existsSpecialization(specializedTraitSymbol, specializationMap)) {
            val newSpecializedTraitInterfaceTraitSymbol = newSpecializedTraitInterfaceTrait(specializedTrait, specializationMap)
            foundSpecializations.addSpecialization(specializedTraitSymbol, specializationMap, newSpecializedTraitInterfaceTraitSymbol)
          }
          else foundSpecializations
        case _ => foundSpecializations
      ).getSpecializations
  }

object DesugarSpecializedTraits:
  val name: String = "desugarSpecializedTraits"
  val description: String = "Replaces traits having type parameters that have the Specialized annotation with specialized versions"

  // TODO: What happens with this name generation if we have Vec[Vec[T]] for example? We potentially don't have an Ident
  // TODO: Check what happens here when we have a case where the types being specialized into are user defined instead of primitives or type vars.
  private[transform] def newSpecializedTraitName(name: Name, specialization: Map[Type, Tree]) = 
    specialization.values.collect(t => t match {
      case Ident(tpe) => tpe ++ str.SPECIALIZED_TRAIT_TYPE_SEP
    }).fold(name ++ str.SPECIALIZED_TRAIT_SUFFIX)((n1, n2) => n1 ++ n2)


class SpecializedTraitCache:
  private val specializationMap: mutable.Map[(Symbol, Name), ClassSymbol] = mutable.Map.empty 

  def existsSpecialization(traitSymbol: Symbol, specialization: Map[Type, Tree])(using Context) =
    specializationMap.contains((traitSymbol, newSpecializedTraitName(traitSymbol.name, specialization)))

  def addSpecialization(traitSymbol: Symbol, specialization: Map[Type, Tree], specializedSymbol: ClassSymbol)(using Context): SpecializedTraitCache = {
    specializationMap((traitSymbol, newSpecializedTraitName(traitSymbol.name, specialization))) = specializedSymbol
    this
  }

  def getSpecializations: List[(Symbol, ClassSymbol)] = specializationMap.toList.map((k, v) => (k._1, v))

end SpecializedTraitCache


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

// Potentially we can just go through and find every place which needs one, do a direct replacement and spit it out directly into some kind of list buffer and then 
// copy it out later

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
 