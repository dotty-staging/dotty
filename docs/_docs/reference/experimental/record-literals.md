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

## Relationship to the Pre-SIP discussions

This is the interpretation that emerged at the end of the
[aggregate literals Pre-SIP](https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-aggregate-literals/6697)
and was refined in the
[collection literals Pre-SIP](https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990):
a record literal trades one class name for *n* field names, so — unlike the
rejected bracket syntax for arbitrary `apply` methods — the notation does not
erase names.
