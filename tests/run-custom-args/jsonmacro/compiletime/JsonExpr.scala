package jsonlib.compiletime

import scala.quoted.*

import jsonlib.*
import jsonlib.parser.*
import jsonlib.schema.*
import jsonlib.util.*

object JsonExpr:
  /*private[jsonmacro]*/ def jsonExpr(jsonStringContext: Expr[JsonStringContext], argsExpr: Expr[Seq[Json.Value]])(using Quotes): Expr[Json.Value] =
    jsonStringContext match
      case '{ jsonlib.json($sc) } =>
        val jsonString = sc.valueOrAbort.parts.map(scala.StringContext.processEscapes)
        Parser(jsonString).parse() match
          case Success(json) =>
            val argExprs = argsExpr match
              case Varargs(argExprs) => argExprs
              case _ => quotes.reflect.report.errorAndAbort("Unpacking StringContext.json args is not supported")
            val jsonExpr = toJsonExpr(json, argExprs)
            ExprSchema.refinedType(json, argExprs) match
              case '[t] => '{ $jsonExpr.asInstanceOf[t & Json.Value] }
          case Error(ParseError(msg, part, offset)) =>
            def error(args: Seq[Expr[String]]) =
              import quotes.reflect.*
              val baseOffset = args(part).asTerm.pos.start
              val pos = Position(jsonStringContext.asTerm.pos.sourceFile, baseOffset + offset, baseOffset + offset)
              report.errorAndAbort(msg + s"($part, $offset)", pos)
            sc match
              case '{ new scala.StringContext(${Varargs(args)}: _*) } => error(args)
              case '{     scala.StringContext(${Varargs(args)}: _*) } => error(args)
              case _ =>
                quotes.reflect.report.errorAndAbort("string context is not known statically")

  def jsonUnapplyExpr(jsonStringContext: Expr[JsonStringContext])(using Quotes): Expr[Option[Any]] =
    quotes.reflect.report.errorAndAbort("jsonUnapplyExpr is not implemented")

  private def toJsonExpr(json: Parsed.Value, args: Seq[Expr[Json.Value]])(using Quotes): Expr[Json.Value] =
    json match
      case Parsed.Null => '{ Json.Null }
      case Parsed.Bool(value) => '{ Json.Bool(${Expr(value)}) }
      case Parsed.Num(value) => '{ Json.Num(${Expr(value)}) }
      case Parsed.Str(value) => '{ Json.Str(${Expr(value)}) }
      case Parsed.Arr(value*) => '{ Json.Arr(${Varargs(value.map(toJsonExpr(_, args)))}*) }
      case Parsed.Obj(nameValues*) =>
        val nameValueExprs = for (name, value) <- nameValues yield '{ (Json.Str(${Expr(name)}), ${toJsonExpr(value, args)}) }
        '{ Json.Obj(Map(${Varargs(nameValueExprs)}*)) }
      case Parsed.InterpolatedValue(idx) => args(idx)