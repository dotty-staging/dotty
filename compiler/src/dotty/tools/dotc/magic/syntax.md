---
layout: doc-page
title: "Scala 3 Syntax Summary"
---

The following description of Scala tokens uses literal characters `‘c’` when
referring to the ASCII fragment `\u0000` – `\u007F`.

Informal descriptions are typeset as `“some comment”`.

## Lexical Syntax

The lexical syntax of Scala is given by the following grammar in EBNF form:

```ebnf
whiteSpace       ::=  ‘\u0020’ | ‘\u0009’ | ‘\u000D’ | ‘\u000A’
upper            ::=  ‘A’ | ... | ‘Z’ and any character in Unicode categories Lu, Lt or Nl,
                      and any character in Unicode categories Lo and Lm that doesn't have
                      contributory property Other_Lowercase
lower            ::=  ‘a’ | ... | ‘z’ | ‘_’ and any character in Unicode category Ll,
                      and any character in Unicode categories Lo or Lm that has contributory
                      property Other_Lowercase
letter           ::=  upper | lower
digit            ::=  ‘0’ | ... | ‘9’
binaryDigit      ::=  '0' | '1'
paren            ::=  ‘(’ | ‘)’ | ‘[’ | ‘]’ | ‘{’ | ‘}’
delim            ::=  ‘`’ | ‘'’ | ‘"’ | ‘.’ | ‘;’ | ‘,’
opchar           ::=  ‘!’ | ‘#’ | ‘%’ | ‘&’ | ‘*’ | ‘+’ | ‘-’ | ‘/’ | ‘:’ |
                      ‘<’ | ‘=’ | ‘>’ | ‘?’ | ‘@’ | ‘\’ | ‘^’ | ‘|’ | ‘~’
                      and any character in Unicode categories Sm or So
printableChar    ::=  all characters in [\u0020, \u007E] inclusive
UnicodeEscape    ::=  ‘\’ ‘u’ hexDigit hexDigit hexDigit hexDigit
                   |  `\` 'U' hexDigit hexDigit hexDigit hexDigit hexDigit hexDigit hexDigit hexDigit
hexDigit         ::=  ‘0’ | ... | ‘9’ | ‘A’ | ... | ‘F’ | ‘a’ | ... | ‘f’
charEscapeSeq    ::=  ‘\’ (‘b’ | ‘t’ | ‘n’ | ‘f’ | ‘r’ | ‘"’ | ‘'’ | ‘\’)
escapeSeq        ::=  UnicodeEscape | charEscapeSeq

symid            ::=  opchar {opchar}
varid            ::=  lower idrest
alphaid          ::=  upper idrest
                   |  varid
idrest           ::=  {letter | digit} [‘_’ symid]
op               ::=  symid
                   |  ‘`’ { charNoBackQuoteOrNewline | escapeSeq } ‘`’
plainid          ::=  alphaid | symid
id               ::=  alphaid | op
assignop         ::=  symid '='                      # but not '==', '!='

integerLiteral   ::=  (decimalNumeral | hexNumeral | binaryNumeral)
decimalNumeral   ::=  digit [{digit | ‘_’} digit]
hexNumeral       ::=  ‘0’ (‘x’ | ‘X’) hexDigit [{hexDigit | ‘_’} hexDigit]
binaryNumeral    ::=  ‘0’ (‘b’ | ‘B’) binaryDigit [{binaryDigit | ‘_’} binaryDigit]

floatingPointLiteral
                 ::=  decimalNumeral (‘.’ [decimalNumeral] [exponentPart] | exponentPart)
                   |  [decimalNumeral] '.' decimalNumeral [exponentPart]
exponentPart     ::=  (‘E’ | ‘e’) [‘+’ | ‘-’] digit [{digit | ‘_’} digit]

numericLiteral   ::=  (integerLiteral  |  floatingPointLiteral) ['j']

stringLiteral    ::=  ‘"’ {charNoDoubleQuoteOrNewline | escapeSeq} ‘"’
                   |  '\'' {{charNoSingleQuoteOrNewline | escapeSeq}} ‘\'’
                   |  ‘"""’ {charNoDoubleQuote | '\' '"'} ‘"""’
                   |  ‘\'\'\'’ {charNoSingleQuote | '\' '\''} ‘\'\'\'’

interpolatedString
                 ::=  alphaid ‘"’ {charNoDoubleQuoteOrNewline | escapeSeq} ‘"’
                   |  alphaid ‘"""’ {[‘"’] [‘"’] char \ (‘"’ | ‘\$’) | escape} {‘"’} ‘"""’
interpolatedStringPart
                 ::= printableChar \ (‘"’ | ‘$’ | ‘\’) | escape
escape           ::=  ‘{{'
                   |  ‘}}’
                   |  ‘{’ Block '}'


comment          ::=  ‘#’ “any sequence of characters up to end of line”

nl               ::=  “new line character”
semi             ::=  ‘;’ |  nl {nl}
```

## Indentation

The lexical analyzer inserts `indent` that represent regions of indented code after `:`, `=`, or `=>` at the end of a line, if the following line is indented more than the current one. A matching `dedent` token is inserted when the indentation is at most the indentation of the current line.

[at certain points](../reference/other-new-features/indentation.md).

```ebnf
<<< X >>>   ::=   ':' INDENT X DEDENT
```

type Pos = { x: Foo with x.A <: X }
1## Keywords

### Regular keywords

```
and  as  break  case  class  continue  def  derives  elif  else
end  enum  except  export  extends  extension  False  finally
for  from  if  in  is  import  match  new  not  None  object  or
private  raise  return  self  super  trait  True  try  type
uses  using  val  var  while  with
->  =>  ?->  ?=>  <:  >:  :=  '@'
```

### Soft keywords

```
consume  erased  exclude  initially  inline  into  lazy  only
opaque  open  override  rd  sealed  tracked  transparent  update  witness
*  +  -  ==  !=  <  >  <=  >=  ?  ^
```

See the [separate section on soft keywords](../reference/soft-modifier.md) for additional
details on where a soft keyword is recognized.

## Context-free Syntax

The context-free syntax of Scala is given by the following EBNF
grammar:

### Literals and SimpleRefs
```ebnf
Literal        ::=  [‘-’] numericLiteral
                 |  stringLiteral
                 |  'True'
                 |  'False'
                 |  'None'

QualId            ::=  id {‘.’ id}

BoundName         ::=  plainid | '_'

ClassQualifier    ::=  ‘[’ id ‘]’
SimpleRef         ::=  ['super' [ClassQualifier] '.'] id
Self              ::=  'self' [ClassQualifier]
PathRef           ::=  SimpleRef {'.' id}
Singleton         ::=  (SimpleRef | Self) {'.' id}
```

### Types
```ebnf
Type              ::=  FunType
                    |  MatchType
                    |  InfixType
FunType           ::=  ParamOfFunType (Arrow | ContextArrow) Type
                    |  TypeParams '->' Type
MatchType         ::=  'match' InfixType ':' TypeCaseClause { TypeCaseClause }
TypeCaseClause    ::=  ‘case’ (InfixType | ‘_’) ':' Type
InfixType         ::=  CapType {op CapType}
CapType           ::=  AnnotType ['^' [CapSet]]
AnnotType         ::=  SimpleType {Annotation}
SimpleType        ::=  PathRef [TypeArgs]
                    |  ‘(’ [Types] ‘)’
                    |  ‘(’ NameAndType {‘,’ NameAndType} ‘)’
                    |  '{' Singleton | Literal | Refined '}'
                    |  SimpleType '?'
                    |  INDENT Type DEDENT
Refined           ::=  BoundName ':' Type 'with' Refinements
Refinements       ::=  Dcl | Dcls
TypeArgs          ::=  ‘[’ TypeArg {‘,’ TypeArg} ‘]’
TypeArg           ::=  [plainid '='] (TypeOrCapSet  |  (‘?’ | '_') TypeBounds)
TypeOrCapSet      ::=  Type  |  CapSet
TypeBounds        ::=  [‘>:’ TypeOrCapSet] [‘<:’ TypeOrCapSet]
Types             ::=  Type {‘,’ Type}
NameAndType       ::=  plainid ':' Type

CapSet            ::=  '{' CapRef {',' CapRef} '}'
CapRef            ::=  (SimpleRef | Self) [OnlyFilter] {ExcludeFilter} ['.' 'rd']
                    |  'rd'
OnlyFilter        ::=  '.' 'only' '[' QualId ']'
ExcludeFilter     ::=  '.' 'exclude' '[' QualId ']'

Arrow             ::=  ‘=>’  |  ‘->’ [CapSet]
ContextArrow      ::=  ‘?=>’ |  ‘?->’ [CapSet]
```

### Expressions


```ebnf
SuiteExpr         ::=  Suite
                    |  Expr
Suite             ::=  `:` Expr                     # if to end of line
                    |  `:` {ParamPrefix} INDENT Block DEDENT
SuiteIfOuter      ::=  Suite                        # if ':' is not in follow set
Expr              ::=  ParamPrefix Expr
                    |  Expr1
ParamPrefix       ::=  FunParams (‘=>’ | ‘?=>’)
                    |  TypeParams ‘=>’
Expr1             ::=  [‘inline’] ‘if’ Conditional
                    |  ‘while’ SuiteExpr Suite
                    |  ‘try’ SuiteExpr
                       ( Except {Except} [‘finally’ SuiteExpr]
                       | ‘finally’ SuiteExpr )
                    |  'raise' SuiteExpr ['from' SuiteExpr]
                    |  'return' [SuiteExpr]
                    |  ForPrefix Suite
                    |  'with' Expr2 ['as' plainid] Suite
                    |  MatchExpr
                    |  {Assignee}_',' (':= | assignop) Expr
                    |  Expr2
Conditional       ::=  SuiteExpr Suite ['else' SuiteExpr | 'elif' Conditional]
Except            ::=  'except' [InfixType ['as' plainid]] Suite
MatchExpr         ::=  ['inline'] 'match' SuiteExpr ':' CaseClauses
CaseClauses       ::=  CaseClause { CaseClause }
CaseClause        ::=  ‘case’ Pattern [Guard] (Suite | 'if' MatchExpr)
Guard             ::=  'if' OrExpr
Assignee          ::=  [SimpleExpr '.'] id
                    |  SimpleExpr Arguments
Expr2             ::=  OrExpr ['if' OrExpr 'else' Expr2]
OrExpr            ::=  AndExpr {'or' (AndExpr | SuiteIfOuter)}
AndExpr           ::=  NotExpr {'and' (NotExpr | SuiteIfOuter)}
NotExpr           ::=  Comparison  |  'not' (Comparison | SuiteIfOuter)
Comparison        ::=  InfixExpr {CompOp (InfixExpr | SuiteIfOuter)}
CompOp            ::=  '<' | '>' | '==' | '>=' | '<=' | '!=' | 'in' | 'not' 'in' | 'is' | 'is' 'not'
InfixExpr         ::=  PrefixExpr {op (PrefixExpr | SuiteIfOuter)}
PrefixExpr        ::=  [PrefixOperator] SimpleExpr
PrefixOperator    ::=  ‘-’ | ‘+’ | ‘~’
SimpleExpr        ::=  SimpleRef
                    |  Self
                    |  Literal
                    |  interpolatedString
                    |  ‘_’
                    |  ‘new’ [ConstrApp] [TemplateBody]
                    |  ‘(’ [ExprsInParens] ‘)’
                    |  ‘(’ [NamedExprsInParens] ‘)’
                    |  '[' [ListExpr] ']'
                    |  '{' [ListExpr] '}'
                    |  '{' [DictExpr] '}'
                    |  SimpleExpr ‘.’ id
                    |  SimpleExpr '?'
                    |  SimpleExpr TypeArgs
                    |  SimpleExpr SuiteIfOutermost
                    |  SimpleExpr Arguments
                    |  INDENT Block DEDENT

ExprsInParens     ::=  ExprInParens {‘,’ ExprInParens}
ExprInParens      ::=  Expr [‘:’ (Type  |  Annotation)]
NamedExprsInParens ::= NamedExprInParens {‘,’ NamedExprInParens}
NamedExprInParens ::=  plainid '=' ExprInParens
Arguments         ::=  ‘(’ ([{Argument}_','] | ‘using’ {Argument}_',') ‘)’
Argument          ::=  [plainid '=']('*' SimpleExpr | ExprInParens Generator)
ConstrApp         ::=  SimpleType {Arguments} {Annotations}
ListExpr          ::=  Expr ({',' Expr}  |  Generator)
DictExpr          ::=  DictItem ({',' DictItem}  |  Generator)
DictItem          ::=  Expr ':' Expr
Generator         ::=  ForPrefix [GeneratorRest]
ForPrefix         ::=  for' ['case'] SinglePattern 'in' OrExpr
GeneratorRest     ::=  Generator
                    |  'if' SuiteExpr [GeneratorRest]
```
### Patterns

```ebnf
Pattern           ::=  SinglePattern { ‘|’ SinglePattern }
SinglePattern     ::=  InfixPattern ['as' plainid]
InfixPattern      ::=  SimplePattern { op SimplePattern }
SimplePattern     ::=  PatVar
                    |  Literal
                    |  Self
                    |  interpolatedString(Pattern)
                    |  `(' [Patterns | NamedPatterns] `)'
                    |  '[' SplicePatterns ']'
                    |  PathRef [TypeArgs] [PatternArguments]
PatVar            ::=  varid  |  ‘_’
PatternInParens   ::=  Pattern
                    |  ['witness'] PatVar ‘:’ Type
                    |  [‘-’] numericLiteral ‘:’ Type
Patterns          ::=  {PatternInParens}_','
PatternArguments  ::=  '(' [SplicePatterns | NamedPatterns] ')'
SplicePatterns    ::=  Patterns
                    |  [Patterns ‘,’] PatVar ‘*’ [‘,’ Patterns]
NamedPatterns     ::=  {NamedPattern}_','
NamedPattern      ::=  plainid '=' PatternInParens
```

### Modifiers and Annotations

```ebnf
Modifier          ::=  AccessModifier
                    |  LocalModifier
                    |  'lazy'
                    |  ‘opaque’
                    |  ‘override’
AccessModifier    ::=  'private' [ClassQualifier]
LocalModifier     ::=  ‘sealed’
                    |  ‘open’
                    |  ‘witness’
                    |  ‘inline’
                    |  ‘transparent’
                    |  'into'
                    |  ‘erased’
                    |  ‘update’
                    |  ‘consume’
DefParamModifier  ::=  'inline'
                    |  'erased'
                    |  'consume'
ClsParamModifier  ::=  AccessModifier
                    |  DefParamModifier

Annotation        ::=  ‘@’ SimpleTypeConstr [TypeArgs] {Arguments}
```

### Type and Value Parameters

```ebnf
TypeParams        ::=  ‘[’ TypeParam {‘,’ TypeParam} ‘]’
TypeParam         ::=  {Annotation} [‘+’ | ‘-’] plainid [`^`] TypeAndCtxBounds ['=' Type]

ParamsOfFunType   ::=  InfixType
                    |  ‘(’ [ ContextTypes | {FunParam}_',' ] ‘)’
DefParamClause    ::=  TypeParams
                    |  DefParams
DefParams         ::=  '(' {DefParam}_',' ')'  |  UsingParams
UsingParams       ::=  '(' 'using' (ContextTypes | {DefParam}_',') ')'
ClsParams         ::=  '(' {ClsParam}_',' ')'
                    |  '(' 'using' (ContextTypes | {ClsParam}_',') ')'
ConstrParams      ::=  DefParams  |  UsingParams

DefParam          ::=  {Annotation} {DefParamModifier} Param
ClsParam          ::=  {Annotation} {ClsParamModifier} ['val' | 'var'] Param
Param             ::=  plainid ':' ParamTypeSpread ['=' Expr]
ParamType         ::=  [Arrow] Type
ParamTypeSpread   ::=  [Arrow] (['*'] Type)
ContextTypes      ::=  {ParamType}_','

FunParams         ::=  ‘(’ {Binding}_‘,’ ‘)’
                    |  BoundName
Binding           ::=  ['erased'] BoundName [‘:’ ParamType]

TypeAndCtxBounds  ::=  TypeBounds [‘:’ ContextBounds]
ContextBounds     ::=  ContextBound
                    |  '{' ContextBound {',' ContextBound} '}'
ContextBound      ::=  Type ['as' plainid]
```

### Definitions

```ebnf

Dcl               ::=  ‘val’ ValDcl
                    |  ‘var’ ValDcl
                    |  ‘def’ DefDcl
                    |  ‘type’ TypeDef
ValDcl            ::=  plainid ‘:’ Type
DefDcl            ::=  plainid {DefParamClause} ‘:’ Type

Def               ::=  'val' ValDef
                    |  ‘var’ VarDef
                    |  ‘def’ DefDef
                    |  ‘type’ TypeDef
                    |  ['case'] ‘class’ ClassDef
                    |  ['case'] ‘object’ ObjectDef
                    |  ‘trait’ ClassDef
                    |  ‘enum’ EnumDef
ValDef            ::=  BoundName [‘:’ Type] ‘=’ Expr
                    |  SinglePattern ‘=’ Expr
VarDef            ::=  BoundName [‘:’ Type] ‘:=’ Expr
                    |  SinglePattern ‘:=’ Expr
DefDef            ::=  plainid {DefParamClause} [‘:’ Type] ‘=’ Expr
TypeDef           ::=  plainid [TypeParams] TypeAndCtxBounds [‘=’ Type]
                    |  plainid `^` TypeAndCtxBounds [‘=’ TypeOrCapSet]
ClassDef          ::=  plainid ClassConstr [Template]
ObjectDef         ::=  plainid [Template]
EnumDef           ::=  plainid ClassConstr InheritClauses EnumBody

SimpleValDef      ::=  BoundName '=' Expr

ClassConstr       ::=  [TypeParams] {Annotation} [AccessModifier] {ClsParamClause}

Template          ::=  InheritClauses [TemplateBody]
InheritClauses    ::=  [‘extends’ {ConstrApp}_',']
                       [‘derives’ {QualId}_‘,’]
                       [‘uses’ {UseRef}_‘,’]
UseRef            ::=  CapRef [‘initially’]
```

### Statements

```ebnf
CommonStat        ::=  Import
                    |  Extension
                    |  EndMarker
                    |  TrailingDocstring
                    |  SimpleValDef
                    |  Expr
BlockStat         ::=  CommonStat
                    |  {Annotation [nl]} {LocalModifier} Def
                    |  'if' Check
                    |  'break'
                    |  'continue'
TemplateStat      ::=  CommonStat
                    |  {Annotation [nl]} {Modifier} (Def | Dcl)
                    |  Export
                    |  'val' SelfDef
SelfDef           ::=  'self' ':' Type

Import            ::=  ‘from’ ImportSource 'import' ImportSelectors
                    |  'import' ImportSource Renaming
Export            ::=  ‘from’ ImportSource 'export' ImportSelectors
                    |  'export' ImportSource Renaming
ImportSource      ::=  PathRef
                    |  '.' QualId
ImportSelectors   ::=  {id Renaming ','} (id Renaming | '*')
Renaming          ::=  ['as' BoundName]

Check             ::=  SuiteExpr ['else' SuiteExpr]
Extension         ::=  ‘extension’ [TypeParamClause] {UsingParamClause}
                       ‘(’ DefParam ‘)’ {UsingParamClause} ExtMethods
ExtMethods        ::=  ExtMethod | ':" INDENT {ExtMethod}_semi DEDENT
ExtMethod         ::=  {Annotation [nl]} {Modifier} ‘def’ DefDef
                    |  Export
EndMarker         ::=  ‘end’ plainid
TrailingDocstring ::=  stringLiteral

EnumStat          ::=  TemplateStat
                    |  {Annotation [nl]} {Modifier} EnumCase
EnumCase          ::=  ‘case’ plainid ClassConstr [‘extends’ {ConstrApp_‘,’}]
                    |  'case' {plainid}_','
```

### Scopes

```ebnf
Block             ::=  BlockStats {BlockStat}_{semi}
                    |  CaseClauses
TemplateBody      ::=  ':' INDENT {TemplateStat}_semi DEDENT
EnumBody          ::=  ':' INDENT {EnumStat}_semi DEDENT
Dcls              ::=  ':' INDENT {Dcl}_semi DEDENT
CompilationUnit   ::=  {TemplateStat}_semi
```


### Predefined Functions

  pass
  assert