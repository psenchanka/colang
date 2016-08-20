package colang.ast.parsed.expression

import colang.Issue
import colang.ast.parsed._
import colang.ast.raw.{expression => raw}

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
}

object Expression {
  def analyze(implicit scope: Scope, rawExpr: raw.Expression): (Expression, Seq[Issue]) = {
    rawExpr match {
      case r: raw.DoubleLiteral => DoubleLiteral.analyze(r)
      case r: raw.IntLiteral => IntLiteral.analyze(r)
      case r: raw.SymbolReference => SymbolReference.analyze(r)
      case r: raw.FunctionCall => FunctionCall.analyze(r)
      case r: raw.InfixOperator => InfixOperator.analyze(r)
    }
  }
}