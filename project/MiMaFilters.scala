
import com.typesafe.tools.mima.core._
import com.typesafe.tools.mima.core.ProblemFilters._

object MiMaFilters {
  val Library: Seq[ProblemFilter] = Seq(
    // New APIs marked @experimental in 3.0.1
    exclude[DirectMissingMethodProblem]("scala.quoted.Quotes#reflectModule.TypedTree"),
    exclude[DirectMissingMethodProblem]("scala.quoted.Quotes#reflectModule.TypedTreeMethods"),
    exclude[DirectMissingMethodProblem]("scala.quoted.Quotes#reflectModule.TypedTreeTypeTest"),
    exclude[DirectMissingMethodProblem]("scala.quoted.Quotes#reflectModule.Wildcard"),
    exclude[DirectMissingMethodProblem]("scala.quoted.Quotes#reflectModule.WildcardTypeTest"),
    exclude[DirectMissingMethodProblem]("scala.quoted.Quotes#reflectModule#SymbolMethods.fieldMember"),
    exclude[DirectMissingMethodProblem]("scala.quoted.Quotes#reflectModule#SymbolMethods.fieldMembers"),
    exclude[DirectMissingMethodProblem]("scala.quoted.Quotes#reflectModule#SymbolMethods.methodMember"),
    exclude[DirectMissingMethodProblem]("scala.quoted.Quotes#reflectModule#SymbolMethods.methodMembers"),
    exclude[DirectMissingMethodProblem]("scala.quoted.Quotes#reflectModule#SymbolMethods.typeMember"),
    exclude[DirectMissingMethodProblem]("scala.quoted.Quotes#reflectModule#SymbolMethods.typeMembers"),
    exclude[DirectMissingMethodProblem]("scala.quoted.Quotes#reflectModule#TermParamClauseMethods.isErased"),
    exclude[MissingClassProblem]("scala.annotation.experimental"),
    exclude[MissingClassProblem]("scala.annotation.internal.ErasedParam"),
    exclude[MissingClassProblem]("scala.quoted.Quotes$reflectModule$TypedTreeMethods"),
    exclude[MissingClassProblem]("scala.quoted.Quotes$reflectModule$TypedTreeModule"),
    exclude[MissingClassProblem]("scala.quoted.Quotes$reflectModule$WildcardModule"),
    exclude[ReversedMissingMethodProblem]("scala.quoted.Quotes#reflectModule.TypedTree"),
    exclude[ReversedMissingMethodProblem]("scala.quoted.Quotes#reflectModule.TypedTreeMethods"),
    exclude[ReversedMissingMethodProblem]("scala.quoted.Quotes#reflectModule.TypedTreeTypeTest"),
    exclude[ReversedMissingMethodProblem]("scala.quoted.Quotes#reflectModule.Wildcard"),
    exclude[ReversedMissingMethodProblem]("scala.quoted.Quotes#reflectModule.WildcardTypeTest"),
    exclude[ReversedMissingMethodProblem]("scala.quoted.Quotes#reflectModule#SymbolMethods.fieldMember"),
    exclude[ReversedMissingMethodProblem]("scala.quoted.Quotes#reflectModule#SymbolMethods.fieldMembers"),
    exclude[ReversedMissingMethodProblem]("scala.quoted.Quotes#reflectModule#SymbolMethods.methodMember"),
    exclude[ReversedMissingMethodProblem]("scala.quoted.Quotes#reflectModule#SymbolMethods.methodMembers"),
    exclude[ReversedMissingMethodProblem]("scala.quoted.Quotes#reflectModule#SymbolMethods.typeMember"),
    exclude[ReversedMissingMethodProblem]("scala.quoted.Quotes#reflectModule#SymbolMethods.typeMembers"),
    exclude[ReversedMissingMethodProblem]("scala.quoted.Quotes#reflectModule#TermParamClauseMethods.isErased"),
  )
}
