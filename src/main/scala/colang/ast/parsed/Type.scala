package colang.ast.parsed

import colang.ast.raw
import colang.ast.raw.TypeDefinition
import colang.issues.{Issue, Issues, Terms}

/**
  * Represents a type.
  * @param name type name
  * @param scope enclosing scope
  * @param definition raw type definition
  * @param native whether type is native
  */
class Type(val name: String,
           val scope: Some[Scope],
           val definition: Option[TypeDefinition],
           val native: Boolean = false) extends Symbol with Scope with ObjectMemberContainer with ConstructorContainer {

  val parent = scope
  val definitionSite = definition match {
    case Some(td) => Some(td.headSource)
    case None => None
  }

  val description = Terms.Type

  /**
    * Returns a reference type for this type.
    * @return reference type
    */
  lazy val reference: ReferenceType = new ReferenceType(this)

  // A default constructor is added for every type (it shouldn't, need to check if the type is Plain)
  addConstructor(defaultConstructor)

  // A copy constructor is added for every type.
  addConstructor(copyConstructor)

  private def defaultConstructor: Constructor = {
    val body = new CodeBlock(new LocalScope(Some(this)), None)

    new Constructor(
      type_ = this,
      parameters = Seq.empty,
      body = body,
      native = true)
  }

  private def copyConstructor: Constructor = {
    val body = new CodeBlock(new LocalScope(Some(this)), None)
    val params = Seq(new Variable(
      name = "other",
      scope = Some(body.innerScope),
      type_ = this,
      definition = None))

    new Constructor(
      type_ = this,
      parameters = params,
      body = body,
      native = true)
  }

  /**
    * Returns true if a type can be implicitly converted to another type.
    * @param other target type
    * @return whether implicit conversion is possible
    */
  def isImplicitlyConvertibleTo(other: Type): Boolean = this eq other

  /**
    * Returns the most specific type that both types are implicitly convertable to, or None.
    * @param other other type
    * @return optional Least Upper Bound
    */
  def leastUpperBound(other: Type): Option[Type] = {
    if (this isImplicitlyConvertibleTo other) {
      Some(other)
    } else if (other isImplicitlyConvertibleTo this) {
      Some(this)
    } else None
  }
}

/**
  * Represents a reference type.
  * Never construct those manually, use Type reference method instead.
  * @param referenced referenced type.
  */
class ReferenceType(val referenced: Type) extends Type(
  name = referenced + "&",
  scope = referenced.scope,
  definition = None,
  native = true) {

  override lazy val reference: ReferenceType = throw new IllegalArgumentException("cannot reference a reference type")

  // A default assign method is generated for every reference type.
  addMethod(defaultAssignMethod)

  private def defaultAssignMethod: Method = {
    val body = new CodeBlock(new LocalScope(Some(this)), None)
    val params = Seq(new Variable(
      name = "other",
      scope = Some(body.innerScope),
      type_ = referenced,
      definition = None))

    new Method(
      name = "assign",
      container = this,
      returnType = this,
      parameters = params,
      body = body,
      definition = None,
      native = true)
  }
}

object Type {

  def resolve(scope: Scope, rawType: raw.Type): (Type, Seq[Issue]) = {
    rawType match {
      case r: raw.SimpleType =>
        scope.resolve(r.name.value) match {
          case Some(type_ : Type) => (type_, Seq.empty)
          case Some(otherSymbol) =>
            val issue = Issues.InvalidReferenceAsType(rawType.source, otherSymbol.description)
            (scope.root.unknownType, Seq(issue))

          case None =>
            val issue = Issues.UnknownName(rawType.source, ())
            (scope.root.unknownType, Seq(issue))
        }

      case r: raw.ReferenceType => ???
    }
  }

  /**
    * Calculates Least Upper Bound of multiple types. See leastUpperBound instance method.
    * @param types types
    * @return Least Upper Bound
    */
  def leastUpperBound(types: Type*): Option[Type] = {
    if (types.isEmpty) {
      None
    } else {
      (types foldLeft Some(types.head).asInstanceOf[Option[Type]]) { (lhsOption, rhs) =>
        lhsOption match {
          case Some(lhs) => lhs leastUpperBound rhs
          case None => None
        }
      }
    }
  }
}
