package colang.backend.c

import java.io.{File, PrintWriter}

import colang.Compiler
import colang.ast.parsed.{Function, Method, Symbol, Type}

import scala.collection.mutable
import scala.io.Source

/**
  * A default C code writer implementation that pretty-prints the code preserving CO symbol names where possible.
  * @param inFile source CO file
  * @param outFile target C file
  */
class CVerboseCodeWriter(inFile: File, outFile: File) extends CCodeWriter {

  private val INDENT: String = "    "

  def write(code: CSourceFile): Unit = {
    val headers = code.headers map { "#include <" + _ + ">" } mkString "\n"
    val typeDefs = code.typeDefs map { td => s"typedef struct {} ${nativeName(td.type_)};" } mkString "\n"
    val varDefs = code.varDefs map { v => s"${nativeName(v.type_)} ${nativeName(v)};"} mkString "\n"
    val funcProtos = code.funcProtos map { writeStatement(_) } mkString "\n"
    val funcDefs = code.funcDefs map { writeStatement(_) } mkString "\n"

    val compilerString = "// This code was generated by colang " + Compiler.VERSION
    val sourceCode = Source.fromFile(inFile).getLines map { "// " + _ } mkString "\n"
    val sourceCodeString = "// Original CO source:\n" + sourceCode

    val macros = Seq(
      """#define _not(a) (!(a))""",
      """#define _neg(a) (-(a))""",
      """#define _mul(a, b) ((a) * (b))""",
      """#define _div(a, b) ((a) / (b))""",
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
      """#define _assign(a, b) ((a) = (b))""",
      """#define _readInt(a) scanf("%d", &(a))""",
      """#define _readDbl(a) scanf("%lf", &(a))""",
      """#define _writeIntLn(a) printf("%d\n", a)""",
      """#define _writeDblLn(a) printf("%lf\n", a)""") mkString "\n"

    val mainDef = s"int main() {\n${INDENT}co_main();\n${INDENT}return 0;\n}"

    val finalSource = Seq(
      compilerString,
      sourceCodeString,
      headers,
      macros,
      typeDefs,
      varDefs,
      funcProtos,
      funcDefs,
      mainDef) filter { _.nonEmpty } mkString "\n\n"

    val writer = new PrintWriter(outFile)
    writer.write(finalSource)
    writer.close()
  }

  /**
    * Formats a C statement.
    * @param statement C statement
    * @param offset current indentation
    * @return string representation
    */
  private def writeStatement(statement: CStatement, offset: String = ""): String = {
    statement match {
      case CSimpleStatement(tokens) => offset + (tokens map writeToken mkString "") + ";"
      case CBlock(heading, variables, statements) =>
        val headingText = writeStatement(heading, offset).dropRight(1)

        val newOffset = offset + INDENT
        val varDefsText = variables map { v => s"$newOffset${nativeName(v.type_)} ${nativeName(v)};"} mkString "\n"
        val statementsText = statements map { writeStatement(_, newOffset) } mkString "\n"

        val blockContentsText = Seq(varDefsText, statementsText) filter { _.nonEmpty } mkString "\n\n"

        s"$headingText {\n$blockContentsText\n$offset}"
    }
  }

  /**
    * Formats a C token.
    * @param token C token
    * @return string representation
    */
  private def writeToken(token: CToken): String = {
    token match {
      case CLiteralToken(text) => text
      case COptionalSpaceToken() => " "
      case CSymbolReferenceToken(symbol) => nativeName(symbol)
      case CMethodReferenceToken(method) => nativeName(method)
    }
  }

  /**
    * Gets a C name for a stable symbol.
    * @param symbol symbol
    * @return C name
    */
  private def nativeName(symbol: Symbol): String = {
    symbol match {
      case f: Function if f.native => internalNativeName(f)
      case t: Type if t.native => internalNativeName(t)
      case _ =>
        val suggestedName = "co_" + sanitizeName(symbol.qualifiedName)
        getOrGenerateNativeName(symbol, suggestedName)
    }
  }

  /**
    * Gets a C function name for a method.
    * @param method method
    * @return C function name
    */
  private def nativeName(method: Method): String = {
    if (method.native) {
      internalNativeName(method)
    } else {
      val suggestedName = "co_" + sanitizeName(method.container.qualifiedName) + "_" + sanitizeName(method.name)
      getOrGenerateNativeName(method, suggestedName)
    }
  }

  //Everything below is the naming implementation and shouldn't be used directly.

  private val nativeNames: mutable.Map[AnyRef, String] = mutable.Map.empty
  private val overlapCount: mutable.Map[String, Integer] = mutable.Map.empty

  /**
    * Generates a new C name for an object or returns the existing one if it exists.
    * @param obj object
    * @param suggestedName suggested name for the object
    * @return C name for the object
    */
  private def getOrGenerateNativeName(obj: AnyRef, suggestedName: String): String = {
    if (nativeNames contains obj) {
      nativeNames(obj)
    } else {
      val newOverlapCount = overlapCount.getOrElseUpdate(suggestedName, 0) + 1
      overlapCount(suggestedName) = newOverlapCount
      val finalName = if (newOverlapCount > 1) {
        suggestedName + "_" + newOverlapCount
      } else {
        suggestedName
      }

      nativeNames(obj) = finalName
      finalName
    }
  }

  private val internalTypeNames: Map[String, String] = Map(
    "int"    -> "int32_t",
    "double" -> "double",
    "void"   -> "void",
    "bool"   -> "int"
  )

  /**
    * Returns a C native name for a type.
    * @param type_ native type
    * @return C name
    */
  private def internalNativeName(type_ : Type): String = {
    internalTypeNames getOrElse(type_.qualifiedName, reportMissingInternalSymbol(type_))
  }

  /**
    * Returns a C native name for a function.
    * @param function native function
    * @return C name
    */
  private def internalNativeName(function: Function): String = {
    function.name match {
      case "readInt" => "_readInt"
      case "readDouble" => "_readDbl"
      case "writeIntLn" => "_writeIntLn"
      case "writeDoubleLn" => "_writeDblLn"
      case _ => reportMissingInternalSymbol(function)
    }
  }

  private val internalUnaryOperatorNames: Map[String, String] = Map(
    "not" -> "_not",
    "unaryMinus" -> "_neg")

  private val internalBinaryOperatorNames: Map[String, String] = Map(
    "times" -> "_mul",
    "div"   -> "_div",
    "plus" -> "_add",
    "minus" -> "_sub",
    "lessThan" -> "_lt",
    "greaterThan" -> "_gt",
    "lessOrEquals" -> "_ltEq",
    "greaterOrEquals" -> "_gtEq",
    "equals" -> "_eq",
    "notEquals" -> "_neq",
    "and" -> "_and",
    "or" -> "_or",
    "assign" -> "_assign")

  /**
    * Returns a C native name for a method.
    * @param method native method
    * @return C name
    */
  private def internalNativeName(method: Method): String = {
    if (method.parameters.size == 1 && (internalBinaryOperatorNames contains method.name)) {
      internalBinaryOperatorNames(method.name)
    } else if (method.parameters.isEmpty && (internalUnaryOperatorNames contains method.name)) {
      internalUnaryOperatorNames(method.name)
    } else {
      reportMissingInternalSymbol(method)
    }
  }

  /**
    * Reports missing implementation for a native symbol and exits with error.
    * @param symbol native symbol
    */
  private def reportMissingInternalSymbol(symbol: Symbol): Nothing = {
    System.err.println(s"internal compiler error: no native representation for symbol '${symbol.qualifiedName}'")
    sys.exit(2)
  }

  /**
    * Reports missing implementation for a native methods and exits with error.
    * @param method native method
    */
  private def reportMissingInternalSymbol(method: Method): Nothing = {
    System.err.println(s"internal compiler error: no native representation for method '${method.name}'" +
      s" of type '${method.container.qualifiedName}'")
    sys.exit(2)
  }

  /**
    * Removes all special characters from an identifier.
    * @param name name to sanitize
    * @return clean name
    */
  private def sanitizeName(name: String) = name.replaceAll("""[^A-Za-z0-9]""", "_")
}
