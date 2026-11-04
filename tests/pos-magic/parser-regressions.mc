# Consecutive `from` imports
from a.b import c
from .d import *

# `not` at the start of a line
def isNotEmpty: Boolean =
    """Doc"""
    not elems.isEmpty

# Typed pattern variables in parentheses
def f(x: Any) =
    match x:
    case (c: Const): c
    case (_: Var): 1
    case _: 2

# Single-line suites followed by more lines
def g(x: Int) =
    if x > 0: a
    else: b

# Case guards ending in an expression followed by `:`
def h(x: Any) =
    match x:
    case (tm: Map) if self.isInstanceOf[HiddenSet]:
        self
    case _: x

# String interpolation, with escaped newlines not treated as dedented strings
val s = i"x {y} z"
val t = i"\nNote that {sym} is a capability"
val u = mkString("\nbecause ", "\nand ", "")

# Case classes
case class C(x: Int)

# Optional types
class D(override val owner: Symbol):
    private var myRepr: Name? := None
    def f(x: Name?, y: List[Int?]): Name? | String
    def g(): Name?
    def h = 1

# Local witnesses
def k() =
    witness val vs = VarState()
    vs
