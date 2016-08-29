package colang.ast.raw.statement

import colang.Strategy.Result
import colang.Strategy.Result.{NoMatch, Success}
import colang.ast.raw.ParserImpl._
import colang.ast.raw._
import colang.ast.raw.expression.Expression
import colang.issues.Issues
import colang.tokens.{Assign, Comma, Identifier}
import colang.{SourceCode, TokenStream}

/**
  * Represents a single variable declaration site.
  * @param name variable name
  * @param initializer optional initializer expression
  */
case class VariableDefinition(name: Identifier, initializer: Option[Expression]) extends Node {
  lazy val source: SourceCode = {
    initializer match {
      case Some(init) => name.source + init.source
      case None => name.source
    }
  }
}

object VariableDefinition {
  val strategy = new ParserImpl.Strategy[VariableDefinition] {

    def apply(stream: TokenStream): Result[TokenStream, VariableDefinition] = {
      ParserImpl.parseGroup()
        .element(identifierStrategy,                   "variable name",        stopIfAbsent = true)
        .element(SingleTokenStrategy(classOf[Assign]), "'='",                  optional = true, stopIfAbsent = true)
        .element(Expression.strategy,                  "variable initializer", optional = true)
        .parse(stream)
        .as[Identifier, Assign, Expression] match {

        case (Present(name), Present(_), Present(initializer), issues, streamAfterVariable) =>
          Success(VariableDefinition(name, Some(initializer)), issues, streamAfterVariable)

        case (Present(name), Present(assign), Invalid(), issues, streamAfterVariable) =>
          Success(VariableDefinition(name, None), issues, streamAfterVariable)

        case (Present(name), Present(assign), Absent(), issues, streamAfterVariable) =>
          val issue = Issues.MissingVariableInitializer(assign.source.after, name.value)
          Success(VariableDefinition(name, None), issues :+ issue, streamAfterVariable)

        case (Present(name), Absent(), _, issues, streamAfterVariable) =>
          Success(VariableDefinition(name, None), issues, streamAfterVariable)

        case _ => NoMatch()
      }
    }
  }
}

/**
  * Represents a variable(s) declaration statement, which may also appear at the top-level.
  * @param type_ variable(s) type
  * @param variables individual variable definitions
  */
case class VariablesDefinition(type_ : Type, variables: Seq[VariableDefinition]) extends Statement
                                                                                 with GlobalSymbolDefinition {
  def source: SourceCode = type_.source + variables.last.source
}

object VariablesDefinition {
  val strategy = new ParserImpl.Strategy[VariablesDefinition] {

    private case class VariableDefinitionSequence(variables: ::[VariableDefinition]) extends Node {
      def source = variables.head.source + variables.last.source
    }

    private val varsStrategy = new ParserImpl.Strategy[VariableDefinitionSequence] {
      def apply(stream: TokenStream): Result[TokenStream, VariableDefinitionSequence] = {
        ParserImpl.parseSequence(
          stream = stream,
          elementStrategy = VariableDefinition.strategy,
          elementDescription = "variable definition",
          mandatorySeparator = Some(classOf[Comma]),
          separatorDescription = "comma"
        ) match {
          case (variables, issues, streamAfterVariables) if variables.nonEmpty =>
            Success(VariableDefinitionSequence(variables.toList.asInstanceOf[::[VariableDefinition]]), issues, streamAfterVariables)
          case _ => NoMatch()
        }
      }
    }

    def apply(stream: TokenStream): Result[TokenStream, VariablesDefinition] = {
      ParserImpl.parseGroup()
        .element(Type.strategy, "variable(s) type", stopIfAbsent = true)
        .element(varsStrategy, "new variables definition")
        .parse(stream)
        .as[Type, VariableDefinitionSequence] match {

        case (Present(type_), Present(VariableDefinitionSequence(variables)), issues, streamAfterVariables) =>
            Success(VariablesDefinition(type_, variables), issues, streamAfterVariables)
        case _ => NoMatch()
      }
    }
  }
}