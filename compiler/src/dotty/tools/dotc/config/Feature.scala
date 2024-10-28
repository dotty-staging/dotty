package dotty.tools
package dotc
package config

import core.*
import Contexts.*, Symbols.*, Names.*
import StdNames.nme
import Decorators.*
import util.{SrcPos, NoSourcePosition}
import SourceVersion.*
import reporting.Message
import NameKinds.QualifiedName
import Annotations.ExperimentalAnnotation
import Settings.Setting.ChoiceWithHelp

object Feature:

  def experimental(str: PreName): TermName =
    QualifiedName(nme.experimental, str.toTermName)

  private def deprecated(str: PreName): TermName =
    QualifiedName(nme.deprecated, str.toTermName)

  private val namedTypeArguments = experimental("namedTypeArguments")
  private val genericNumberLiterals = experimental("genericNumberLiterals")
  val scala2macros = experimental("macros")

  val dependent = experimental("dependent")
  val erasedDefinitions = experimental("erasedDefinitions")
  val symbolLiterals = deprecated("symbolLiterals")
  val fewerBraces = experimental("fewerBraces")
  val saferExceptions = experimental("saferExceptions")
  val clauseInterleaving = experimental("clauseInterleaving")
  val pureFunctions = experimental("pureFunctions")
  val captureChecking = experimental("captureChecking")
  val into = experimental("into")
  val modularity = experimental("modularity")
  val tracked = experimental("tracked")
  val betterMatchTypeExtractors = experimental("betterMatchTypeExtractors")
  val quotedPatternsWithPolymorphicFunctions = experimental("quotedPatternsWithPolymorphicFunctions")
  val betterFors = experimental("betterFors")

  def experimentalAutoEnableFeatures(using Context): List[TermName] =
    defn.languageExperimentalFeatures
      .map(sym => experimental(sym.name))
      .filterNot(_ == captureChecking) // TODO is this correct?

  val values = List(
    (nme.help, "Display all available features"),
    (nme.noAutoTupling, "Disable automatic tupling"),
    (nme.dynamics, "Allow direct or indirect subclasses of scala.Dynamic"),
    (nme.unsafeNulls, "Enable unsafe nulls for explicit nulls"),
    (nme.postfixOps, "Allow postfix operators (not recommended)"),
    (nme.strictEquality, "Enable strict equality (disable canEqualAny)"),
    (nme.implicitConversions, "Allow implicit conversions without warnings"),
    (nme.adhocExtensions, "Allow ad-hoc extension methods"),
    (namedTypeArguments, "Allow named type arguments"),
    (genericNumberLiterals, "Allow generic number literals"),
    (scala2macros, "Allow Scala 2 macros"),
    (dependent, "Allow dependent method types"),
    (erasedDefinitions, "Allow erased definitions"),
    (symbolLiterals, "Allow symbol literals"),
    (fewerBraces, "Enable support for using indentation for arguments"),
    (saferExceptions, "Enable safer exceptions"),
    (clauseInterleaving, "Enable clause interleaving"),
    (pureFunctions, "Enable pure functions for capture checking"),
    (captureChecking, "Enable experimental capture checking"),
    (into, "Allow into modifier on parameter types"),
    (modularity, "Enable experimental modularity features"),
    (tracked, "Enable tracked modifier"),
    (betterMatchTypeExtractors, "Enable better match type extractors"),
    (betterFors, "Enable improvements in `for` comprehensions")
  )

  // legacy language features from Scala 2 that are no longer supported.
  val legacyFeatures = List(
    "higherKinds",
    "existentials",
    "reflectiveCalls"
  )

  private def enabledLanguageFeaturesBySetting(using Context): List[String] =
    ctx.settings.language.value.asInstanceOf

  /** Is `feature` enabled by by a command-line setting? The enabling setting is
   *
   *       -language:<prefix>feature
   *
   *  where <prefix> is the fully qualified name of `owner`, followed by a ".",
   *  but subtracting the prefix `scala.language.` at the front.
   */
  def enabledBySetting(feature: TermName)(using Context): Boolean =
    enabledLanguageFeaturesBySetting.contains(feature.toString)

  /** Is `feature` enabled by by an import? This is the case if the feature
   *  is imported by a named import
   *
   *       import owner.feature
   *
   *  and there is no visible nested import that excludes the feature, as in
   *
   *       import owner.{ feature => _ }
   */
  def enabledByImport(feature: TermName)(using Context): Boolean =
    //atPhase(typerPhase) {
      val info = ctx.importInfo
      info != null && info.featureImported(feature)
    //}

  /** Is `feature` enabled by either a command line setting or an import?
   *  @param  feature   The name of the feature
   *  @param  owner     The prefix symbol (nested in `scala.language`) where the
   *                    feature is defined.
   */
  def enabled(feature: TermName)(using Context): Boolean =
    enabledBySetting(feature) || enabledByImport(feature) || feature == tracked

  /** Is auto-tupling enabled? */
  def autoTuplingEnabled(using Context): Boolean = !enabled(nme.noAutoTupling)

  def dynamicsEnabled(using Context): Boolean = enabled(nme.dynamics)

  def dependentEnabled(using Context) = enabled(dependent)

  def namedTypeArgsEnabled(using Context) = enabled(namedTypeArguments)

  def clauseInterleavingEnabled(using Context) =
    sourceVersion.isAtLeast(`3.6`) || enabled(clauseInterleaving)

  def betterForsEnabled(using Context) = enabled(betterFors)

  def trackedEnabled(using Context) = enabled(tracked) || enabled(modularity)

  def genericNumberLiteralsEnabled(using Context) = enabled(genericNumberLiterals)

  def scala2ExperimentalMacroEnabled(using Context) = enabled(scala2macros)

  def quotedPatternsWithPolymorphicFunctionsEnabled(using Context) =
    enabled(quotedPatternsWithPolymorphicFunctions)

  /** Is pureFunctions enabled for this compilation unit? */
  def pureFunsEnabled(using Context) =
    enabledBySetting(pureFunctions)
    || ctx.compilationUnit.knowsPureFuns
    || ccEnabled

  /** Is captureChecking enabled for this compilation unit? */
  def ccEnabled(using Context) =
    enabledBySetting(captureChecking)
    || ctx.compilationUnit.needsCaptureChecking

  /** Is pureFunctions enabled for any of the currently compiled compilation units? */
  def pureFunsEnabledSomewhere(using Context) =
    enabledBySetting(pureFunctions)
    || ctx.run != null && ctx.run.nn.pureFunsImportEncountered
    || ccEnabledSomewhere

  /** Is captureChecking enabled for any of the currently compiled compilation units? */
  def ccEnabledSomewhere(using Context) =
    if ctx.run != null then ctx.run.nn.ccEnabledSomewhere
    else enabledBySetting(captureChecking)

  def sourceVersionSetting(using Context): SourceVersion =
    SourceVersion.valueOf(ctx.settings.source.value)

  def sourceVersion(using Context): SourceVersion =
    ctx.compilationUnit.sourceVersion match
      case Some(v) => v
      case none => sourceVersionSetting

  def migrateTo3(using Context): Boolean =
    sourceVersion == `3.0-migration`

  def fewerBracesEnabled(using Context) =
    sourceVersion.isAtLeast(`3.3`) || enabled(fewerBraces)

  /** If current source migrates to `version`, issue given warning message
   *  and return `true`, otherwise return `false`.
   */
  def warnOnMigration(msg: Message, pos: SrcPos, version: SourceVersion)(using Context): Boolean =
    if sourceVersion.isMigrating && sourceVersion.stable == version
       || (version == `3.0` || version == `3.1`) && migrateTo3
    then
      report.migrationWarning(msg, pos)
      true
    else
      false

  def checkExperimentalFeature(which: String, srcPos: SrcPos, note: => String = "")(using Context) =
    if !isExperimentalEnabled then
      report.error(experimentalUseSite(which) + note, srcPos)

  private def ccException(sym: Symbol)(using Context): Boolean =
    ccEnabled && defn.ccExperimental.contains(sym)

  def checkExperimentalDef(sym: Symbol, srcPos: SrcPos)(using Context) =
    val experimentalSym =
      if sym.hasAnnotation(defn.ExperimentalAnnot) then sym
      else if sym.owner.hasAnnotation(defn.ExperimentalAnnot) then sym.owner
      else NoSymbol
    if !isExperimentalEnabled && !ccException(experimentalSym) then
      val msg =
        experimentalSym.getAnnotation(defn.ExperimentalAnnot).map {
          case ExperimentalAnnotation(msg) if msg.nonEmpty => s": $msg"
          case _ => ""
        }.getOrElse("")
      val markedExperimental =
        if experimentalSym.exists
        then i"$experimentalSym is marked @experimental$msg"
        else i"$sym inherits @experimental$msg"
      report.error(markedExperimental + "\n\n" + experimentalUseSite("definition"), srcPos)

  private def experimentalUseSite(which: String): String =
    s"""Experimental $which may only be used under experimental mode:
       |  1. in a definition marked as @experimental, or
       |  2. an experimental feature is imported at the package level, or
       |  3. compiling with the -experimental compiler flag.
       |""".stripMargin

  def isExperimentalEnabled(using Context): Boolean =
    ctx.settings.experimental.value ||
    experimentalAutoEnableFeatures.exists(enabled)

  def experimentalEnabledByLanguageSetting(using Context): Option[TermName] =
    experimentalAutoEnableFeatures.find(enabledBySetting)

  def isExperimentalEnabledByImport(using Context): Boolean =
    experimentalAutoEnableFeatures.exists(enabledByImport)

  /** Handle language import `import language.<prefix>.<imported>` if it is one
   *  of the global imports `pureFunctions` or `captureChecking`. In this case
   *  make the compilation unit's and current run's fields accordingly.
   *  @return true iff import that was handled
   */
  def handleGlobalLanguageImport(prefix: TermName, imported: Name)(using Context): Boolean =
    val fullFeatureName = QualifiedName(prefix, imported.asTermName)
    if fullFeatureName == pureFunctions then
      ctx.compilationUnit.knowsPureFuns = true
      if ctx.run != null then ctx.run.nn.pureFunsImportEncountered = true
      true
    else if fullFeatureName == captureChecking then
      ctx.compilationUnit.needsCaptureChecking = true
      if ctx.run != null then ctx.run.nn.ccEnabledSomewhere = true
      true
    else
      false
end Feature
