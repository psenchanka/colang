package colang.ast.parsed.expression

import colang.ast.parsed._
import colang.ast.parsed.statement.Statement
import colang.ast.raw.{expression => raw}
import colang.issues.{Issue, Terms}

/**
  * Represents a code fragment that can be evaluated.
  */
trait Expression extends Statement {

  /**
    * Produced value type.
    */
  def type_ : Type
}

/**
  * Represents an expression that failed to parse.
  * @param scope enclosing scope
  */
case class InvalidExpression(implicit scope: Scope) extends Expression {
  val type_ = scope.root.unknownType
  val rawNode = None
}

object Expression {
  def analyze(rawExpr: raw.Expression)(implicit scope: Scope, localContext: LocalContext): (Expression, Seq[Issue]) = {
    rawExpr match {
      case r: raw.ParenthesesExpression => analyze(r.expression)

      case r: raw.IntLiteral => IntLiteral.analyze(r)
      case r: raw.DoubleLiteral => DoubleLiteral.analyze(r)
      case r: raw.BoolLiteral => BoolLiteral.analyze(r)

      case r: raw.SymbolReference => SymbolReference.analyze(r)
      case r: raw.ThisReference => ThisReference.analyze(r)
      case r: raw.FunctionCall => FunctionCall.analyze(r)
      case r: raw.MemberAccess => MemberAccess.analyze(r)
        
      case r: raw.InfixOperator => Operator.analyze(r)
      case r: raw.PrefixOperator => Operator.analyze(r)

      case r: raw.TypeReferencing => Type.analyzeReference(r)
    }
  }

  def analyzeInNonLocalContext(rawExpr: raw.Expression)(implicit scope: Scope): (Expression, Seq[Issue]) = {

    // We use the most possibly safe stub here.
    implicit val localContext = LocalContext(
      applicableKind = Terms.Constructor,
      expectedReturnType = None)

    analyze(rawExpr)
  }
}
