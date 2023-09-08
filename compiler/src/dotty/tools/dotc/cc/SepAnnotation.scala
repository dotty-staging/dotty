package dotty.tools
package dotc
package cc

import core.*
import Types.*, Symbols.*, Contexts.*, Annotations.*
import ast.Trees.*
import ast.{tpd, untpd}
import Decorators.*
import config.Printers.capt
import printing.Printer
import printing.Texts.*

case class SepAnnotation(refs: CaptureSet)(cls: ClassSymbol) extends Annotation:
  import tpd.*

  /** Reconstitute annotation tree from capture set */
  override def tree(using Context) =
    val elems = refs.elems.toList.map {
      case cr: TermRef => ref(cr)
      case cr: TermParamRef => untpd.Ident(cr.paramName).withType(cr)
      case cr: ThisType => This(cr.cls)
    }
    val arg = repeated(elems, TypeTree(defn.AnyType))
    New(symbol.typeRef, arg :: Nil)

  override def symbol(using Context) = cls

  override def derivedAnnotation(tree: Tree)(using Context): Annotation = this

  def derivedAnnotation(refs: CaptureSet)(using Context): Annotation =
    if (this.refs eq refs) then this
    else SepAnnotation(refs)(cls)

  override def sameAnnotation(that: Annotation)(using Context): Boolean = that match
    case SepAnnotation(refs) =>
      this.refs == refs && this.symbol == that.symbol
    case _ => false

  override def mapWith(tm: TypeMap)(using Context) =
    val elems = refs.elems.toList
    val elems1 = elems.mapConserve(tm)
    if elems1 eq elems then this
    else if elems1.forall(_.isInstanceOf[CaptureRef])
    then derivedAnnotation(CaptureSet(elems1.asInstanceOf[List[CaptureRef]]*))
    else EmptyAnnotation

  override def refersToParamOf(tl: TermLambda)(using Context): Boolean =
    refs.elems.exists {
      case TermParamRef(tl1, _) => tl eq tl1
      case _ => false
    }

  override def toText(printer: Printer): Text = "sep" ~ refs.toText(printer)

  override def eql(that: Annotation) = that match
    case that: SepAnnotation => this.refs eq that.refs
    case _ => false

