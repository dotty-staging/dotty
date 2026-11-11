package dotty.tools.dotc.transform

import dotty.tools.dotc.ast.{tpd, untpd}
import dotty.tools.dotc.ast.Trees.Backquoted
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.{toTermName, toTypeName}
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.Phases.*
import dotty.tools.dotc.core.StdNames.{nme, tpnme}

import scala.collection
import scala.collection.mutable

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
  private def specializedName[T <: Name](name: T, specialization: Iterable[Name]): name.ThisName =
    def shortName(n: Name): String = n match
      case tpnme.Byte => "B"
      case tpnme.Short => "S"
      case tpnme.Int => "I"
      case tpnme.Long => "J"
      case tpnme.Char => "C"
      case tpnme.Float => "F"
      case tpnme.Double => "D"
      case tpnme.Boolean => "Z"
      case tpnme.Unit => "V"
    name ++ (ourNames.specializationPrefix + specialization.map(shortName).mkString("") + ourNames.specializationSuffix)

  private type TypeNameToSpecializationsByIndex = collection.Map[TypeName, collection.Map[Int, collection.Set[TypeName]]]

  /** Generates specialized subclasses (steps 1, 2, 4.a.i, 4.b, 4.c, 4.d) */
  final class PreTyper extends Phase:
    import untpd.*

    private val _specializedTypes: mutable.Map[TypeName, collection.Map[Int, collection.Set[TypeName]]] = mutable.Map.empty
    def specializedTypes: TypeNameToSpecializationsByIndex = _specializedTypes

    private val _typesDeclaredFinal: mutable.Set[TypeName] = mutable.Set.empty
    def typesDeclaredFinal: collection.Set[TypeName] = _typesDeclaredFinal

    override def phaseName: String =
      "scala2SpecializationForStdLib.PreTyper"

    override def isEnabled(using Context): Boolean =
      ctx.settings.YcompilingStdLib.value

    override def isCheckable: Boolean =
      false // we are before the typer

    override protected def run(using Context): Unit =
      ctx.compilationUnit.untpdTree match
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
            ctx.compilationUnit.untpdTree = cpy.PackageDef(p)(pid = pid, stats = specializedStats)
        case _ => ()

    private def specializeTypeDef(td: TypeDef)(using Context): List[TypeDef] =
      // First, get all specialized types and the corresponding specializations
      val typeParams = td.rhs match
        case t: Template => t.constr.leadingTypeParams
        case _ => Nil
      val specializedTypeParams =
        typeParams.flatMap(tp =>
          val types = tp.mods.annotations.flatMap {
            case Apply(Select(New(Ident(ourNames.specialized)), nme.CONSTRUCTOR), args) => specializedTypes(args)
            case _ => Nil
          }
          if types.isEmpty then None
          else Some((tp.name, types))
        )
      // Most types aren't specialized so let's not do extra work for nothing
      if specializedTypeParams.isEmpty then return List(td)
      // Then, transform that to one [type => specialization] map per result
      // (e.g., [@specialized(Int, Double) X, @specialized(Short) Y, Z] => [[X => Int, Y => Short], [X => Double, Y => Short]]
      val specializations = specializedTypeParams.foldRight(List(Map.empty[TypeName, TypeName])) {
        case ((tp, types), acc) =>
          for
            typ <- types
            rest <- acc
          yield rest.updated(tp, typ)
      }
      _specializedTypes(td.name) = typeParams.zipWithIndex.flatMap((tp, n) => specializedTypeParams.find(_._1 == tp.name) match
        case Some((_, specs)) => Some((n, specs.toSet))
        case None => None
      ).toMap
      if td.mods.is(Flags.Final) then _typesDeclaredFinal.add(td.name)
      td.rhs match {
        case t: Template =>
          val membersNeedingSpecialization = t.body.collect { case m: ValOrDefDef if needsSpecializing(m, specializations.head) => m }
          val hasSpecializedVals = membersNeedingSpecialization.exists(_.isInstanceOf[ValDef])
          // Use these specialization maps to specialize the original typedef by adding new members
          val specializedMethods = membersNeedingSpecialization.collect{ case dd: DefDef => dd }.flatMap(dd => specializations.map(sp => specializedForSuperclass(dd, sp)))
          //val specInstanceMethod = if hasSpecializedVals then List(DefDef(ourNames.specInstance, Nil, TypeTree(), Literal(Constant(false)))) else Nil
          val specializedTd = cpy.TypeDef(td)(rhs = cpy.Template(t)(body = t.body ++ specializedMethods /*++ specInstanceMethod*/))
            .withFlags(td.mods.flags &~ Flags.Final)
          // Finally, turn each of the specialization maps into a TypeDef
          val specializedSubclasses = specializations.map(spec =>
            val specializedBody = {
              if td.mods.is(Flags.Trait) then Nil // presumably because Scala2 targeted pre-default-interfaces JDK?
              else t.body.flatMap {
                case dd: DefDef if membersNeedingSpecialization.contains(dd) => specializedForSubclass(dd, spec)
                case m: ValOrDefDef if !m.rhs.isEmpty && !m.mods.is(Flags.Final) =>
                  val specialized = specialize(m, spec)
                  if specialized.eq(m) then Nil
                  else List(specialized.asInstanceOf[ValOrDefDef].withAddedFlags(Flags.Override))
                case other =>
                  Nil
              }
            }
            //val specInstanceMethod = if hasSpecializedVals then List(DefDef(ourNames.specInstance, Nil, TypeTree(), Literal(Constant(true))).withAddedFlags(Flags.Override)) else Nil
            val specializedConstr = cpy.DefDef(t.constr)(paramss = t.constr.paramss.map(_.flatMap {
              case td: TypeDef if spec.contains(td.name) => Nil
              case td: TypeDef => List(specialize(td, spec))
              // we must mark case class parameters, and explicit 'val' parameters, as `override`
              case vd: ValDef if td.mods.is(Flags.CaseClass) || !vd.mods.is(Flags.Local) => List(specialize(vd, spec).asInstanceOf[ValDef].withAddedFlags(Flags.Override))
              case vd: ValDef => List(specialize(vd, spec))
            }.asInstanceOf[ParamClause]).filter(_.nonEmpty))
            val parent = AppliedTypeTree(Ident(td.name), t.constr.leadingTypeParams.map(tp => Ident(spec.getOrElse(tp.name, tp.name))))
            val parentTermArgs = t.constr.termParamss.map(_.map(p => Ident(p.name)))
            val parentCall = if parentTermArgs.flatten.isEmpty then parent else parentTermArgs.foldLeft[Tree](Select(New(parent), nme.CONSTRUCTOR))((m, as) => Apply(m, as))
            val subclassName = specializedName(td.name, spec.values)
            cpy.TypeDef(td)(name = subclassName, rhs = cpy.Template(t)(constr = specializedConstr, parents = List(parentCall), body = specializedBody /*++ specInstanceMethod*/))
              .withFlags(td.mods.flags &~ Flags.CaseClass) // case-to-case inheritance is prohibited
              .withAttachment(Backquoted, ()) // avoid warnings about the use of $
          )

          specializedTd :: specializedSubclasses
      }

    private def specializedTypes(args: List[Tree])(using Context): List[TypeName] = args match {
      case Select(Ident(ourNames.specializable), name) :: Nil =>
        name.toString match
          case "Primitives" =>  List(tpnme.Byte, tpnme.Short, tpnme.Int, tpnme.Long, tpnme.Char, tpnme.Float, tpnme.Double, tpnme.Boolean, tpnme.Unit)
          case "Everything" =>  List(tpnme.Byte, tpnme.Short, tpnme.Int, tpnme.Long, tpnme.Char, tpnme.Float, tpnme.Double, tpnme.Boolean, tpnme.Unit /* not actually used: tpnme.AnyRef */)
          // unused: case "Bits32AndUp" => List(tpnme.Int, tpnme.Long, tpnme.Float, tpnme.Double)
          // unused: case "Integral" =>    List(tpnme.Byte, tpnme.Short, tpnme.Int, tpnme.Long, tpnme.Char)
          // unused: case "AllNumeric" =>  List(tpnme.Byte, tpnme.Short, tpnme.Int, tpnme.Long, tpnme.Char, tpnme.Float, tpnme.Double)
          // unused: case "BestOfBreed" => List(tpnme.Int, tpnme.Double, tpnme.Boolean, tpnme.Unit, tpnme.AnyRef)
          // unused: case "Unit" =>        List(tpnme.Unit)
          case "Arg" =>         List(tpnme.Int, tpnme.Long, tpnme.Float, tpnme.Double)
          case "Args" =>        List(tpnme.Int, tpnme.Long, tpnme.Double)
          case "Return" =>      List(tpnme.Int, tpnme.Long, tpnme.Float, tpnme.Double, tpnme.Boolean, tpnme.Unit)
      case idents =>
        idents.map { case Ident(n) => n.toTypeName }
    }

    // not handling arrays here, not needed for the stdlib
    private def boxCall(arg: Tree, specializableName: TypeName)(using Context): Tree =
      arg // we're before the typer so boxing will be done for us later
    private def boxToGenericParamCall(arg: Tree, specializableName: TypeName)(using Context): Tree =
      TypeApply(Select(arg, nme.asInstanceOf_), List(Ident(specializableName)))
    private def unboxCall(arg: Tree, specializableName: TypeName)(using Context): Tree =
      if specializableName == tpnme.Unit then arg
      else TypeApply(Select(arg, nme.asInstanceOf_), List(Ident(specializableName)))

    private def getSpecializableTypeName(t: Tree, specialization: Map[TypeName, TypeName]): Option[TypeName] = t match
      case Ident(name: TypeName) => specialization.get(name)
      // not needed: case AppliedTypeTree(Ident(fst), Ident(snd: TypeName) :: Nil) if fst == tpnme.Array => specialization.get(snd)
      case _ => None

    private def needsSpecializing(m: ValOrDefDef, specialization: Map[TypeName, TypeName])(using Context): Boolean =
      def containsAny(t: Tree): Boolean = getSpecializableTypeName(t, specialization).nonEmpty
      // unlike final classes which can be specialized, final members cannot
      val optOut = m.mods.is(Flags.Final) || m.mods.annotations.exists {
        case Apply(Select(New(Select(Ident(ourNames.annotation), ourNames.unspecialized)), nme.CONSTRUCTOR), Nil) => true
        case _ => false
      }
      !optOut && (m match
        case vd: ValDef => containsAny(vd.tpt)
        case dd: DefDef => containsAny(dd.tpt) || dd.termParamss.exists(ps => ps.exists(v => containsAny(v.tpt))))

    private def specialize(t: Tree, specialization: Map[TypeName, TypeName])(using Context): Tree =
      val map = new UntypedTreeMap() {
        override def transform(tree: untpd.Tree)(using Context): untpd.Tree = tree match
          case Ident(n: TypeName) => specialization.get(n).map(Ident.apply).getOrElse(super.transform(tree))
          case _ => super.transform(tree)
      }
      map.transform(t)

    private def specializedDefDef(dd: DefDef, specialization: Map[TypeName, TypeName], name: TermName, body: Tree)(using Context): DefDef =
      val paramss = dd.paramss.map(_.map {
        case v: ValDef =>
          getSpecializableTypeName(v.tpt, specialization) match
            case Some(tn) => cpy.ValDef(v)(tpt = Ident(tn))
            case None => v
        case t: TypeDef => t
      }.asInstanceOf[ParamClause])
      val tpt = getSpecializableTypeName(dd.tpt, specialization) match
        case Some(tn) => Ident(tn)
        case None => dd.tpt
      cpy.DefDef(dd)(name = name, paramss = paramss, tpt = tpt, rhs = body)

    private def specializedForSuperclass(dd: DefDef, specialization: Map[TypeName, TypeName])(using Context): DefDef =
      val forwarderBody = {
        val argsForCall = dd.termParamss.map(_.map(v =>
          val original = Ident(v.name)
          v.tpt match
            case Ident(n: TypeName) if specialization.contains(n) => boxToGenericParamCall(original, n)
            case _ => original
        ))
        val call = argsForCall.foldLeft[Tree](Ident(dd.name))((m, as) => Apply(m, as))
        getSpecializableTypeName(dd.tpt, specialization) match
          case Some(tn) => unboxCall(call, tn)
          case None => call
      }
      specializedDefDef(dd, specialization, specializedName(dd.name, specialization.values), forwarderBody)
        .withAttachment(Backquoted, ()) // avoid warnings about the use of $

    private def specializedForSubclass(m: ValOrDefDef, specialization: Map[TypeName, TypeName])(using Context): List[ValOrDefDef] = m match
      case vd: ValDef => specializedForSubclass(vd, specialization)
      case dd: DefDef => specializedForSubclass(dd, specialization)

    private def specializedForSubclass(vd: ValDef, specialization: Map[TypeName, TypeName])(using Context): List[ValDef] =
      val tpt = getSpecializableTypeName(vd.tpt, specialization) match
        case Some(tn) => Ident(tn)
        case None => vd.tpt // shouldn't happen, why else would we be specializing a valdef?
      val body = specialize(vd.rhs, specialization)
      List(cpy.ValDef(vd)(name = specializedName(vd.name, specialization.values), tpt = tpt, rhs = body))

    private def specializedForSubclass(dd: DefDef, specialization: Map[TypeName, TypeName])(using Context): List[DefDef] =
      val specName = specializedName(dd.name, specialization.values)
      val forwarderBody = {
        val argsForCall = dd.termParamss.map(_.map(v =>
          val original = Ident(v.name)
          getSpecializableTypeName(v.tpt, specialization) match
            case Some(tn) => unboxCall(original, tn)
            case None => original
        ))
        val call = argsForCall.foldLeft[Tree](Ident(specName))((m, as) => Apply(m, as))
        getSpecializableTypeName(dd.tpt, specialization) match
          case Some(tn) => boxCall(call, tn)
          case None => call
      }
      val forwarder = specializedDefDef(dd, specialization, dd.name, forwarderBody)
        .withAddedFlags(Flags.Override)
      val specialized = specializedDefDef(dd, specialization, specName, specialize(dd.rhs, specialization))
        .withAddedFlags(Flags.Override)
        .withAttachment(Backquoted, ()) // avoid warnings about the use of $
      List(forwarder, specialized)

  /** Adds back the 'final' flag (step 4.a.ii) */
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
      case _ => td

  /** Removes 'final' from specializable classes (step 4.a.iii) */
  final class PostPickler extends Phase:
    import tpd.*

    override def phaseName: String =
      "scala2SpecializationForStdLib.PostPickler"

    override def isEnabled(using Context): Boolean =
      ctx.settings.YcompilingStdLib.value

    override protected def run(using Context): Unit =
      val preTyperPhase = ctx.base.phases.collectFirst{ case p: PreTyper => p }.get
      ctx.compilationUnit.tpdTree match
        case p @ PackageDef(pid, stats) =>
          stats.foreach {
            case td: TypeDef if preTyperPhase.specializedTypes.contains(td.name) =>
              td.symbol.resetFlag(Flags.Final)
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