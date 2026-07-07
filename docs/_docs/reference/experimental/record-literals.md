---
layout: doc-page
title: "Record Literals"
nightlyOf: https://docs.scala-lang.org/scala3/reference/experimental/record-literals.html
---

## Introduction

Record literals are an experimental feature enabled with the language import

```scala
import language.experimental.recordLiterals
```

A named tuple literal whose expected type is a case class `C` is interpreted
as a constructor call `C(f1 = v1, ..., fn = vn)`:

```scala
case class Developer(id: String, name: String, url: String = "")

val dev: Developer = (id = "jamie", name = "Jamie Thompson")
// elaborates to Developer(id = "jamie", name = "Jamie Thompson")
```

Because the elaboration is an ordinary named-argument call of the case class
companion's `apply`:

- default arguments apply — fields with defaults can be omitted, which named
  tuples by themselves cannot offer;
- overloads, implicit conversions on arguments, and context parameters behave
  as at any call site;
- error messages point at individual fields.

Record literals compose with
[collection literals](./collection-literals.md): a field of type
`Seq[Developer]` gives an inner `[...]` its expected type, and each element's
expected type `Developer` makes a nested named tuple a record literal:

```scala
import language.experimental.collectionLiterals

case class Project(name: String, developers: Seq[Developer] = Nil)

val proj: Project = (
  name = "example",
  developers = [(id = "a", name = "A"), (id = "b", name = "B")]
)
```

## Restrictions

- This is an interpretation of *literals* only. There is no subtyping and no
  implicit conversion between named tuples and case classes; a named tuple
  *value* does not convert to a case class.
- Positional tuples do not convert to case classes. The field names are what
  make the form safe.
- With no expected type, or with a named tuple expected type, a named tuple
  literal is a named tuple, exactly as without the feature.
- There is no empty record literal. `()` is the Unit value, and there is no
  named tuple literal of arity zero, so the rule has no empty case to
  interpret: constructing a case class with no fields, or with every field
  defaulted, is written `C()`. This is deliberate — a record literal earns
  its conciseness by trading the class name for field names, and at arity
  zero the class name is the only information left. Any nonempty subset of
  fields works, with defaults filling the rest. A `()` written at a case
  class type with a fully-defaulted constructor gets an error note pointing
  at the `C()` spelling.

## Composition with companion scope inference

Because a record literal elaborates to a named-argument constructor call,
every field position carries the constructor parameter's type as expected
type — which is what
[companion scope inference](./companion-scope-inference.md) (SIP-80) consumes.
Under both features, enum cases and companion members inside a record literal
need no qualification, recursively:

```scala
import language.experimental.{collectionLiterals, recordLiterals, companionScopeInference}

enum Geometry:
  case Circle, Rectangle, Triangle
enum Color:
  case Red, Green, Blue
case class Shape(geometry: Geometry, color: Color = Color.Red)

val shape: Shape = (geometry = Circle)  // default color applies
val shapes: Vector[Shape] = [(geometry = Circle, color = Blue), (geometry = Triangle)]
```

## Relationship to the Pre-SIP discussions

This is the interpretation that emerged at the end of the
[aggregate literals Pre-SIP](https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-aggregate-literals/6697)
and was refined in the
[collection literals Pre-SIP](https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990):
a record literal trades one class name for *n* field names, so — unlike the
rejected bracket syntax for arbitrary `apply` methods — the notation does not
erase names.
