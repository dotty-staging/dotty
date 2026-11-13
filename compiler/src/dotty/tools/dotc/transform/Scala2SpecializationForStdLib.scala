package dotty.tools.dotc.transform

import dotty.tools.dotc.ast.{tpd, untpd}
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.{toTermName, toTypeName}
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.Phases.*
import dotty.tools.dotc.core.Scopes.newScope
import dotty.tools.dotc.core.StdNames.nme
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*

import scala.collection
import scala.collection.mutable

// TODO:
// - parents should be themselves specialized
// - make sure extending Function0/1/2 works

/**
 * Implements just enough Scala2-style specialization for the standard library and only the standard library
 *
 * THIS IS NOT TO BE USED ELSEWHERE. DO NOT TRY TO USE IT FOR YOUR OWN CODE. DO NOT ASK FOR IT TO BE SUPPORTED.
 */
object Scala2SpecializationForStdLib:
  /* The stdlib only has specialized types remaining, i.e., there are no `def`s with `@specialized` type params.
   * Per chapter 4 in Iulian Dragos's thesis (available at https://doi.org/10.5075/epfl-thesis-4820), type specialization means:
   * 1. Generate specialized versions of all methods whose signature contain a specialized type or an array of a specialized type.
   *    In the base class/trait, these delegate to the unspecialized method by boxing arguments and unboxing return values as needed.
   * 2. Generate a `def specInstance$: Boolean` method that returns `false` in the base class/trait.
   * 3. Wrap the primary constructor in `if !specInstance$ then ...`
   *    (This must come after the Constructors phase, which creates primary constructors)
   * 4. Generate specialized subclasses:
   *    a. handle 'final' classes:
   *      i. the original class must not be `final` when typed
   *      ii. the original class must be 'final' when pickled if it was declared as such
   *      iii. the original class must not be 'final' when emitted
   *    b. subclass methods work in the opposite way: the unspecialized versions delegates via boxing/unboxing to their specialized form
   *    c. subclass `specInstance$` methods return `true`
   *    d. subclass constructors are a specialized form of the super one (it calls the super one, which will have no effect due to the `specInstance$` check)
   * 5. Ensure that every creation of a class with specializations for which the type arguments match a specialization
   *    is replaced with a creation of the specialized form (e.g., for `C[@specialized(Int) T, U]`, `C[Int, String]` is replaced, but `C[String, Int]` isn't)
   * 6. Ensure that every method call on a class with specializations for which the type arguments of the class match a specialization
   *    are replaced with a call to the specialized method (e.g., for `def foo(n: T): Unit` on `C[@specialized(Int) T]`, any call to `foo` for which `T = Int` is replaced).
   *    (These last two must come after the typer, since we may have methods like `def x: T` that would be overloaded only on the return type,
   *     which isn't permitted, so we can't simply use `@targetName`)
   *
   * Because this is only for the standard library, steps 2, 3, 5 and 6 are not needed.
   * The compiler already has SpecializeFunctions and SpecializeTuples for programs that use the stdlib.
   */

  // Avoid polluting nme.*
  private object ourNames:
    val annotation: TermName = "annotation".toTermName
    val unspecialized: TypeName = "unspecialized".toTypeName
    val specialized: TypeName = "specialized".toTypeName
    val specializable: TermName = "Specializable".toTermName
    val specializationPrefix = "$mc"
    val specializationSuffix = "$sp"
//    val specInstance: TermName = "specInstance$".toTermName

  /** Name + specialized types -> specialized name */
  private def specializedName[T <: Name](name: T, specialization: Specialization)(using Context): name.ThisName =
    name ++ (ourNames.specializationPrefix + specialization.map(e => ctx.definitions.typeTag(e._2)).mkString("") + ourNames.specializationSuffix)

  private type Specialization = Map[Type, NamedType]
  private val emptySpecialization: Specialization = Map.empty
  //private type TypeNameToSpecializationsByIndex = collection.Map[TypeName, collection.Map[Int, collection.Set[TypeName]]]

  /** Generates specialized subclasses (steps 1, 2, 4.a.i, 4.b, 4.c, 4.d) */
  final class PrePickler extends Phase:

    private val _specializedTypes: mutable.Set[TypeName] = mutable.Set.empty
    def specializedTypes: collection.Set[TypeName] = _specializedTypes
//    private val _specializedTypes: mutable.Map[TypeName, collection.Map[Int, collection.Set[TypeName]]] = mutable.Map.empty
//    def specializedTypes: TypeNameToSpecializationsByIndex = _specializedTypes

//    private val _typesDeclaredFinal: mutable.Set[TypeName] = mutable.Set.empty
//    def typesDeclaredFinal: collection.Set[TypeName] = _typesDeclaredFinal

    override def phaseName: String =
      "scala2SpecializationForStdLib.PrePickler"

    override def isEnabled(using Context): Boolean =
      ctx.settings.YcompilingStdLib.value

    override def changesMembers: Boolean = true
    // TODO we create new classes but that should be okay? override def changesParents: Boolean = true

    //    override def isCheckable: Boolean =
//      false // we are before the typer

    override protected def run(using Context): Unit =
      ctx.compilationUnit.tpdTree match
        case p @ PackageDef(pid, stats) =>
          var changed = false
          val specializedStats = stats.flatMap {
            case td: TypeDef =>
              val specialized = specializeTypeDef(td)
              if specialized.length > 1 then changed = true
              specialized
            case other =>
              List(other)
          }
          if changed then
            ctx.compilationUnit.tpdTree = cpy.PackageDef(p)(pid = pid, stats = specializedStats)
        case _ => ()

    private def specializeTypeDef(td: TypeDef)(using Context): List[TypeDef] =
      // First, collect specialized params (from the body, where their annotations have been preserved, not from the template constr),
      val typeParams = td.rhs match
        case t: Template => t.body.collect { case td: TypeDef => td }
        case _ => Nil
      // and find which types the params are specialized for.
      val specializedTypeParams =
        typeParams.flatMap(tp =>
          val types = tp.mods.annotations.flatMap {
            case Apply(Select(New(Ident(ourNames.specialized)), nme.CONSTRUCTOR), args) => specializedTypes(args)
            case _ => Nil
          }
          if types.isEmpty then None
          else Some((tp.tpe, types))
        ).toMap
      // Most types aren't specialized, so let's not do extra work for nothing
      if specializedTypeParams.isEmpty then return List(td)
      // Then, transform that information to one [type param => specialized type] map per specialization
      // (e.g., [@specialized(Int, Double) X, @specialized(Short) Y, Z] => [[X => Int, Y => Short], [X => Double, Y => Short]]
      val specializations = specializedTypeParams.foldRight(List(emptySpecialization)) {
        case ((tp, types), acc) =>
          for
            typ <- types
            rest <- acc
          yield rest.updated(tp, typ)
      }
      _specializedTypes.add(td.name)
      /*_specializedTypes(td.name) = typeParams.zipWithIndex.flatMap((tp, n) => specializedTypeParams.find(_._1 == tp.name) match
        case Some((_, specs)) => Some((n, specs.toSet))
        case None => None
      ).toMap
      if td.mods.is(Flags.Final) then _typesDeclaredFinal.add(td.name)*/
      td.rhs match {
        case t: Template =>
          // Collect the members that need a specialized version, which will exist in both the supertype and the specialized subtypes, but with different bodies.
          val membersNeedingSpecialization = t.body.collect { case m: ValOrDefDef if needsSpecializing(m, specializations.head) => m }
          // Use the specialization maps to create the specialized methods in the supertype,
          val superTypeSpecializedMethods = membersNeedingSpecialization.flatMap {
            case dd: DefDef => specializations.map(sp => specializedForSuperType(dd, sp))
            case _ => Nil
          }
          // which is the only change we need to make to that type.
          val specializedSuperType = cpy.TypeDef(td)(rhs = cpy.Template(t)(body = t.body ++ superTypeSpecializedMethods))
          // Then use the specialization maps to create specialized subtypes,
          val superTypeSymbol = td.symbol
          val superTypeConstrSymbol = t.constr.symbol
          var typeArgs: List[Type] = Nil
          val specializedSubTypes = specializations.map(spec =>
            val subTypeDecls = newScope(ctx.owner.nestingLevel + 1)
            val subTypeName = specializedName(td.name, spec)
            val subTypeSymbol = newClassSymbol(
              superTypeSymbol.owner,
              subTypeName,
              superTypeSymbol.flags | Synthetic,
              cls => {
                typeArgs = typeParams.map(tp =>
                  spec.get(tp.tpe) match
                    case Some(t) =>
                      t
                    case None =>
                      val s = newSymbol(
                        cls,
                        tp.name,
                        TypeParam | Param,
                        TypeBounds.empty
                      )
                      subTypeDecls.enter(s)
                      s.typeRef
                )
                ClassInfo(
                  ctx.owner.thisType,
                  cls,
                  superTypeSymbol.typeRef.parents :+ superTypeSymbol.typeRef.appliedTo(typeArgs),
                  subTypeDecls
                )
              }
            ).entered
            // Create members with the opposite forwarding direction as the supertype,
            val members =
              // with the caveat that `trait`s get no new bodies (presumably because Scala 2 originally targeted JDKs < 8 without default interface methods?)
              if td.symbol.is(Trait) then Nil
              else
                // Transform the vals first so they can be used when specializing the def bodies
                val memberVals = membersNeedingSpecialization.collect { case vd: ValDef => vd }
                val specializedMemberVals = memberVals.map(vd => specializedForSubType(vd, subTypeSymbol, spec, td.name))
                val specializedMemberDefs = membersNeedingSpecialization.flatMap {
                  case dd: DefDef => specializedForSubType(dd, subTypeSymbol, spec, memberVals, td.name)
                  case _ => Nil
                }
                specializedMemberVals ++ specializedMemberDefs
            val constrInfo = specialize(superTypeConstrSymbol.info, spec, superTypeSymbol.typeRef, topLevelPolyType = true)
            val constrSymbol = newSymbol(subTypeSymbol, nme.CONSTRUCTOR, superTypeConstrSymbol.flags | Synthetic, constrInfo).entered
            val constrDef = DefDef(constrSymbol)
            val parent = New(superTypeSymbol.typeRef.appliedTo(typeArgs), constrSymbol.paramSymss.flatten.map(ref))
            ClassDefWithParents(subTypeSymbol, constrDef, t.parents :+ parent, members)
          )
          specializedSuperType :: specializedSubTypes
      }

    private def specializedTypes(args: List[untpd.Tree])(using Context): List[NamedType] = args match {
      case untpd.Select(untpd.Ident(ourNames.specializable), name) :: Nil =>
        name.toString match
          case "Primitives" =>  List(defn.ByteType, defn.ShortType, defn.IntType, defn.LongType, defn.CharType, defn.FloatType, defn.DoubleType, defn.BooleanType, defn.UnitType)
          case "Everything" =>  List(defn.ByteType, defn.ShortType, defn.IntType, defn.LongType, defn.CharType, defn.FloatType, defn.DoubleType, defn.BooleanType, defn.UnitType /* not actually used: defn.AnyRefType */)
          // unused: case "Bits32AndUp" => List(defn.IntType, defn.LongType, defn.FloatType, defn.DoubleType)
          // unused: case "Integral" =>    List(defn.ByteType, defn.ShortType, defn.IntType, defn.LongType, defn.CharType)
          // unused: case "AllNumeric" =>  List(defn.ByteType, defn.ShortType, defn.IntType, defn.LongType, defn.CharType, defn.FloatType, defn.DoubleType)
          // unused: case "BestOfBreed" => List(defn.IntType, defn.DoubleType, defn.BooleanType, defn.UnitType, defn.AnyRefType)
          // unused: case "Unit" =>        List(defn.UnitType)
          case "Arg" =>         List(defn.IntType, defn.LongType, defn.FloatType, defn.DoubleType)
          case "Args" =>        List(defn.IntType, defn.LongType, defn.DoubleType)
          case "Return" =>      List(defn.IntType, defn.LongType, defn.FloatType, defn.DoubleType, defn.BooleanType, defn.UnitType)
      case idents =>
        idents.map { case untpd.Ident(n) => n match
          case nme.Byte => defn.ByteType
          case nme.Short => defn.ShortType
          case nme.Int => defn.IntType
          case nme.Long => defn.LongType
          case nme.Char => defn.CharType
          case nme.Float => defn.FloatType
          case nme.Double => defn.DoubleType
          case nme.Boolean => defn.BooleanType
          case nme.Unit => defn.UnitType
        }
    }

    // not handling arrays here, not needed for the stdlib
    private def castTo(arg: Tree, specializedType: Type)(using Context): Tree =
      if specializedType == defn.UnitType then Block(List(arg), unitLiteral)
      else
        TypeApply(Select(arg, nme.asInstanceOf_), List(TypeTree(specializedType)))

    private def needsSpecializing(m: ValOrDefDef, specialization: Specialization)(using Context): Boolean =
      // unlike final classes which can be specialized, final members cannot
      // also we don't need any specialized `var`s for the stdlib so let's not do vars/setters
      // (which cause problems later due to specialization name mangling making them not look like setters)
      val mSym = m.symbol
      val optOut = mSym.isOneOf(Final | Mutable) || mSym.isSetter || m.mods.annotations.exists {
        case Apply(Select(New(Select(Ident(ourNames.annotation), ourNames.unspecialized)), nme.CONSTRUCTOR), Nil) => true
        case _ => false
      }
      !optOut && {
        val tp = mSym.info
        val specTp = specialize(tp, specialization, mSym.owner.typeRef)
        !(tp =:= specTp)
      }

    private def specialize(tp: Type, specialization: Specialization, typeBeingSpecialized: Type, topLevelPolyType: Boolean = false)(using Context): Type =
      val paramRefsSpecialization = mutable.Map.empty[TypeParamRef, Type]
      val map = new TypeMap:
        override def apply(tp: Type): Type =
          tp match
            case tr: TypeRef =>
              specialization.get(tr) match
                case Some(replacement) => replacement
                case None => mapOver(tp)
            case tpr: TypeParamRef =>
              paramRefsSpecialization.get(tpr) match
                case Some(replacement) => replacement
                case None => mapOver(tpr)
            case AppliedType(tycon: NamedType, args)
              if tycon == typeBeingSpecialized && args.collect{ case tpr: TypeParamRef if paramRefsSpecialization.contains(tpr) => tpr }.size == paramRefsSpecialization.size =>
              val specialized = tycon.typeSymbol.owner.info.decls.lookup(specializedName(tycon.name, specialization))
              // may not exist if we haven't created it yet
              if specialized.exists then specialized.typeRef else mapOver(tp)
            case _ =>
              mapOver(tp)
      tp match
        case poly: PolyType if topLevelPolyType =>
          def matchesByName(tp: Type, name: TypeName): Boolean = tp match
            case nt: NamedType => nt.name == name
            case _ => false
          val removedIndices = poly.paramRefs.zipWithIndex.flatMap((pr, idx) =>
              specialization.find((k, _) => matchesByName(k, pr.paramName)) match
                case Some((k, v)) =>
                  paramRefsSpecialization(pr) = v
                  Some(idx)
                case None =>
                  None
          )
          if removedIndices.length == poly.paramNames.length then
            map.apply(poly.resType)
          else
            PolyType(poly.paramNames.zipWithIndex.collect{ case (pn, idx) if !removedIndices.contains(idx) => pn })(
              _ => poly.paramInfos.zipWithIndex.collect{case (pi, idx) if !removedIndices.contains(idx) => RealTypeBounds(map.apply(pi.lo), map.apply(pi.hi)) },
              _ => map.apply(poly.resType)
            )
        case _ => map.apply(tp)

    private def specialize(tree: Tree,
                           specialization: Specialization,
                           specializedMembers: List[ValOrDefDef],
                           superTypeName: Name, subTypeSymbol: Symbol)(using Context): Tree =
      val map = new TreeMap:
        override def transform(tree: tpd.Tree)(using Context): tpd.Tree = tree match
          case s @ Select(This(Ident(t)), name) if t == superTypeName =>
            val replacementName =
              specializedMembers.collectFirst { case m if m.name == name => specializedName(name, specialization) } match
                case Some(n) => n
                case None => name
            val replacementMember = subTypeSymbol.typeRef.member(replacementName)
            if replacementMember.exists then ref(replacementMember.symbol)
            else super.transform(tree)
          case Ident(n) if tree.symbol.owner.name == superTypeName =>
            val replacementName = specializedName(n, specialization)
            val replacementMember = subTypeSymbol.typeRef.member(replacementName)
            if replacementMember.exists then ref(replacementMember.symbol)
            else super.transform(tree)
          case _ =>
            super.transform(tree)
      map.transform(tree)

    private def specializedForSuperType(dd: DefDef, specialization: Specialization)(using Context): DefDef =
      val originalSym = dd.symbol
      val name = specializedName(dd.name, specialization)
      val info = specialize(originalSym.info, specialization, originalSym.owner.typeRef)
      val sym = newSymbol(originalSym.owner, name, originalSym.flags | Synthetic, info).entered
      // Some of the casts may not be necessary, but they won't hurt either
      DefDef(sym, paramss =>
        var base = ref(originalSym)
        var remainingParamss = paramss
        if paramss.headOption.exists(_.headOption.exists(_.isInstanceOf[TypeTree])) then
          base = base.appliedToTypeTrees(paramss.head)
          remainingParamss = paramss.tail
        val call = remainingParamss.zip(originalSym.info.paramInfoss).foldLeft(base) {
          case (fn, (args, infos)) =>
            // box to the generic parameter which is the argument type of the callee
            fn.appliedToArgs(args.zip(infos).map((a, i) => castTo(a, i)))
        }
        // unbox to the specialized type which is the result type of the caller
        castTo(call, info.resultType)
      )

    private def specializedForSubType(vd: ValDef, owner: Symbol,
                                      specialization: Specialization,
                                      superTypeName: Name)(using Context): ValDef =
      val originalSym = vd.symbol
      val name = specializedName(vd.name, specialization)
      val info = specialize(originalSym.info, specialization, owner.typeRef)
      val sym = newSymbol(owner, name, originalSym.flags | Synthetic, info).entered
      // vals only have a specialized form in the subtype
      ValDef(sym, specialize(vd.rhs, specialization, Nil, superTypeName, owner))

    private def specializedForSubType(dd: DefDef, owner: Symbol,
                                      specialization: Specialization, specializedMembers: List[ValOrDefDef],
                                      superTypeName: Name)(using Context): List[DefDef] =
      // Each def has two variants in subtypes:
      val originalSym = dd.symbol
      // the specialized one, which has a copy of the body but specialized,
      val specName = specializedName(dd.name, specialization)
      val specInfo = specialize(originalSym.info, specialization, owner.typeRef)
      val specSym = newSymbol(owner, specName, originalSym.flags | Synthetic | Override, specInfo).entered
      val specDef = DefDef(specSym, paramss =>
        val withSubstParams = dd.rhs.subst(dd.paramss.flatten.map(_.symbol), paramss.flatten.map(_.symbol))
        specialize(withSubstParams, specialization, specializedMembers, superTypeName, owner)
      )
      // and the original one, which merely forwards
      val newSym = newSymbol(owner, dd.name, originalSym.flags | Synthetic | Override, specInfo).entered
      val newDef = DefDef(newSym, paramss => paramss.foldLeft(ref(specSym)) { case (fn, args) => fn.appliedToArgs(args) })
      List(specDef, newDef)


  /* Adds back the 'final' flag (step 4.a.ii)
  final class PostTyper extends Phase:
    import tpd.*

    override def phaseName: String =
      "scala2SpecializationForStdLib.PostTyper"

    override def isEnabled(using Context): Boolean =
      ctx.settings.YcompilingStdLib.value

    override protected def run(using Context): Unit =
      val preTyperPhase = ctx.base.phases.collectFirst{ case p: PreTyper => p }.get
      ctx.compilationUnit.tpdTree match
        case p @ PackageDef(pid, stats) =>
          ctx.compilationUnit.tpdTree = cpy.PackageDef(p)(pid, stats.map {
            case td: TypeDef if preTyperPhase.specializedTypes.contains(td.name) =>
              if preTyperPhase.typesDeclaredFinal(td.name) then
                td.symbol.setFlag(Flags.Final)
              td
            //case td: TypeDef if td.name.endsWith(ourNames.specializationSuffix) =>
            //  adaptParents(td, preTyperPhase.specializedTypes)
            case s => s
          })
        case _ => ()

    private def adaptParents(td: TypeDef, specializedTypes: TypeNameToSpecializationsByIndex)(using Context): TypeDef = td.rhs match
      case t: Template =>
        val extraParents = t.parents.flatMap {
          case p @ AppliedTypeTree(id @ Ident(n: TypeName), args) =>
            specializedTypes.get(n) match
              case Some(specsByIdx) =>
                def getSpecialization(a: Tree, n: Int): Option[TypeName] = specsByIdx.get(n) match
                  case Some(specs) => a match
                    case Ident(nm: TypeName) => Option.when(specs(nm))(nm)
                    case _ => None
                  case None => None
                val specialization = args.zipWithIndex.flatMap(getSpecialization)
                if specialization.size == specsByIdx.size then
                  val unspecialized = args.zipWithIndex.flatMap((a, n) => if specsByIdx.contains(n) then None else Some(a))
                  val newParentName = specializedName(n, specialization)
                  ??? //Some(cpy.AppliedTypeTree(p)(cpy.Ident(id)(newParentName), unspecialized))
                else None
              case None => None
          case p => None
        }
        cpy.TypeDef(td)(rhs = cpy.Template(t)(parents = extraParents ++ t.parents))
      case _ => td*/

  /** Removes 'final' from specializable classes (step 4.a.iii) */
  final class PostPickler extends Phase:
    import tpd.*

    override def phaseName: String =
      "scala2SpecializationForStdLib.PostPickler"

    override def isEnabled(using Context): Boolean =
      ctx.settings.YcompilingStdLib.value

    override protected def run(using Context): Unit =
      val ogPhase = ctx.base.phases.collectFirst{ case p: PrePickler => p }.get
      ctx.compilationUnit.tpdTree match
        case p @ PackageDef(pid, stats) =>
          stats.foreach {
            case td: TypeDef if ogPhase.specializedTypes.contains(td.name) =>
              td.symbol.resetFlag(Final)
            case _ =>
              ()
          }
        case _ => ()

  /* Encapsulates constructors (step 3)
  final class PostConstructors extends Phase:
    import tpd.*

    override def phaseName: String =
      "scala2SpecializationForStdLib.PostConstructors"

    override def isEnabled(using Context): Boolean =
      ctx.settings.YcompilingStdLib.value

    override protected def run(using Context): Unit =
      val preTyperPhase = ctx.base.phases.collectFirst{ case p: PreTyper => p }.get
      ctx.compilationUnit.tpdTree match
        case p @ PackageDef(pid, stats) =>
          ctx.compilationUnit.tpdTree = cpy.PackageDef(p)(pid, stats.map {
            case td: TypeDef if preTyperPhase.specializedTypes.contains(td.name) =>
              cpy.TypeDef(td)(rhs = encapsulateConstructor(td))
            case s => s
          })
        case _ => ()

    private def encapsulateConstructor(td: TypeDef)(using Context): Tree = td.rhs match
      case t: Template =>
        val originalConstrBody = t.constr.rhs
        val newConstrBody = If(
          Apply(Select(This(td.symbol.asClass), ourNames.specInstance), Nil),
          unitLiteral,
          originalConstrBody
        )
        cpy.Template(t)(constr = cpy.DefDef(t.constr)(rhs = newConstrBody))*/