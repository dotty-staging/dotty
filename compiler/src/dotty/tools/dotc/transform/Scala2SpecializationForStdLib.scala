package dotty.tools.dotc.transform

import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.toTypeName
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.core.StdNames.nme

/**
 * Implements just enough Scala2-style specialization for the standard library and only the standard library
 *
 * THIS IS NOT TO BE USED ELSEWHERE. DO NOT TRY TO USE IT FOR YOUR OWN CODE. DO NOT ASK FOR IT TO BE SUPPORTED.
 */
final class Scala2SpecializationForStdLib extends Phase:
  private val specializedTypeName = "specialized".toTypeName
  private val specializableTypeName = "Specializable".toTypeName

  override def phaseName: String = "scala2SpeializationForStdLib"

  override def isEnabled(using Context): Boolean =
    ctx.settings.YcompilingStdLib.value

  override protected def run(using Context): Unit = {
    val unit = ctx.compilationUnit
    unit.untpdTree match
      case p @ PackageDef(pid, stats) =>
        val specializedStats = stats.flatMap {
          case unspecializedTd: TypeDef =>
            val allTds = unspecializedTd :: specializeTypeDef(unspecializedTd)
            allTds.map(td => td.rhs match {
              case t: Template =>
                val specializedBody = t.body.flatMap {
                  case dd: DefDef => dd :: specializeDefDef(dd)
                  case other => List(other)
                }
                cpy.TypeDef(td)(rhs = cpy.Template(t)(body = specializedBody))
              case _ => td
            })
          case other =>
            List(other)
        }
        cpy.PackageDef(p)(pid = pid, stats = specializedStats)
      case _ => ()
  }

  private def specializeTypeDef(td: TypeDef)(using Context): List[TypeDef] =
    val specializedTypeParams = td.rhs match {
      case t: Template =>
        t.constr.leadingTypeParams.flatMap(tp =>
          val types = tp.mods.annotations.flatMap {
            case Apply(Select(New(Ident(specializedTypeName)), nme.CONSTRUCTOR), args) => specializedTypes(args)
            case _ => Nil
          }
          if types.isEmpty then None
          else Some((tp, types))
        )
      case _ => Nil
    }
    if specializedTypeParams.isEmpty then return Nil
    ???

  private def specializeDefDef(td: DefDef)(using Context): List[TypeDef] = {
    ???
  }

  private def specializedTypes(args: List[Tree])(using Context): List[Name] = args match {
    case Select(Ident(specializableTypeName), name) :: Nil =>
      name.toString match {
        case "Primitives" =>  List(nme.Byte, nme.Short, nme.Int, nme.Long, nme.Char, nme.Float, nme.Double, nme.Boolean, nme.Unit)
        case "Everything" =>  List(nme.Byte, nme.Short, nme.Int, nme.Long, nme.Char, nme.Float, nme.Double, nme.Boolean, nme.Unit, nme.AnyRef)
        case "Bits32AndUp" => List(nme.Int, nme.Long, nme.Float, nme.Double)
        case "Integral" =>    List(nme.Byte, nme.Short, nme.Int, nme.Long, nme.Char)
        case "AllNumeric" =>  List(nme.Byte, nme.Short, nme.Int, nme.Long, nme.Char, nme.Float, nme.Double)
        case "BestOfBreed" => List(nme.Int, nme.Double, nme.Boolean, nme.Unit, nme.AnyRef)
        case "Unit" =>        List(nme.Unit)
        case "Arg" =>         List(nme.Int, nme.Long, nme.Float, nme.Double)
        case "Args" =>        List(nme.Int, nme.Long, nme.Double)
        case "Return" =>      List(nme.Int, nme.Long, nme.Float, nme.Double, nme.Boolean, nme.Unit)
      }
    case idents =>
      idents.map { case Ident(n) => n }
  }
