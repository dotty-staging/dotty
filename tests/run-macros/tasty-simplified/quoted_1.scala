import scala.annotation.tailrec
import scala.quoted._

object Macros {

  inline def simplified[T <: Tuple]: Seq[String] = ${ impl[T] }

  def impl[T: Type](given qctx: QuoteContext): Expr[Seq[String]] = {
    import qctx.tasty.{_, given}

    def unpackTuple(tp: Tpe): List[Tpe] = {
      @tailrec
      def loop(tp: Tpe, acc: List[Tpe]): List[Tpe] = tp.dealias.simplified match {
        case AppliedType(_, List(IsTpe(hd), IsTpe(tl))) =>
          loop(tl, hd.dealias.simplified :: acc)
        case other => acc
      }
      loop(tp, Nil).reverse
    }

    val tps = unpackTuple(typeOf[T])
    Expr.ofSeq(tps.map(x => Expr(x.show)))
  }
}
