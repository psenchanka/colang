package colang.ast.parsed.routines

import colang.Issue
import colang.ast.parsed.{CodeBlock, Function, LocalScope, Namespace, Type, Variable}
import colang.ast.raw
import colang.tokens.NativeKeyword

private[routines] object RegisterFunctions {

  /**
    * "Registers" all functions in the root namespace, doesn't parse bodies.
    * @param rootNamespace root namespace
    * @param funcDefs function definitions
    * @return (new functions, encountered issues)
    */
  def registerFunctions(rootNamespace: Namespace, funcDefs: Seq[raw.FunctionDefinition]): (Seq[Function], Seq[Issue]) = {
    val result = funcDefs map { funcDef =>
      val (returnType, returnTypeIssues) = Type.resolve(rootNamespace, funcDef.returnType)

      val functionBody = new CodeBlock(new LocalScope(Some(rootNamespace)), funcDef.body)

      val paramsResult = funcDef.parameterList.params map { rawParam =>
        val (paramType, paramTypeIssues) = Type.resolve(rootNamespace, rawParam.type_)
        val param = new Variable(
          name = rawParam.name.value,
          scope = Some(functionBody.innerScope),
          type_ = paramType,
          definition = Some(rawParam))

        (param, paramTypeIssues)
      }

      val params = paramsResult map { _._1 }
      val paramIssues = paramsResult flatMap { _._2 }

      val function = new Function(
        name = funcDef.name.value,
        scope = Some(rootNamespace),
        returnType = returnType,
        parameters = params,
        body = functionBody,
        definition = Some(funcDef),
        native = funcDef.specifiers.has(classOf[NativeKeyword]))

      val functionIssues = rootNamespace.tryAdd(function)
      (function, returnTypeIssues ++ paramIssues ++ functionIssues)
    }

    val functions = result map { _._1 }
    val issues = result flatMap { _._2 }
    (functions, issues)
  }
}