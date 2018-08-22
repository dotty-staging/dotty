package dotty.tools.dotc
package tastyreflect

import dotty.tools.dotc.ast.{tpd, untpd}
import dotty.tools.dotc.core.Constants
import dotty.tools.dotc.core.Types

trait TastyCoreImpl extends scala.tasty.reflect.TastyCore {

  type Context = core.Contexts.Context

  type Parent = tpd.Tree

  type Tree = tpd.Tree
    type PackageClause = tpd.PackageDef
    type Statement = tpd.Tree
      type Import = tpd.Import
      type Definition = tpd.Tree
        type ClassDef = tpd.TypeDef
        type TypeDef = tpd.TypeDef
        type DefDef = tpd.DefDef
        type ValDef = tpd.ValDef
        type PackageDef = PackageDefinition
      type Term = tpd.Tree

  type CaseDef = tpd.CaseDef

  type Pattern = tpd.Tree

  type TypeOrBoundsTree = tpd.Tree
    type TypeTree = tpd.Tree
    type TypeBoundsTree = tpd.Tree

  type TypeOrBounds = Types.Type
    type NoPrefix = Types.NoPrefix.type
    type TypeBounds = Types.TypeBounds
    type Type = Types.Type
    type RecursiveType = Types.RecType
    type LambdaType[ParamInfo] = Types.LambdaType { type PInfo = ParamInfo }
      type MethodType = Types.MethodType
      type PolyType = Types.PolyType
      type TypeLambda = Types.TypeLambda

  type ImportSelector = untpd.Tree

  type Id = untpd.Ident

  type Signature = core.Signature

  type Position = util.SourcePosition

  type Constant = Constants.Constant
  
}
