package dotty.tools.dotc
package transform
package init

import core._
import MegaPhase._
import Contexts.Context
import StdNames._
import Names._
import Phases._
import ast._
import Trees._
import Flags._
import SymUtils._
import Symbols._
import Denotations._
import SymDenotations._
import Types._
import Decorators._
import DenotTransformers._
import util.Positions._
import config.Printers.init.{ println => debug }
import Constants.Constant
import collection.mutable

object Checker {
  val name = "initChecker"
}

/** This transform checks initialization is safe based on data-flow analysis
 *
 *  - Cold
 *  - Warm
 *  - Hot
 *
 *  1. A _hot_ object is fully initialized.
 *  2. All fields of a _warm_ object are assigned, but the fields may refer to non-full objects.
 *  3. A _cold_ object may have unassigned fields.
 *
 *  TODO:
 *   - check default arguments of init methods
 *   - handle tailrec calls during initialization (which captures `this`)
 */
class Checker extends MiniPhase with IdentityDenotTransformer { thisPhase =>
  import tpd._

  override def phaseName: String = Checker.name

  override def transformTypeDef(tree: TypeDef)(implicit ctx: Context): Tree = {
    if (!tree.isClassDef) return tree

    val cls = tree.symbol.asClass
    val self = cls.thisType

    // ignore init checking if `@unchecked`
    if (cls.hasAnnotation(defn.UncheckedAnnot)) return tree

    def lateInitMsg(sym: Symbol) =
      s"""|Initialization too late: $sym is used during parent initialization.
          |Consider make it a class parameter."""
        .stripMargin

    for (decl <- cls.info.decls.toList if decl.is(AnyFlags, butNot = Method | Deferred)) {
      if (!decl.is(ParamAccessor | Override) && decl.isCalledAbove(cls))
        ctx.warning(lateInitMsg(decl), decl.pos)
    }

    def invalidImplementMsg(sym: Symbol) = {
      val annot = if (sym.owner.is(Trait)) "cold" else "init"
      s"""|@scala.annotation.$annot required for ${sym.show} in ${sym.owner.show}
          |Because the method is called during initialization."""
        .stripMargin
    }

    def parents(cls: ClassSymbol) =
      cls.baseClasses.tail.filter(_.is(AbstractOrTrait)).dropWhile(_.is(JavaDefined | Scala2x))

    def check(curCls: ClassSymbol): Unit = {
      for {
        mbr <- calledSymsIn(curCls)
        mbrd <- self.member(mbr.name).alternatives
        tp = mbr.info.asSeenFrom(self, mbr.owner)
        if mbrd.info.overrides(tp, matchLoosely = true) &&
           !mbrd.symbol.isInit && !mbrd.symbol.isCold &
           !mbrd.symbol.isCalledAbove(cls.asClass) &&
           !mbrd.symbol.is(Deferred)
      } ctx.warning(invalidImplementMsg(mbrd.symbol), cls.pos)
    }
    parents(cls).foreach(check)  // no need to check methods defined in current class

    checkInit(cls, tree)

    tree
  }

  def checkInit(cls: ClassSymbol, cdef: tpd.TypeDef)(implicit ctx: Context) = {
    val tmpl = cdef.rhs.asInstanceOf[tpd.Template]

    debug("*************************************")
    debug("checking " + cls.show)
    debug("*************************************")

    val analyzer = new Analyzer

    // cold check
    coldCheck(cls, tmpl, analyzer)

    // current class env needs special setup
    val root = Heap.createRootEnv
    val setting = Setting(root, cls.pos, ctx, analyzer)
    val obj = new ObjectValue(tp = cls.typeRef, open = !cls.is(Final) && !cls.isAnonymousClass)
      // enhancement possible to check if there are actual children
      // and whether children are possible in other modules.

    // for recursive usage
    indexOuter(cls)(setting)

    val classValue = analyzer.classValue(cdef)(setting)
    // init check
    val constr = tmpl.constr
    val values = constr.vparamss.flatten.map { param => param.tpe.widen.value }
    val poss = constr.vparamss.flatten.map(_.pos)
    val res = classValue.init(constr.symbol, values, poss, obj)(setting)
    val slice = obj.slices(cls).asSlice(setting)

    res.effects.foreach(_.report)

    // init check: try commit early
    if (obj.open) {
      val innerEnv = obj.slices(cls).asSlice(setting).innerEnv
      initCheck(cls, obj, tmpl)(setting.withEnv(innerEnv))
    }
  }

  def coldCheck(cls: ClassSymbol, tmpl: tpd.Template, analyzer: Analyzer)(implicit ctx: Context) = {
    val obj = new ObjectValue(tp = cls.typeRef, open = !cls.is(Final) && !cls.isAnonymousClass)
      // enhancement possible to check if there are actual children
      // and whether children are possible in other modules.

    def checkMethod(ddef: tpd.DefDef): Unit = {
      val sym = ddef.symbol
      if (!sym.isEffectiveCold) return

      val root = Heap.createRootEnv
      val setting: Setting = Setting(root, sym.pos, ctx, analyzer, isWidening = true)
      indexOuter(cls)(setting)
      if (sym.isIcy) root.add(cls, IcyValue)
      else root.add(cls, ColdValue)

      val value = analyzer.methodValue(ddef)(setting)
      val res = value.apply(i => HotValue, i => NoPosition)(setting)

      if (res.hasErrors) {
        ctx.warning("Calling the method during initialization causes errors", sym.pos)
        res.effects.foreach(_.report)
      }
      else if (!res.value.widen(setting).isHot) {
        ctx.warning("A method called during initialization must return a fully initialized value", sym.pos)
      }
    }

    def checkLazy(vdef: tpd.ValDef): Unit = {
      val sym = vdef.symbol
      if (!sym.isEffectiveCold) return

      val root = Heap.createRootEnv
      val setting: Setting = Setting(root, sym.pos, ctx, analyzer, isWidening = true)
      indexOuter(cls)(setting)
      if (sym.isCold) root.add(cls, IcyValue)
      else root.add(cls, ColdValue)

      val value = analyzer.lazyValue(vdef)(setting)
      val res = value.apply(i => HotValue, i => NoPosition)(setting)

      if (res.hasErrors) {
        ctx.warning("Forcing cold lazy value causes errors", sym.pos)
        res.effects.foreach(_.report)
      }
      else {
        val value = res.value.widen(setting)
        if (!value.isHot) ctx.warning("Cold lazy value must return a full value", sym.pos)
      }
    }

    tmpl.body.foreach {
      case ddef: DefDef if !ddef.symbol.hasAnnotation(defn.UncheckedAnnot) =>
        checkMethod(ddef)
      case vdef: ValDef if vdef.symbol.is(Lazy)  =>
        checkLazy(vdef)
      case _ =>
    }
  }

  def initCheck(cls: ClassSymbol, obj: ObjectValue, tmpl: tpd.Template)(implicit setting: Setting) = {
    def checkMethod(ddef: tpd.DefDef)(implicit setting: Setting): Unit = {
      val sym = ddef.symbol
      if (!sym.isEffectiveInit && !sym.isCalledIn(cls)) return

      var res = obj.select(sym, isStaticDispatch = true)
      if (!sym.info.isParameterless)
        res = res.value.apply(i => HotValue, i => NoPosition)
      if (res.hasErrors) {
        setting.ctx.warning(s"Calling the init $sym causes errors", sym.pos)
        res.effects.foreach(_.report)
      }
      else if (!res.value.widen(setting.widening).isHot) {
        setting.ctx.warning("A dynamic init method must return a full value", sym.pos)
      }
    }

    def checkLazy(vdef: tpd.ValDef)(implicit setting: Setting): Unit = {
      val sym = vdef.symbol
      if (!sym.isEffectiveInit) return

      val res = obj.select(sym, isStaticDispatch = true)
      if (res.hasErrors) {
        setting.ctx.warning("Forcing init lazy value causes errors", sym.pos)
        res.effects.foreach(_.report)
      }
      else {
        val value = res.value.widen(setting.widening)
        if (!value.isHot) setting.ctx.warning("Init lazy value must return a full value", sym.pos)
      }
    }

    def checkValDef(vdef: tpd.ValDef)(implicit setting: Setting): Unit = {
      val sym = vdef.symbol
      if (sym.is(Flags.PrivateOrLocal)) return

      val actual = obj.select(sym, isStaticDispatch = true).value.widen(setting.widening)
      if (actual.isCold) sym.annotate(defn.ColdAnnotType)
      else if (actual.isWarm) sym.annotate(defn.WarmAnnotType)

      if (sym.isOverrideClassParam && !sym.isClassParam) {
        setting.ctx.warning("Overriding a class parameter in class body may cause initialization problems", sym.pos)
      }
      else if (!sym.isHot && sym.allOverriddenSymbols.exists(_.isHot)) {
        setting.ctx.warning("Overriding a fully initialized class parameter with a cold parameter may cause initialization problems", sym.pos)
      }
    }

    def checkClassDef(cdef: tpd.TypeDef)(implicit setting: Setting): Unit = {
      val sym = cdef.symbol
      if (sym.isInit) {
        val setting2 = setting.widening
        val value = setting2.analyzer.widenTree(cdef)(setting2)

        val captured = Capture.analyze(cdef)(setting2)
        val notHot = captured.keys.filterNot(setting2.widen(_).isHot)

        for(key <- notHot; tree <- captured(key))
          setting.ctx.warning(s"The init $sym captures " + tree.show + ".\nTry to make captured fields or methods private or final.", tree.pos)
      }
    }

    tmpl.body.foreach {
      case ddef: DefDef if !ddef.symbol.hasAnnotation(defn.UncheckedAnnot) =>
        checkMethod(ddef)(setting.withPos(ddef.symbol.pos))
      case vdef: ValDef if vdef.symbol.is(Lazy)  =>
        checkLazy(vdef)(setting.withPos(vdef.symbol.pos))
      case vdef: ValDef =>
        checkValDef(vdef)(setting.withPos(vdef.symbol.pos))
      case cdef: TypeDef if cdef.isClassDef =>
        checkClassDef(cdef)(setting.withPos(cdef.symbol.pos))
      case _ =>
    }
  }


  def indexOuter(cls: ClassSymbol)(implicit setting: Setting) = {
    def recur(cls: Symbol, maxValue: OpaqueValue): Unit = if (cls.owner.exists) {
      val outerValue = cls.value
      val enclosingCls = cls.owner.enclosingClass

      if (!cls.owner.isClass || maxValue == HotValue) {
        setting.env.add(enclosingCls, HotValue)
        recur(enclosingCls, HotValue)
      }
      else {
        val meet = outerValue.meet(maxValue)
        setting.env.add(enclosingCls, meet)
        recur(enclosingCls, meet)
      }
    }
    recur(cls, cls.value)
  }
}
