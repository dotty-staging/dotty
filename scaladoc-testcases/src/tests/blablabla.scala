package tests
package blablabla

trait A:
  def baz: B
    = baz2
  def baz2: A.this.B //expected: def baz2: B
    = baz
  type B
  class C extends A:
    def foo: A.this.B
      = ???
    def foo2: B
      = ???
    def bar: B
      = ???
