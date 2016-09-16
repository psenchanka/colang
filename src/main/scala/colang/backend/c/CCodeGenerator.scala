package colang.backend.c

import java.io.{File, PrintWriter}

import colang.Compiler
import colang.ast.parsed.expression._
import colang.ast.parsed.statement._
import colang.ast.parsed.{CodeBlock, Constructor, Function, LocalScope, Method, NonReferenceType, OverloadedFunction, ReferenceType, RootNamespace, Symbol, Type, Variable}
import colang.backend.Backend
import colang.tokens.BoolLiteral
import colang.utils.{InternalErrors, TopologicalSorter}

import scala.collection.mutable
import scala.io.Source

/**
  * A backend implementation that translates the program into C.
  * @param inFile CO source file
  * @param outFile target C file
  * @param nameGenerator C name generator to use
  */
class CCodeGenerator(inFile: File, outFile: File, nameGenerator: CNameGenerator) extends Backend {

  private val INDENT = "    "
  private val HEADERS = Seq("stdlib.h", "stdio.h", "math.h", "stdint.h")
  private val MACROS = Seq(
    """#define _not(a) (!(a))""",
    """#define _neg(a) (-(a))""",
    """#define _mul(a, b) ((a) * (b))""",
    """#define _div(a, b) ((a) / (b))""",
    """#define _mod(a, b) ((a) % (b))""",
    """#define _add(a, b) ((a) + (b))""",
    """#define _sub(a, b) ((a) - (b))""",
    """#define _lt(a, b) ((a) < (b))""",
    """#define _gt(a, b) ((a) > (b))""",
    """#define _ltEq(a, b) ((a) <= (b))""",
    """#define _gtEq(a, b) ((a) >= (b))""",
    """#define _eq(a, b) ((a) == (b))""",
    """#define _neq(a, b) ((a) != (b))""",
    """#define _and(a, b) ((a) && (b))""",
    """#define _or(a, b) ((a) || (b))""",
    """#define _assign(a, b) (*(a) = (b))""",
    """#define _readInt(a) scanf("%d", a)""",
    """#define _readDbl(a) scanf("%lf", a)""",
    """#define _writeIntLn(a) printf("%d\n", a)""",
    """#define _writeDblLn(a) printf("%lf\n", a)""",
    """#define _int_ctor(a) (*(a) = 0)""",
    """#define _dbl_ctor(a) (*(a) = 0.0)""",
    """#define _bool_ctor(a) (*(a) = 0)""")

  private val NATIVE_FUNCTION_DEFS = Seq(
    """void _assert(int c) { if (!c) { fprintf(stderr, "Assertion failed!\n"); exit(1); } }""",
    """int32_t _powInt(int32_t a, int32_t k) { int32_t res = 1; while (k) { if (k & 1) res *= a; a *= a; k >>= 1; } return res; }""",
    """double _powDbl(double a, double k) { return pow(a, k); }""")

  def process(rootNamespace: RootNamespace): Unit = {
    val (types, variables, functions, methods, constructors) = pickUsedEntities(rootNamespace)

    // Sort the types so that a type 'T' that contains a field of type 'U' is guaranteed to be behind 'U'.
    val typeSorter = new TopologicalSorter[Type](t => t.allFields.map { _.type_ })
    val sortedTypes = typeSorter.sort(types) match {
      case TopologicalSorter.Sorted(ts) => ts
      case TopologicalSorter.LoopFound(loop) => throw new IllegalArgumentException("types are circular-dependent")
    }

    val compilerString = s"// This code was generated by colang ${Compiler.VERSION}\n" +
      s"// Check out https://github.com/psenchanka/colang"
    val sourceCode = Source.fromFile(inFile).getLines map { "// " + _ } mkString "\n"
    val sourceCodeString = "// Original CO source:\n" + sourceCode

    val headers = HEADERS map { "#include <" + _ + ">" } mkString "\n"
    val macros = MACROS mkString "\n"
    val nativeFunctionDefs = NATIVE_FUNCTION_DEFS mkString "\n"

    val typeDefinitions = sortedTypes flatMap generateTypeDefinition mkString "\n\n"
    val variableDefinitions = variables flatMap generateVariableDefinition mkString "\n"
    val functionPrototypes = functions flatMap generateFunctionPrototype mkString "\n"
    val methodPrototypes = methods flatMap generateMethodPrototype mkString "\n"
    val constructorPrototypes = constructors flatMap generateConstructorPrototype mkString "\n"
    val functionDefinitions = functions flatMap generateFunctionDefinition mkString "\n\n"
    val methodDefinitions = methods flatMap generateMethodDefinition mkString "\n\n"
    val constructorDefinitions = constructors flatMap generateConstructorDefinition mkString "\n\n"

    val mainDef = s"int main() {\n${INDENT}co_main();\n${INDENT}return 0;\n}"

    val finalCSource = Seq(
      compilerString,
      sourceCodeString,
      headers,
      macros,
      nativeFunctionDefs,
      typeDefinitions,
      variableDefinitions,
      functionPrototypes,
      methodPrototypes,
      constructorPrototypes,
      functionDefinitions,
      methodDefinitions,
      constructorDefinitions,
      mainDef) filter { _.nonEmpty } mkString "\n\n"

    val writer = new PrintWriter(outFile)
    writer.write(finalCSource)
    writer.close()
  }

  /**
    * Picks all global entities that are actually used in the program from the global namespace.
    * This method basically walks the syntax tree starting with 'main()' function and adds every visited entity.
    * @param rootNamespace root namespace
    * @return (types, variables, functions, methods, constructors) that are potentially used
    */
  private def pickUsedEntities(rootNamespace: RootNamespace)
  : (Seq[Type], Seq[Variable], Seq[Function], Seq[Method], Seq[Constructor]) = {

    val types = mutable.HashSet.empty[Type]
    val functions = mutable.HashSet.empty[Function]
    val variables = mutable.HashSet.empty[Variable]
    val methods = mutable.HashSet.empty[Method]
    val constructors = mutable.HashSet.empty[Constructor]

    val mainFunction = rootNamespace.resolve("main") match {
      case Some(f: Function) => f
      case Some(of: OverloadedFunction) => of.resolveOverload(Seq.empty, None)._1.get
    }

    processFunction(mainFunction)

    // We only keep non-reference types.
    def processType(type_ : Type): Unit = {
      if (types contains type_) {
        return
      }

      type_ match {
        case rt: ReferenceType => processType(rt.referenced)
        case _ =>
          types.add(type_)
          type_.allFields map { _.type_ } foreach types.add
      }
    }

    def processVariable(variable: Variable): Unit = {
      if (variables contains variable) {
        return
      }

      if (variable.scope.get.isInstanceOf[LocalScope]) {
        return
      }

      variables.add(variable)
      processType(variable.type_)
    }

    def processFunction(function: Function): Unit = {
      if (functions contains function) {
        return
      }

      functions.add(function)

      processType(function.returnType)
      function.parameters map { _.type_ } foreach processType
      processCodeBlock(function.body)
    }

    def processMethod(method: Method): Unit = {
      if (methods contains method) {
        return
      }

      methods.add(method)
      processType(method.container)
      processType(method.returnType)
      method.parameters map { _.type_ } foreach processType
      processCodeBlock(method.body)
    }

    def processConstructor(constructor: Constructor): Unit = {
      if (constructors contains constructor) {
        return
      }

      constructors.add(constructor)

      processType(constructor.type_)
      constructor.parameters map { _.type_ } foreach processType
      processCodeBlock(constructor.body)
    }

    def processCodeBlock(codeBlock: CodeBlock): Unit = {
      codeBlock.innerScope.allVariables map { _.type_ } foreach processType
      codeBlock.statements foreach processStatement
    }

    def processStatement(statement: Statement): Unit = {
      statement match {
        case expr: Expression => processExpression(expr)
        case cb: CodeBlock => processCodeBlock(cb)

        case constructorCall: VariableConstructorCall =>
          processConstructor(constructorCall.constructor)
          processType(constructorCall.instance.type_)
          constructorCall.arguments foreach processExpression

        case stmt: IfElseStatement =>
          processExpression(stmt.condition)
          processCodeBlock(stmt.ifBranch)
          stmt.elseBranch foreach processCodeBlock

        case stmt: ReturnStatement =>
          stmt.returnValue foreach processExpression

        case stmt: WhileStatement =>
          processExpression(stmt.condition)
          processCodeBlock(stmt.loop)
      }
    }

    def processExpression(expression: Expression): Unit = {
      processType(expression.type_)

      expression match {
        case expr: BoolLiteral => processType(rootNamespace.boolType)
        case expr: DoubleLiteral => processType(rootNamespace.doubleType)

        case expr: FunctionCall =>
          processFunction(expr.function)
          expr.arguments foreach processExpression

        case expr: ImplicitDereferencing =>
          processExpression(expr.expression)
          processType(expr.type_)

        case expr: IntLiteral => processType(rootNamespace.intType)

        case expr: FieldAccess =>
          processExpression(expr.instance)

        case expr: MethodCall =>
          processMethod(expr.method)
          processExpression(expr.instance)
          expr.arguments foreach processExpression

        case expr: FunctionReference => processFunction(expr.function)
        case expr: OverloadedFunctionReference => ()
        case expr: VariableReference => processVariable(expr.variable)
      }
    }

    (types.toSeq, variables.toSeq, functions.toSeq, methods.toSeq, constructors.toSeq)
  }

  private val nativeTypeNames = Map(
    "int"    -> "int32_t",
    "double" -> "double",
    "void"   -> "void",
    "bool"   -> "int")

  private def generateTypeDefinition(type_ : Type): Option[String] = {
    if (type_.native) {
      val nativeName = nativeTypeNames.getOrElse(type_.qualifiedName, reportMissingInternalSymbol(type_))
      nameGenerator.setNativeNameFor(type_, nativeName)
      None
    } else {
      val cName = nameGenerator.nameFor(type_)
      val cFields = addIndentation(type_.allFields map { field =>
        val cFieldType = nameGenerator.nameFor(field.type_)
        val cFieldName = nameGenerator.nameFor(field)
        s"$cFieldType $cFieldName;"
      } mkString "\n")

      if (cFields.trim.nonEmpty) {
        Some(s"typedef struct {\n$cFields\n} $cName;")
      } else {
        Some(s"typedef struct {} $cName;")
      }
    }
  }

  private def generateVariableDefinition(variable: Variable): Option[String] = {
    val cName = nameGenerator.nameFor(variable)
    val cTypeName = nameGenerator.nameFor(variable.type_)
    Some(s"$cTypeName $cName;")
  }

  private val nativeFunctionNames = Map(
    "void read(int&)" -> "_readInt",
    "void read(double&)" -> "_readDbl",
    "void println(int)" -> "_writeIntLn",
    "void println(double)" -> "_writeDblLn",
    "void assert(bool)" -> "_assert")

  private def generateFunctionPrototype(function: Function): Option[String] = {
    if (function.native) {
      val nativeName = nativeFunctionNames.getOrElse(function.signatureString, reportMissingInternalSymbol(function))
      nameGenerator.setNativeNameFor(function, nativeName)
      None
    } else {
      val cName = nameGenerator.nameFor(function)
      val cReturnTypeName = nameGenerator.nameFor(function.returnType)
      val cParamTypes = function.parameters map { _.type_ } map nameGenerator.nameFor mkString ", "

      Some(s"$cReturnTypeName $cName($cParamTypes);")
    }
  }

  private def generateFunctionDefinition(function: Function): Option[String] = {
    if (!function.native) {
      val cName = nameGenerator.nameFor(function)
      val cReturnTypeName = nameGenerator.nameFor(function.returnType)

      val cParamTypeNames = function.parameters map { _.type_ } map nameGenerator.nameFor
      val cParamNames = function.parameters map nameGenerator.nameFor
      val cParameters = (cParamTypeNames zip cParamNames) map { case (t, n) => s"$t $n" } mkString ", "

      val cBody = generateCodeBlock(function.body, ignoredVariables = function.parameters)

      Some(s"$cReturnTypeName $cName($cParameters) $cBody")
    } else None
  }

  private val nativeMethodNames = Map(
    "int int.unaryMinus()" -> "_neg",
    "int int.times(int)" -> "_mul",
    "int int.div(int)"   -> "_div",
    "int int.mod(int)"   -> "_mod",
    "int int.pow(int)"   -> "_powInt",
    "int int.plus(int)" -> "_add",
    "int int.minus(int)" -> "_sub",
    "bool int.lessThan(int)" -> "_lt",
    "bool int.greaterThan(int)" -> "_gt",
    "bool int.lessOrEquals(int)" -> "_ltEq",
    "bool int.greaterOrEquals(int)" -> "_gtEq",
    "bool int.equals(int)" -> "_eq",
    "bool int.notEquals(int)" -> "_neq",
    "double double.unaryMinus()" -> "_neg",
    "double double.times(double)" -> "_mul",
    "double double.div(double)"   -> "_div",
    "double double.pow(double)"   -> "_powDbl",
    "double double.plus(double)" -> "_add",
    "double double.minus(double)" -> "_sub",
    "bool double.lessThan(double)" -> "_lt",
    "bool double.greaterThan(double)" -> "_gt",
    "bool double.lessOrEquals(double)" -> "_ltEq",
    "bool double.greaterOrEquals(double)" -> "_gtEq",
    "bool double.equals(double)" -> "_eq",
    "bool double.notEquals(double)" -> "_neq",
    "bool bool.not()" -> "_not",
    "bool bool.and(bool)" -> "_and",
    "bool bool.or(bool)" -> "_or")

  private def generateMethodPrototype(method: Method): Option[String] = {
    if (method.name == "assign") {
      nameGenerator.setNativeNameFor(method, "_assign")
      None
    } else if (method.native) {
      val nativeName = nativeMethodNames.getOrElse(method.signatureString, reportMissingInternalMethod(method))
      nameGenerator.setNativeNameFor(method, nativeName)
      None
    } else {
      ???
    }
  }

  private def generateMethodDefinition(method: Method): Option[String] = {
    None
  }

  private val nativeDefaultConstructorNames = Map(
    "int.constructor()" -> "_int_ctor",
    "double.constructor()" -> "_dbl_ctor",
    "bool.constructor()" -> "_bool_ctor")

  private def generateConstructorPrototype(constructor: Constructor): Option[String] = {
    // 1.1: Use a macro (copy-ctor):
    if (constructor.native && constructor.isCopyConstructor && constructor.type_.native) {
      nameGenerator.setNativeNameFor(constructor, "_assign")
      None

    // 1.2: Use a macro (default-ctor):
    } else if (nativeDefaultConstructorNames contains constructor.signatureString) {
      nameGenerator.setNativeNameFor(constructor, nativeDefaultConstructorNames(constructor.signatureString))
      None

    // 2: Generate a recursive constructor:
    // 3: Use custom constructor from definition:
    } else {
      val cName = nameGenerator.nameFor(constructor)
      val cInstanceType = nameGenerator.nameFor(constructor.type_) + "*"
      val cParamTypes = (constructor.parameters map { _.type_ }) map nameGenerator.nameFor

      val cParamString = (cInstanceType +: cParamTypes) mkString ", "

      Some(s"void $cName($cParamString);")
    }
  }

  private def generateConstructorDefinition(constructor: Constructor): Option[String] = {
    // 1: Use a macro:
    if (constructor.native && constructor.isCopyConstructor && constructor.type_.native
      || (nativeDefaultConstructorNames contains constructor.signatureString)) {

      None

    // 2: Generate a recursive constructor:
    } else if (constructor.native) {
      // TODO when we have fields, those constructors should actually do something.

      val cName = nameGenerator.nameFor(constructor)

      val cInstanceTypeName = nameGenerator.nameFor(constructor.type_) + "*"
      val cParamTypeNames = constructor.parameters map { _.type_ } map nameGenerator.nameFor
      val cParamNames = "_this" +: (constructor.parameters map nameGenerator.nameFor)
      val cParameters = ((cInstanceTypeName +: cParamTypeNames) zip cParamNames) map { case (t, n) => s"$t $n" } mkString ", "

      Some(s"void $cName($cParameters) {}")

    // 3: Use custom constructor from definition
    } else {
      ???
    }
  }

  private def generateCodeBlock(codeBlock: CodeBlock, ignoredVariables: Seq[Variable] = Seq.empty): String = {
    val localVariables = codeBlock.innerScope.allVariables filterNot { ignoredVariables contains _ }

    val localVariableDefinitions = localVariables flatMap generateVariableDefinition mkString "\n"
    val statements = codeBlock.statements map generateStatement mkString "\n"

    val blockContent = addIndentation(Seq(localVariableDefinitions, statements) filter { _.trim.nonEmpty } mkString "\n")
    s"{\n$blockContent\n}"
  }

  private def generateStatement(statement: Statement): String = {
    statement match {
      case expr: Expression => generateExpression(expr) + ";"
      case cb: CodeBlock => generateCodeBlock(cb)

      case constructorCall: VariableConstructorCall =>
        val cConstructorName = nameGenerator.nameFor(constructorCall.constructor)
        val instance = "&" + nameGenerator.nameFor(constructorCall.instance)
        val arguments = constructorCall.arguments map generateExpression
        val cArguments = (instance +: arguments) mkString ", "

        s"$cConstructorName($cArguments);"

      case stmt: IfElseStatement =>
        val condition = generateExpression(stmt.condition)
        val ifBranch = generateCodeBlock(stmt.ifBranch)
        val elseBranchOption = stmt.elseBranch map { generateCodeBlock(_) }

        elseBranchOption match {
          case Some(elseBranch) => s"if ($condition) $ifBranch else $elseBranch"
          case None => s"if ($condition) $ifBranch"
        }

      case stmt: ReturnStatement =>
        stmt.returnValue match {
          case Some(retVal) =>
            val cRetVal = generateExpression(retVal)
            s"return $cRetVal;"

          case None => "return;"
        }

      case stmt: WhileStatement =>
        val condition = generateExpression(stmt.condition)
        val loop = generateCodeBlock(stmt.loop)
        s"while ($condition) $loop"
    }
  }

  private def generateExpression(expression: Expression): String = {
    expression match {
      case expr: BoolLiteral => if (expr.value) "1" else "0"
      case expr: DoubleLiteral =>
        if (expr.value.isPosInfinity) {
          "INFINITY"
        } else if (expr.value.isNegInfinity) {
          "-INFINITY"
        } else if (expr.value.isNaN) {
          "NAN"
        } else {
          expr.value.toString
        }

      case expr: FunctionCall =>
        val cFunctionName = nameGenerator.nameFor(expr.function)
        val arguments = expr.arguments map generateExpression mkString ", "
        s"$cFunctionName($arguments)"

      case expr: ImplicitDereferencing =>
        s"*${generateExpression(expr.expression)}"

      case expr: IntLiteral => expr.value.toString

      case expr: FieldAccess =>
        val cInstance = generateExpression(expr.instance)
        val cField = nameGenerator.nameFor(expr.field)

        expr.instance.type_ match {
          case _: NonReferenceType => s"(($cInstance).$cField)"
          case _: ReferenceType => s"&(($cInstance)->$cField)"
        }

      case expr: MethodCall =>
        val cMethodName = nameGenerator.nameFor(expr.method)
        val arguments = (expr.instance +: expr.arguments) map generateExpression mkString ", "
        s"$cMethodName($arguments)"

      // Function references by themselves are currently useless, so we don't generate them.
      case expr: FunctionReference => ""
      case expr: OverloadedFunctionReference => ""

      case ReferenceVariableReference(v, _) => nameGenerator.nameFor(v)
      case VariableReference(v) => "&" + nameGenerator.nameFor(v)
    }
  }

  /**
    * Adds one level of indentation to given source code.
    * @param code source code to indent
    * @return resulting source code
    */
  private def addIndentation(code: String): String = {
    code split "\n" map { INDENT + _ } mkString "\n"
  }

  /**
    * Reports missing implementation for a native symbol and exits with error.
    * @param symbol native symbol
    */
  private def reportMissingInternalSymbol(symbol: Symbol): Nothing = {
    InternalErrors.noNativeSymbol(symbol.qualifiedName)
  }

  /**
    * Reports missing implementation for a native methods and exits with error.
    * @param method native method
    */
  private def reportMissingInternalMethod(method: Method): Nothing = {
    // TODO when methods have signature() method, use it here.
    InternalErrors.noNativeSymbol(method.name)
  }
}
