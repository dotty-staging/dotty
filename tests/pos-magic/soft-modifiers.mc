# Identifiers named like soft modifiers, used as expressions
def f() =
  open
  update(x)
def g() =
  lazy
  open(x)
class C:
  transparent inline def h() = 1
# Soft modifiers spread over several lines still start a definition
class D:
  transparent
  inline
  def h() = 1
  override
  open
  class E
