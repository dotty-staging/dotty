---
marp: true
paginate: true
title: "Tracked Capabilities Overview"
---

# Checking Mutation and Separation in Scala 3

Martin Odersky
VIMPL 2026
Joint work with Yichen Xu, Yaoyu Zhao, Cao Nguyen Pham, and Oliver Bracevac.

![h:380](./capybara.png)

---

# Tracked Capabilities In Scala 3

**Tracked capabilities** are the most important new feature of Scala.

(Object-)capabilities were in invented already in 1966 by Dennis and Van Horn. They are a well-established security primitive.

What's new is that now we record in a type which capabilities are retained by its values:

```scala
    T^{a, b}
```
Here `T` is a normal type, and `a, b` are capabilities that can be free in values of `T`.

This allows us to record possible **effects** of closures and objects.

---

# This Talk

This talk is about some recent additions to capture checking in Scala 3.

### Mutation

 - Capabilities can now also express read and write effects to mutable data.

### Separation Checking

 - Prevents hidden aliases and unsafe concurrent access to mutable data.

---

# Currently These Are Optional

Tracked capabilities are supported under Scala's capture checking extension, enabled
by the language import
```scala
import language.experimental.captureChecking
```
Separation checking and some other features of mutable data require a separate language import:

```scala
import language.experimental.separationChecking
```
Both extensions are **experimental**, which means that details can still change.

---

# Capabilities

Informally, a capability is a value "of interest". For instance:

 - a file handle,
 - an access permission token,
 -  a mutable data structure

Contrast with pair `("hello", "world!")`: this is just a value, not a capability.

Often capabilities are associated with **effects**.

For instance, a file handle gives access to the effect of reading or writing it.

One can designate a value by extending a standard trait `Capability`:
```scala
	class File(path: String) extends ExclusiveCapability
```
---

# Capability Tracking

Capabilities in Scala 3 are **tracked**: we record in a type which capabilities can be accessed by values of that type.

We write `A^{c}` for the type of values of type `A` that can access the capability `c`.

For instance we can define a class `Logger` for sending log messages to a file and instantiate it like this:
```scala sc:nocompile
  class Logger(f: File) { ... }

  val out = File("~/some/bits")
  val lg: Logger^{out} = Logger(out)
```
We say `lg` **captures** `out` and call `Logger^{out}` a **capturing type**.

---

# Capability Tracking Notation

Generally, the type `A^{c₁, ..., cₙ}` stands for instances that retain capabilities
`c₁, ..., cₙ`.

If class `A` does not extend `Capability`, then the type `A` alone stands for instances that retain no capabilities

 - i.e. `A` is equivalent to `A^{}`.
 - We also say `A` is **pure**.

The opposite of pure `A` describes instances of `A` that can retain arbitrary capabilities.
This type is `A^{any}`, or, shorter, `A^`.

---

# Derived Capabilities

Values of capturing types are themselves considered capabilities.

For instance in
```scala
val lg: Logger^{out} = Logger(out)
```
`lg`is treated as a capability even though its class `Logger` does not extend `Capability`.

---

# Subcapturing

Capability sets induce a subtyping relation, where smaller sets lead to smaller types.

Also, if `c`: `A^{c₁, ..., cₙ}` then `{c} <: {c₁, ..., cₙ}`.

For instance:
```
  A  <:  A^{lg}  <:  A^{out}  <:  A^{out, f}  <:  A^
```

---

# Function Types

The function type `A -> B` is considered to be pure, so it cannot retain any capability.

**Shorthands**

```scala sc:nocompile
   A ->{c₁, ..., cₙ} B   =  (A -> B)^{c₁, ..., cₙ}
                A => B   =  A ->{any} B
```

A function captures any capabilities accessed by its body. E.g. the function
```scala sc:nocompile
(x: Int) =>
  lg.log(s"called with parameter $x")
  x + 1
```
has type `Int ->{lg} Int`, which is a subtype of `Int => Int`.

---

# Methods

Scala systematically distinguishes **methods**, which are members of classes and objects, from **functions**, which are objects themselves.

Methods don't have types or tracked capability sets. Instead, the capability set is associated with the enclosing object.
```scala
> val exec = new Runnable:
    def run() = lg.log(s"called with parameter $x")
> exec: Runnable^{lg}
```
Methods are converted to functions by implicit eta-expansion.
```scala
exec.run  =  () => exec.run()
exec.run: () ->{exec} Unit  <:  () -> {lg} Unit
```
---

# Lifetimes

The capture checker controls the lifetime of capabilities. Example:

```scala
def logged[T](op: Logger^ => T): T =
  val f = new File("logfile")
  val l = Logger(f)
  val result = op(l)
  f.close()
  result
```
Problematic use:
```scala
  val bad = logged(l => () => l.log("too late!")))
  bad()
```
This is rejected since the type parameter `T` of `logged` would be instantiated to `() ->{l} Unit` but `l` is not visible where `T` is defined.

---

# Implicit Capability Passing

Common issue with traditional capabilities:

  - passing them as parameters can get tedious quickly.

In Scala, this is not a problem since parameters can be implicit.

For instanace, `Async` contexts in `Gears`:

```scala
  class Async extends SharedCapability

  def readDataEventually(file: File)(using async: Async): Data = ...

  def processData(using Async) =
    val file = File("~/some/path")
    readDataEventually(file)
```
---

# New: Mutation

Mutable variables and mutable data structures are now also considered capabilities.
```scala
> var counter: Int = 0
> def incr = () => counter += 1
incr: () ->{counter} Unit
> def current = () => counter
current: () ->{counter.rd} Unit
```
We distinguish read (`x.rd`) and write (`x`) accesses to mutable data.

---

# Mutable Data Structures

Mutable data structures extend trait `Mutable`, which is another subtrait of `Capability`.

Methods that write to mutable data are marked with an `update` modifier.

```scala
class Ref(init: Int) extends Mutable:
  private var elem: Int = init
  update def set(x: T) = elem = x
  def get: T = elem

class Buffer[T] extends Mutable:
  update def append(elem: T): Unit
  def apply(pos: Int): T
  def size: Int
```

---

# Mutable Data Access

Types are used to regulate calls to update methods.

A reference of type `Buffer` only allows access to regular methods.

If the reference has type `Buffer^` it also allows access to update methods.

```scala sc:nocompile
def copy(from: Buffer[T], to: Buffer[T]^): Unit =
  for i <- 0 until from.size do
    to.append(from(i))
```

---

# Desugaring Mutable Access

In fact, for `Mutable` capability classes `C`, `C` alone stands for `C^{any.rd}`, whereas `C^` stands for `C^{any}`.

(For other capability types the two forms are equivalent.)

So the previous example expands to:
```scala
def copy(from: Buffer[T]^{any.rd}, to: Buffer[T]^{any}): Unit =
  for i <- 0 until from.size do
    to.append(from(i))
```

---

# A Contradiction?

But if we also consider subtyping and subcapturing, we observe what looks like a contradiction:

`x.rd` is a restricted capability, so `{x.rd} <: {x}`.

But for access it goes the other way: a `C^{any}` value can be passed to a `C^{any.rd}` parameter.

The contradiction can be explained by noting that we use a capture set in two different roles.

 - To define **retained capabilities**.  More capabilities give rise to larger types. Sets with read-only capabilities are give smaller types than sets with full capabilities.
 - To define **access permissions** to mutable data. Here `Buffer[T]^{any}` can access all methods, but `Buffer[T]^{any.rd}` can only access non-update methods.

---

# Read-Only Capture Sets

The contradiction can be solved by distinguishing these two roles.

For access permissions, we express read-only sets with an additional **qualifier** `reader`.

 - used only in the formal theory and the implementation, not expressible in source.

`reader` is added to all capture sets on mutable types that consist only of shared or read-only capabilities.

So when we write
```scala sc:nocompile
val b1: Ref^{a.rd} = a
```
we really mean
```scala sc:nocompile
val b1: Ref^{a.rd}.reader = a
```
---

# Refined Subcapturing

The subcapturing theory for capsets has the following additional rules for mutable types `C`:

 - `C <: C.reader`
 - `C₁.reader <: C₂.reader` if `C₍ <: C₂`
 - `{x, ...}.reader = {x.rd, ...}.reader`
 - `{x.rd, ...} <: {x, ...}`

---

# Separation Checking

Separation checking is an extension of capture checking that enforces unique, un-aliased access to capabilities.

Separation checking ensures that certain accesses to capabilities are not aliased.

---

# Example: Matrix Multiplication

```scala sc:nocompile
class Matrix(nrows: Int, ncols: Int) extends Mutable:
  update def setElem(i: Int, j: Int, x: Double): Unit = ...
  def getElem(i: Int, j: Int): Double = ...


def multiply(a: Matrix, b: Matrix, c: Matrix^): Unit
```

This signature enforces two desirable properties:

 - Matrices `a`, and `b` are **read-only**; `multiply` will not call their update method. By contrast, the `c` matrix can be updated.
 - Matrices `a` and `b` are **different** from matrix `c`, but `a` and `b` could refer to the same matrix.

So, effectively, anything that can be updated must be unaliased. (mutation **xor** aliasing)

---

# The Core Idea


 - We now interpret each occurrence of `any` as a **separate** top capability.

   This includes derived syntaxes like `A^` and `B => C`.

 - We keep track during capture checking which capabilities are subsumed by each `any`.

   If capture checking widens a capability `x` to a top capability `anyᵢ`, we say `x` is __hidden__ by `anyᵢ`.

 - A capability hidden by a top capability `anyᵢ` cannot be referenced independently or hidden in another `anyⱼ` in code that can see `anyᵢ`.



---

# Example

```scala sc:nocompile
val x: C^ = y
  ... x ...  // ok
  ... y ...  // error
```
 - Capabilities such as `x` that have `any` as underlying capture set are un-aliased or "fresh".
 - Previously existing aliases such as `y` are inaccessible as long as `x` is also visible.

Separation checking applies only to exclusive capabilities and their read-only versions.

Any capability extending `SharedCapability` in its type is exempted

---

# Compare with Borrow Checking

Separation enforces properties similar to ownership types or borrow checking, but is built on different concepts:

 - No notation of ownership
 - No move vs copy semantics

Instead we keep track of retained capabilities and **hidden** capabilites that were widened by subsumption to an `any` instance.



---

# Definition: Interference

 - The _transitive capture set_ `tcs(c)` of a capability `c: T^C` with underlying capture set `C` is `c` itself, plus the transitive capture set of `C`.

 - The _transitive capture set_ `tcs(C)` of a capture set C is the union
   of `tcs(c)` for all elements `c` of `C`.

 - Two capture sets **interfere** if one contains an exclusive capability `x` and the other also contains `x` or contains the read-only capability `x.rd`.

 - Conversely, two capture sets are **separated** if their transitive capture sets don't interfere.

---

# Checking Statement Sequences

When a capability `x` is used in a statement sequence, we check that `{x}` is separated from the hidden sets of all previous definitions.

Example:
```scala sc:nocompile
val a: Ref^ = Ref(1)
val b: Ref^ = a
val x = a.get // error
```
Note that this check only applies when there are explicit top capabilities in play. One could very well write
```scala sc:nocompile
val a: Ref^ = Ref(1)
val b: Ref^{a} = a
val x = a.get // ok
```
---

# Checking Statement Sequences (2)

When a capability `x` is used in a statement sequence, we check that `{x}` is separated from the hidden sets of all previous definitions.

Example:
```scala sc:nocompile
val a: Ref^ = Ref(1)
val b: Ref^ = a
val x = a.get // error
```
One can also drop the explicit type of `b` and leave it to be inferred. That would not cause a separation error either.
```scala sc:nocompile
val a: Ref^ = Ref(0)
val b = a
val x = a.get // ok
```
---
# Checking Applications

To check a function application `f(e₁, ..., eₙ)`
where
```scala
def f(x₁: T₁, ..., xₙ: Tₙ)
```
we check hidden sets similar to the statement sequence
```scala
val x₁: T₁ = e₁
...
val xₙ : Tₙ = eₙ
```
except that all definitions are done **in parallel**. So each hidden set must be separated from the hidden sets of all previous **or** following definitions.

(and also the hidden sets of the function prefix and function result).

---

# Example

Given
```scala
def multiply(a: Matrix, b: Matrix, c: Matrix^): Unit
```
We reject
```scala
multiply(x, y, x)
```
since `x` is in the hidden set of formal parameter `c` and is also passed separately.
```scala
val a: Matrix = x
val b: Matrix = y
val c: Matrix^ = x  // ^ hides {x} here
```

---

# Escape Hatch

We do not report a separation error between two sets if a formal parameter's capture set explicitly names a conflicting parameter. Example:
```scala sc:nocompile
def seq(f: () => Unit; g: () ->{any, f} Unit): Unit =
  f(); g()
```
Here, the `g` parameter explicitly mentions `f` in its potential capture set.

This means that the `any` in the same capture does not hide the first argument.

Consequently, we can pass the same function twice to `seq` without violating the separation criteria:
```scala sc:nocompile
val r = Ref(1)
val plusOne = r.set(r.get + 1)
seq(plusOne, plusOne)
```

---

# Checking Types

When a type contains top capabilities we check that their hidden sets don't interfere with other parts of the same type.

Example:
```scala sc:nocompile
val b: (Ref^, Ref^) = (a, a)       // error
val c: (Ref^, Ref^{a}) = (a, a)    // error
val d: (Ref^{a}, Ref^{a}) = (a, a) // ok
```
---

# Checking Return Types

When an `any` appears in the return type of a method it means a **fresh** top capability that is different from what is known at the call site. Separation checking makes sure this is the case. For instance, the following is OK:
```scala sc:nocompile
def newRef(): Ref^ = Ref(1)
```
And so is this:
```scala sc:nocompile
def newRef(): Ref^ = { val a = Ref(1); a }
```
But the next definition would cause a separation error:
```scala sc:nocompile
val a = Ref(1)
def newRef(): Ref^ = a // error
```
---

# Checking Return Types: Parameters

The rule is that the hidden set of an `any` in a return type cannot reference exclusive or read-only capabilities defined outside of the function.

What about parameters? Here's another illegal version:
```scala sc:nocompile
def incr(a: Ref^): Ref^ =
  a.set(a.get + 1)
  a
```
This needs to be rejected because otherwise we could have set up the following bad example:
```scala sc:nocompile
val a = Ref(1)
val b: Ref^ = incr(a)
```
Here, `b` aliases `a` but does not hide it.

---
# Consume Parameters

Returning parameters in hidden in the result type is safe if the actual argument to the parameter is not used afterwards.

We can signal this by adding a `consume` modifier to a parameter.

So the following variant of `incr` is legal:
```scala sc:nocompile
def incr(consume a: Ref^): Ref^ =
  a.set(a.get + 1)
  a
```
---

# Consume Parameters: Use Cases

OK:
```scala
val a1 = Ref(1)
val a2 = incr(a1)
val a3 = incr(a2)
println(a3)
```
Error:
```scala sc:nocompile
val a4 = println(a2) // error: a2 was consumed
val a5 = incr(a1)    // error: a1 was consumed
```
Consume parameters enforce linear (more precisely: affine) access to resources.

---

# Consume Methods

The `consume` modifier can also be placed on a method.

This means that the implicit receiver parameter is consumed.

For instance, we can make a functional version `+=` of `Buffer`'s `append`:
```scala
class Buffer[T] extends Mutable:
  ...
  consume def +=(elem: T): Buffer[T]^ = { append(elem); this }
```

Then the following is OK
```scala
val buf = Buffer[Int]
val buf1 = buf += 1 += 2 += 3
```
The original `buf` is inaccessible afterwards.

---
# Freezing

We often want to create a mutable data structure like an array, initialize by assigning to its elements and then return the array as an immutable type

This can be achieved using the `freeze` wrapper.

As an example, consider a class `Arr` which is modelled after `Array` and its immutable counterpart `IArr`:

```scala sc:nocompile
class Arr[T: reflect.ClassTag](len: Int) extends Mutable:
  private val arr: Array[T] = new Array[T](len)
  def apply(i: Int): T = arr(i)
  update def update(i: Int, x: T): Unit = arr(i) = x

type IArr[T] = Arr[T]^{}
```
---
# The `freeze` wrapper

The `freeze` wrapper allows us to go from an `Arr` to an `IArr`, safely:

```scala sc:nocompile
import caps.freeze

val f: IArr[String] =
  val a = Arr[String](2)
  a(0) = "hello"
  a(1) = "world"
  freeze(a)
```

---

# Conclusion

New features in Scala 3: Read-write capabilities to mutable data and separation checking.

Formal theory in the works: **System Capybara**.

![h:350](./capybara.png)


