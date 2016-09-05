package colang.ast.parsed

/**
  * Represents a type constructor.
  * @param type_ container type
  * @param parameters constructor parameters
  * @param body constructor body
  * @param native whether constructor is native
  */
class Constructor(val type_ : Type,
                  val parameters: Seq[Variable],
                  val body: CodeBlock,
                  val native: Boolean = false) extends Applicable {

  val definitionSite = None

  /**
    * Constructs a string from the constructor's signature: its parameter types.
    * @return signature string
    */
  def signatureString: String = {
    val typeName = type_.qualifiedName
    val parameterTypes = parameters map { _.type_.qualifiedName } mkString ", "
    s"$typeName.constructor($parameterTypes)"
  }
}
