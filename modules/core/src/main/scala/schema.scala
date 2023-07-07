// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.grackle

import cats.parse.Parser
import cats.implicits._
import io.circe.Json
import org.tpolecat.sourcepos.SourcePos

import syntax._
import Ast.{InterfaceTypeDefinition, ObjectTypeDefinition, TypeDefinition, UnionTypeDefinition}
import ScalarType._
import Value._

/**
 * Representation of a GraphQL schema
 *
 * A `Schema` is a collection of type and directive declarations.
 */
trait Schema {

  def pos: SourcePos

  /** The types defined by this `Schema`. */
  def types: List[NamedType]

  /** The directives defined by this `Schema`. */
  def directives: List[Directive]

  /** A reference by name to a type defined by this `Schema`.
   *
   * `TypeRef`s refer to types defined in this schema by name and hence
   * can be used as part of mutually recursive type definitions.
   */
  def ref(tpnme: String): TypeRef = new TypeRef(this, tpnme)

  /**
   * Alias for `ref` for use within constructors of concrete
   * `Schema` values.
   */
  protected def TypeRef(tpnme: String): TypeRef = ref(tpnme)

  /**
   * The default type of a GraphQL schema
   *
   * Unless a type named `"Schema"` is explicitly defined as part of
   * this `Schema` a definition of the form,
   *
   * ```
   * type Schema {
   *   query: Query!
   *   mutation: Mutation
   *   subscription: Subscription
   * }
   * ```
   *
   * is used.
   */
  def defaultSchemaType: NamedType = {
    def mkRootDef(fieldName: String)(tpe: NamedType): Field =
      Field(fieldName, None, Nil, tpe, false, None)

    ObjectType(
      name = "Schema",
      description = None,
      fields =
        List(
          definition("Query").map(mkRootDef("query")),
          definition("Mutation").map(mkRootDef("mutation")),
          definition("Subscription").map(mkRootDef("subscription"))
        ).flatten,
      interfaces = Nil
    )
  }

  /**
   * Look up by name a type defined in this `Schema`.
   *
   * Yields the type, if defined, `None` otherwise.
   */
  def definition(name: String): Option[NamedType] =
    typeIndex.get(name).orElse(ScalarType.builtIn(name)).map(_.dealias)

  private lazy val typeIndex = types.map(tpe => (tpe.name, tpe)).toMap

  def ref(tp: Type): Option[TypeRef] = tp match {
    case nt: NamedType if types.exists(_.name == nt.name) => Some(ref(nt.name))
    case _ => None
  }

  /**
   * The schema type.
   *
   * Either the explicitly defined type named `"Schema"` or the default
   * schema type if not defined.
   */
  def schemaType: NamedType = definition("Schema").getOrElse(defaultSchemaType)

  /** The type of queries defined by this `Schema`*/
  def queryType: NamedType = schemaType.field("query").flatMap(_.asNamed).get

  /** The type of mutations defined by this `Schema`*/
  def mutationType: Option[NamedType] = schemaType.field("mutation").flatMap(_.asNamed)

  /** The type of subscriptions defined by this `Schema`*/
  def subscriptionType: Option[NamedType] = schemaType.field("subscription").flatMap(_.asNamed)

  /** True if the supplied type is one of the Query, Mutation or Subscription root types, false otherwise */
  def isRootType(tpe: Type): Boolean =
    tpe =:= queryType || mutationType.map(_ =:= tpe).getOrElse(false) || subscriptionType.map(_ =:= tpe).getOrElse(false)

  /** Are the supplied alternatives exhaustive for `tp` */
  def exhaustive(tp: Type, branches: List[Type]): Boolean = {
    types.forall {
      case o: ObjectType => !(o <:< tp) || branches.exists(b => o <:< b)
      case _ => true
    }
  }

  override def toString = SchemaRenderer.renderSchema(this)
}

object Schema {
  def apply(schemaText: String)(implicit pos: SourcePos): Result[Schema] =
    SchemaParser.parseText(schemaText)
}

/**
 * A GraphQL type definition.
 */
sealed trait Type extends Product {
  /**
   * Is this type equivalent to `other`.
   *
   * Note that plain `==` will distinguish types from type aliases,
   * which is typically not desirable, so `=:=` is usually the
   * most appropriate comparison operator.
   */
  def =:=(other: Type): Boolean = (this eq other) || (dealias == other.dealias)

  /** `true` if this type is a subtype of `other`. */
  def <:<(other: Type): Boolean =
    (this.dealias, other.dealias) match {
      case (tp1, tp2) if tp1 == tp2 => true
      case (tp1, UnionType(_, _, members)) => members.exists(tp1 <:< _.dealias)
      case (ObjectType(_, _, _, interfaces), tp2) => interfaces.exists(_ <:< tp2)
      case (InterfaceType(_, _, _, interfaces), tp2) => interfaces.exists(_ <:< tp2)
      case (NullableType(tp1), NullableType(tp2)) => tp1 <:< tp2
      case (tp1, NullableType(tp2)) => tp1 <:< tp2
      case (ListType(tp1), ListType(tp2)) => tp1 <:< tp2
      case _ => false
    }

  def nominal_=:=(other: Type): Boolean =
    this =:= other ||
      ((this.dealias, other.dealias) match {
        case (nt1: NamedType, nt2: NamedType) => nt1.name == nt2.name
        case _ => false
      })

  /**
   * Yield the type of the field of this type named `fieldName` or
   * `None` if there is no such field.
   */
  def field(fieldName: String): Option[Type] = this match {
    case NullableType(tpe) => tpe.field(fieldName)
    case TypeRef(_, _) if exists => dealias.field(fieldName)
    case ObjectType(_, _, fields, _) => fields.find(_.name == fieldName).map(_.tpe)
    case InterfaceType(_, _, fields, _) => fields.find(_.name == fieldName).map(_.tpe)
    case _ => None
  }

  /** `true` if this type has a field named `fieldName`, false otherwise. */
  def hasField(fieldName: String): Boolean =
    field(fieldName).isDefined

  /**
   * `true` if this type has a field named `fieldName` which is undefined in
   * some interface it implements
   */
  def variantField(fieldName: String): Boolean =
    underlyingObject match {
      case Some(ObjectType(_, _, _, interfaces)) =>
        hasField(fieldName) && interfaces.exists(!_.hasField(fieldName))
      case _ => false
    }

  def withField[T](fieldName: String)(body: Type => Result[T]): Result[T] =
    field(fieldName).map(body).getOrElse(Result.failure(s"Unknown field '$fieldName' in '$this'"))

  /**
   * Yield the type of the field at the end of the path `fns` starting
   * from this type, or `None` if there is no such field.
   */
  def path(fns: List[String]): Option[Type] = (fns, this) match {
    case (Nil, _) => Some(this)
    case (_, ListType(tpe)) => tpe.path(fns)
    case (_, NullableType(tpe)) => tpe.path(fns)
    case (_, TypeRef(_, _)) => dealias.path(fns)
    case (fieldName :: rest, ObjectType(_, _, fields, _)) =>
      fields.find(_.name == fieldName).flatMap(_.tpe.path(rest))
    case (fieldName :: rest, InterfaceType(_, _, fields, _)) =>
      fields.find(_.name == fieldName).flatMap(_.tpe.path(rest))
    case _ => None
  }

  /**
   * Does the path `fns` from this type specify multiple values.
   *
   * `true` if navigating through the path `fns` from this type
   * might specify 0 or more values. This will be the case if the
   * path passes through at least one field of a List type.
   */
  def pathIsList(fns: List[String]): Boolean = (fns, this) match {
    case (Nil, _) => this.isList
    case (_, _: ListType) => true
    case (_, NullableType(tpe)) => tpe.pathIsList(fns)
    case (_, TypeRef(_, _)) => dealias.pathIsList(fns)
    case (fieldName :: rest, ObjectType(_, _, fields, _)) =>
      fields.find(_.name == fieldName).map(_.tpe.pathIsList(rest)).getOrElse(false)
    case (fieldName :: rest, InterfaceType(_, _, fields, _)) =>
      fields.find(_.name == fieldName).map(_.tpe.pathIsList(rest)).getOrElse(false)
    case _ => false
  }

  /**
   * Does the path `fns` from this type specify a nullable type.
   *
   * `true` if navigating through the path `fns` from this type
   * might specify an optional value. This will be the case if the
   * path passes through at least one field of a nullable type.
   */
  def pathIsNullable(fns: List[String]): Boolean = (fns, this) match {
    case (Nil, _) => false
    case (_, ListType(tpe)) => tpe.pathIsNullable(fns)
    case (_, _: NullableType) => true
    case (_, TypeRef(_, _)) => dealias.pathIsNullable(fns)
    case (fieldName :: rest, ObjectType(_, _, fields, _)) =>
      fields.find(_.name == fieldName).map(_.tpe.pathIsNullable(rest)).getOrElse(false)
    case (fieldName :: rest, InterfaceType(_, _, fields, _)) =>
      fields.find(_.name == fieldName).map(_.tpe.pathIsNullable(rest)).getOrElse(false)
    case _ => false
  }

  /** Strip off aliases */
  def dealias: Type = this

  /** true if a non-TypeRef or a TypeRef to a defined type */
  def exists: Boolean = true

  /** Is this type nullable? */
  def isNullable: Boolean = this match {
    case NullableType(_) => true
    case _ => false
  }

  /** This type if it is nullable, `Nullable(this)` otherwise. */
  def nullable: Type = this match {
    case t: NullableType => t
    case t => NullableType(t)
  }

  /**
   * A non-nullable version of this type.
   *
   * If this type is nullable, yield the non-nullable underlying
   * type. Otherwise yield this type.
   */
  def nonNull: Type = this match {
    case NullableType(tpe) => tpe.nonNull
    case _ => this
  }

  /** Is this type a list. */
  def isList: Boolean = this match {
    case ListType(_) => true
    case _ => false
  }

  /**
   * The element type of this type.
   *
   * If this type is is a list, yield the non-list underlying type.
   * Otherwise yield `None`.
   */
  def item: Option[Type] = this match {
    case NullableType(tpe) => tpe.item
    case ListType(tpe) => Some(tpe)
    case _ => None
  }

  /** This type if it is a (nullable) list, `ListType(this)` otherwise. */
  def list: Type = this match {
    case l: ListType => l
    case NullableType(tpe) => NullableType(tpe.list)
    case tpe => ListType(tpe)
  }

  def underlying: Type = this match {
    case NullableType(tpe) => tpe.underlying
    case ListType(tpe) => tpe.underlying
    case _: TypeRef => dealias.underlying
    case _ => this
  }

  /**
   * Yield the object type underlying this type.
   *
   * Strip off all aliases, nullability and enclosing list types until
   * an underlying object type is reached, in which case yield it, or a
   * non-object type which isn't further reducible is reached, in which
   * case yield `None`.
   */
  def underlyingObject: Option[Type] = this match {
    case NullableType(tpe) => tpe.underlyingObject
    case ListType(tpe) => tpe.underlyingObject
    case _: TypeRef => dealias.underlyingObject
    case o: ObjectType => Some(o)
    case i: InterfaceType => Some(i)
    case u: UnionType => Some(u)
    case _ => None
  }

  /**
   * Yield the type of the field named `fieldName` of the object type
   * underlying this type.
   *
   * Strip off all aliases, nullability and enclosing list types until
   * an underlying object type is reached which has a field named
   * `fieldName`, in which case yield the type of that field; if there
   * is no such field, yields `None`.
   */
  def underlyingField(fieldName: String): Option[Type] = this match {
    case NullableType(tpe) => tpe.underlyingField(fieldName)
    case ListType(tpe) => tpe.underlyingField(fieldName)
    case TypeRef(_, _) => dealias.underlyingField(fieldName)
    case ObjectType(_, _, fields, _) => fields.find(_.name == fieldName).map(_.tpe)
    case InterfaceType(_, _, fields, _) => fields.find(_.name == fieldName).map(_.tpe)
    case _ => None
  }

  def withUnderlyingField[T](fieldName: String)(body: Type => Result[T]): Result[T] =
    underlyingObject.toResult(s"$this is not an object or interface type").flatMap(_.withField(fieldName)(body))

  /** Is this type a leaf type?
   *
   * `true` if after stripping of aliases the underlying type a scalar or an
   * enum, `false` otherwise.
   */
  def isLeaf: Boolean = this match {
    case TypeRef(_, _) => dealias.isLeaf
    case _: ScalarType => true
    case _: EnumType => true
    case _ => false
  }

  /**
   * If the underlying type of this type is a scalar or an enum then yield it
   * otherwise yield `None`.
   */
  def asLeaf: Option[Type] = this match {
    case TypeRef(_, _) => dealias.asLeaf
    case _: ScalarType => Some(this)
    case _: EnumType => Some(this)
    case _ => None
  }


  /**
   * Is the underlying of this type a leaf type?
   *
   * Strip off all aliases, nullability and enclosing list types until
   * an underlying leaf type is reached, in which case yield true, or an
   * a object, interface or union type which is reached, in which case
   * yield false.
   */
  def isUnderlyingLeaf: Boolean = this match {
    case NullableType(tpe) => tpe.isUnderlyingLeaf
    case ListType(tpe) => tpe.isUnderlyingLeaf
    case _: TypeRef => dealias.isUnderlyingLeaf
    case (_: ObjectType)|(_: InterfaceType)|(_: UnionType) => false
    case _ => true
  }

  /**
   * Yield the leaf type underlying this type.
   *
   * Strip off all aliases, nullability and enclosing list types until
   * an underlying leaf type is reached, in which case yield it, or an
   * a object, interface or union type which is reached, in which case
   * yield `None`.
   */
  def underlyingLeaf: Option[Type] = this match {
    case NullableType(tpe) => tpe.underlyingLeaf
    case ListType(tpe) => tpe.underlyingLeaf
    case _: TypeRef => dealias.underlyingLeaf
    case (_: ObjectType)|(_: InterfaceType)|(_: UnionType) => None
    case tpe => Some(tpe)
  }

  def withModifiersOf(tpe: Type): Type = {
    def loop(rtpe: Type, tpe: Type): Type = tpe match {
      case NullableType(tpe) => loop(NullableType(rtpe), tpe)
      case ListType(tpe) => loop(ListType(rtpe), tpe)
      case _ => rtpe
    }
    loop(this, tpe)
  }

  def isNamed: Boolean = false

  def asNamed: Option[NamedType] = None

  def isInterface: Boolean = false

  def isUnion: Boolean = false

  def /(pathElement: String): Path =
    Path.from(this) / pathElement

}

// Move all below into object Type?

/** A type with a schema-defined name.
 *
 * This includes object types, inferface types and enums.
 */
sealed trait NamedType extends Type {
  /** The name of this type */
  def name: String

  override def dealias: NamedType = this

  override def isNamed: Boolean = true

  override def asNamed: Option[NamedType] = Some(this)

  def description: Option[String]

  override def toString: String = name
}

/**
 * A by name reference to a type defined in `schema`.
 */
case class TypeRef(schema: Schema, name: String) extends NamedType {
  override lazy val dealias: NamedType = schema.definition(name).getOrElse(this)

  override lazy val exists: Boolean = schema.definition(name).isDefined

  def description: Option[String] = dealias.description

}

/**
 * Represents scalar types such as Int, String, and Boolean. Scalars cannot have fields.
 *
 * @see https://facebook.github.io/graphql/draft/#sec-Scalar
 */
case class ScalarType(
  name: String,
  description: Option[String]
) extends Type with NamedType {
  import ScalarType._

  /** True if this is one of the five built-in Scalar types defined in the GraphQL Specification. */
  def isBuiltIn: Boolean =
    this match {
      case IntType     |
           FloatType   |
           StringType  |
           BooleanType |
           IDType      => true
      case _           => false
    }

}

object ScalarType {
  def builtIn(tpnme: String): Option[ScalarType] = tpnme match {
    case "Int" => Some(IntType)
    case "Float" => Some(FloatType)
    case "String" => Some(StringType)
    case "Boolean" => Some(BooleanType)
    case "ID" => Some(IDType)
    case _ => None
  }

  val IntType = ScalarType(
    name = "Int",
    description =
      Some(
        """|The Int scalar type represents a signed 32‐bit numeric non‐fractional value.
           |Response formats that support a 32‐bit integer or a number type should use that
           |type to represent this scalar.
        """.stripMargin.trim
      )
  )
  val FloatType = ScalarType(
    name = "Float",
    description =
      Some(
        """|The Float scalar type represents signed double‐precision fractional values as
           |specified by IEEE 754. Response formats that support an appropriate
           |double‐precision number type should use that type to represent this scalar.
        """.stripMargin.trim
      )
  )
  val StringType = ScalarType(
    name = "String",
    description =
      Some(
        """|The String scalar type represents textual data, represented as UTF‐8 character
           |sequences. The String type is most often used by GraphQL to represent free‐form
           |human‐readable text.
        """.stripMargin.trim
      )
  )
  val BooleanType = ScalarType(
    name = "Boolean",
    description =
      Some(
        """|The Boolean scalar type represents true or false. Response formats should use a
           |built‐in boolean type if supported; otherwise, they should use their
           |representation of the integers 1 and 0.
        """.stripMargin.trim
      )
  )

  val IDType = ScalarType(
    name = "ID",
    description =
      Some(
        """|The ID scalar type represents a unique identifier, often used to refetch an
           |object or as the key for a cache. The ID type is serialized in the same way as a
           |String; however, it is not intended to be human‐readable.
        """.stripMargin.trim
      )
  )

  val AttributeType = ScalarType(
    name = "InternalAttribute",
    description = None
  )
}

/**
 * A type with fields.
 *
 * This includes object types and inferface types.
 */
sealed trait TypeWithFields extends NamedType {
  def fields: List[Field]
  def interfaces: List[NamedType]

  def fieldInfo(name: String): Option[Field] = fields.find(_.name == name)
}

/**
 * Interfaces are an abstract type where there are common fields declared. Any type that
 * implements an interface must define all the fields with names and types exactly matching.
 *
 * @see https://facebook.github.io/graphql/draft/#sec-Interface
 */
case class InterfaceType(
  name: String,
  description: Option[String],
  fields: List[Field],
  interfaces: List[NamedType]
) extends Type with TypeWithFields {
  override def isInterface: Boolean = true
}

/**
 * Object types represent concrete instantiations of sets of fields.
 *
 * @see https://facebook.github.io/graphql/draft/#sec-Object
 */
case class ObjectType(
  name: String,
  description: Option[String],
  fields: List[Field],
  interfaces: List[NamedType]
) extends Type with TypeWithFields

/**
 * Unions are an abstract type where no common fields are declared. The possible types of a union
 * are explicitly listed out in elements. Types can be made parts of unions without
 * modification of that type.
 *
 * @see https://facebook.github.io/graphql/draft/#sec-Union
 */
case class UnionType(
  name: String,
  description: Option[String],
  members: List[NamedType]
) extends Type with NamedType {
  override def isUnion: Boolean = true
  override def toString: String = members.mkString("|")
}

/**
 * Enums are special scalars that can only have a defined set of values.
 *
 * @see https://facebook.github.io/graphql/draft/#sec-Enum
 */
case class EnumType(
  name: String,
  description: Option[String],
  enumValues: List[EnumValue]
) extends Type with NamedType {
  def hasValue(name: String): Boolean = enumValues.exists(_.name == name)

  def value(name: String): Option[EnumValue] = enumValues.find(_.name == name)
}

/**
 * The `EnumValue` type represents one of possible values of an enum.
 *
 * @see https://facebook.github.io/graphql/draft/#sec-The-__EnumValue-Type
 */
case class EnumValue(
  name: String,
  description: Option[String],
  isDeprecated: Boolean = false,
  deprecationReason: Option[String] = None
)

/**
 * Input objects are composite types used as inputs into queries defined as a list of named input
 * values.
 *
 * @see https://facebook.github.io/graphql/draft/#sec-Input-Object
 */
case class InputObjectType(
  name: String,
  description: Option[String],
  inputFields: List[InputValue]
) extends Type with NamedType {
  def inputFieldInfo(name: String): Option[InputValue] = inputFields.find(_.name == name)
}

/**
 * Lists represent sequences of values in GraphQL. A List type is a type modifier: it wraps
 * another type instance in the ofType field, which defines the type of each item in the list.
 *
 * @see https://facebook.github.io/graphql/draft/#sec-Type-Kinds.List
 */
case class ListType(
  ofType: Type
) extends Type {
  override def toString: String = s"[$ofType]"
}

/**
 * A Non‐null type is a type modifier: it wraps another type instance in the `ofType` field.
 * Non‐null types do not allow null as a response, and indicate required inputs for arguments
 * and input object fields.
 *
 * @see https://facebook.github.io/graphql/draft/#sec-Type-Kinds.Non-Null
 */
case class NullableType(
  ofType: Type
) extends Type {
  override def toString: String = s"$ofType?"
}

/**
 * The `Field` type represents each field in an Object or Interface type.
 *
 * @see https://facebook.github.io/graphql/draft/#sec-The-__Field-Type
 */
case class Field(
  name: String,
  description: Option[String],
  args: List[InputValue],
  tpe: Type,
  isDeprecated: Boolean,
  deprecationReason: Option[String]
)

/**
 * @param defaultValue a String encoding (using the GraphQL language) of the default value used by
 *                     this input value in the condition a value is not provided at runtime.
 */
case class InputValue(
  name: String,
  description: Option[String],
  tpe: Type,
  defaultValue: Option[Value]
)

sealed trait Value

object Value {

  case class IntValue(value: Int) extends Value

  case class FloatValue(value: Double) extends Value

  case class StringValue(value: String) extends Value

  case class BooleanValue(value: Boolean) extends Value

  case class IDValue(value: String) extends Value

  case class UntypedEnumValue(name: String) extends Value

  case class TypedEnumValue(value: EnumValue) extends Value

  case class UntypedVariableValue(name: String) extends Value

  case class ListValue(elems: List[Value]) extends Value

  case class ObjectValue(fields: List[(String, Value)]) extends Value

  case object NullValue extends Value

  case object AbsentValue extends Value

  object StringListValue {
    def apply(ss: List[String]): Value =
      ListValue(ss.map(StringValue(_)))

    def unapply(value: Value): Option[List[String]] =
      value match {
        case ListValue(l) => l.traverse {
          case StringValue(s) => Some(s)
          case _ => None
        }
        case _ => None
      }
  }

  def checkValue(iv: InputValue, value: Option[Value]): Result[Value] =
    (iv.tpe.dealias, value) match {
      case (_, None) if iv.defaultValue.isDefined =>
        iv.defaultValue.get.success
      case (_: NullableType, None) =>
        AbsentValue.success
      case (_: NullableType, Some(AbsentValue)) =>
        AbsentValue.success
      case (_: NullableType, Some(NullValue)) =>
        NullValue.success
      case (NullableType(tpe), Some(_)) =>
        checkValue(iv.copy(tpe = tpe), value)
      case (IntType, Some(value: IntValue)) =>
        value.success
      case (FloatType, Some(value: FloatValue)) =>
        value.success
      case (StringType, Some(value: StringValue)) =>
        value.success
      case (BooleanType, Some(value: BooleanValue)) =>
        value.success

      // Custom Scalars
      case (s @ ScalarType(_, _), Some(value: IntValue)) if !s.isBuiltIn =>
        value.success
      case (s @ ScalarType(_, _), Some(value: FloatValue)) if !s.isBuiltIn =>
        value.success
      case (s @ ScalarType(_, _), Some(value: StringValue)) if !s.isBuiltIn =>
        value.success
      case (s @ ScalarType(_, _), Some(value: BooleanValue)) if !s.isBuiltIn =>
        value.success

      case (IDType, Some(value: IDValue)) =>
        value.success
      case (IDType, Some(StringValue(s))) =>
        IDValue(s).success
      case (IDType, Some(IntValue(i))) =>
        IDValue(i.toString).success
      case (_: EnumType, Some(value: TypedEnumValue)) =>
        value.success
      case (e: EnumType, Some(UntypedEnumValue(name))) if e.hasValue(name) =>
        TypedEnumValue(e.value(name).get).success
      case (ListType(tpe), Some(ListValue(arr))) =>
        arr.traverse { elem =>
          checkValue(iv.copy(tpe = tpe, defaultValue = None), Some(elem))
        }.map(ListValue.apply)
      case (InputObjectType(nme, _, ivs), Some(ObjectValue(fs))) =>
        val obj = fs.toMap
        val unknownFields = fs.map(_._1).filterNot(f => ivs.exists(_.name == f))
        if (unknownFields.nonEmpty)
          Result.failure(s"Unknown field(s) ${unknownFields.map(s => s"'$s'").mkString("", ", ", "")} in input object value of type ${nme}")
        else
          ivs.traverse(iv => checkValue(iv, obj.get(iv.name)).map(v => (iv.name, v))).map(ObjectValue.apply)
      case (_: ScalarType, Some(value)) => value.success
      case (tpe, Some(value)) => Result.failure(s"Expected $tpe found '$value' for '${iv.name}'")
      case (tpe, None) => Result.failure(s"Value of type $tpe required for '${iv.name}'")
    }

  def checkVarValue(iv: InputValue, value: Option[Json]): Result[Value] = {
    import JsonExtractor._

    (iv.tpe.dealias, value) match {
      case (_, None) if iv.defaultValue.isDefined =>
        iv.defaultValue.get.success
      case (_: NullableType, None) =>
        AbsentValue.success
      case (_: NullableType, Some(jsonNull(_))) =>
        NullValue.success
      case (NullableType(tpe), Some(_)) =>
        checkVarValue(iv.copy(tpe = tpe), value)
      case (IntType, Some(jsonInt(value))) =>
        IntValue(value).success
      case (FloatType, Some(jsonDouble(value))) =>
        FloatValue(value).success
      case (StringType, Some(jsonString(value))) =>
        StringValue(value).success
      case (BooleanType, Some(jsonBoolean(value))) =>
        BooleanValue(value).success
      case (IDType, Some(jsonInt(value))) =>
        IDValue(value.toString).success

      // Custom scalars
      case (s @ ScalarType(_, _), Some(jsonInt(value))) if !s.isBuiltIn =>
        IntValue(value).success
      case (s @ ScalarType(_, _), Some(jsonDouble(value))) if !s.isBuiltIn =>
        FloatValue(value).success
      case (s @ ScalarType(_, _), Some(jsonString(value))) if !s.isBuiltIn =>
        StringValue(value).success
      case (s @ ScalarType(_, _), Some(jsonBoolean(value))) if !s.isBuiltIn =>
        BooleanValue(value).success

      case (IDType, Some(jsonString(value))) =>
        IDValue(value).success
      case (e: EnumType, Some(jsonString(name))) if e.hasValue(name) =>
        TypedEnumValue(e.value(name).get).success
      case (ListType(tpe), Some(jsonArray(arr))) =>
        arr.traverse { elem =>
          checkVarValue(iv.copy(tpe = tpe, defaultValue = None), Some(elem))
        }.map(vs => ListValue(vs.toList))
      case (InputObjectType(nme, _, ivs), Some(jsonObject(obj))) =>
        val unknownFields = obj.keys.filterNot(f => ivs.exists(_.name == f))
        if (unknownFields.nonEmpty)
          Result.failure(s"Unknown field(s) ${unknownFields.map(s => s"'$s'").mkString("", ", ", "")} in input object value of type ${nme}")
        else
          ivs.traverse(iv => checkVarValue(iv, obj(iv.name)).map(v => (iv.name, v))).map(ObjectValue.apply)
      case (_: ScalarType, Some(jsonString(value))) => StringValue(value).success
      case (tpe, Some(value)) => Result.failure(s"Expected $tpe found '$value' for '${iv.name}'")
      case (tpe, None) => Result.failure(s"Value of type $tpe required for '${iv.name}'")
    }
  }
}

/**
 * The `Directive` type represents a Directive that a server supports.
 *
 * @see https://facebook.github.io/graphql/draft/#sec-The-__Directive-Type
 */
case class Directive(
  name: String,
  description: Option[String],
  locations: List[Ast.DirectiveLocation],
  args: List[InputValue],
  isRepeatable: Boolean
)

/**
 * GraphQL schema parser
 */
object SchemaParser {

  import Ast.{Directive => DefinedDirective, Type => _, Value => _, _}
  import OperationType._

  /**
   * Parse a query String to a query algebra term.
   *
   * Yields a Query value on the right and accumulates errors on the left.
   */
  def parseText(text: String)(implicit pos: SourcePos): Result[Schema] = {
    def toResult[T](pr: Either[Parser.Error, T]): Result[T] =
      Result.fromEither(pr.leftMap(_.expected.toList.mkString(",")))

    for {
      doc <- toResult(GraphQLParser.Document.parseAll(text))
      query <- parseDocument(doc)
    } yield query
  }

  def parseDocument(doc: Document)(implicit sourcePos: SourcePos): Result[Schema] = {
    object schema extends Schema {
      var types: List[NamedType] = Nil
      var schemaType1: Option[NamedType] = null
      var pos: SourcePos = sourcePos

      override def schemaType: NamedType = schemaType1.getOrElse(super.schemaType)

      var directives: List[Directive] = Nil

      def complete(types0: List[NamedType], schemaType0: Option[NamedType], directives0: List[Directive]): Unit = {
        types = types0
        schemaType1 = schemaType0
        directives = directives0
      }
    }

    val defns: List[TypeDefinition] = doc.collect { case tpe: TypeDefinition => tpe }
    for {
      types      <- mkTypeDefs(schema, defns)
      schemaType <- mkSchemaType(schema, doc)
      _          =  schema.complete(types, schemaType, Nil)
      _          <- SchemaValidator.validateSchema(schema, defns)
    } yield schema
  }

  // explicit Schema type, if any
  def mkSchemaType(schema: Schema, doc: Document): Result[Option[NamedType]] = {
    def mkRootOperationType(rootTpe: RootOperationTypeDefinition): Result[(OperationType, Type)] = {
      val RootOperationTypeDefinition(optype, tpe) = rootTpe
      mkType(schema)(tpe).flatMap {
        case NullableType(nt: NamedType) => (optype, nt).success
        case other => Result.failure(s"Root operation types must be named types, found $other")
      }
    }

    def build(query: Type, mutation: Option[Type], subscription: Option[Type]): NamedType = {
      def mkRootDef(fieldName: String)(tpe: Type): Field =
        Field(fieldName, None, Nil, tpe, false, None)

      ObjectType(
        name = "Schema",
        description = None,
        fields =
          mkRootDef("query")(query) ::
            List(
              mutation.map(mkRootDef("mutation")),
              subscription.map(mkRootDef("subscription"))
            ).flatten,
        interfaces = Nil
      )
    }

    def defaultQueryType = schema.ref("Query")

    val defns = doc.collect { case schema: SchemaDefinition => schema }
    defns match {
      case Nil => None.success
      case SchemaDefinition(rootTpes, _) :: Nil =>
        rootTpes.traverse(mkRootOperationType).map { ops0 =>
          val ops = ops0.toMap
          Some(build(ops.get(Query).getOrElse(defaultQueryType), ops.get(Mutation), ops.get(Subscription)))
        }

      case _ => Result.failure("At most one schema definition permitted")
    }
  }

  def mkTypeDefs(schema: Schema, defns: List[TypeDefinition]): Result[List[NamedType]] =
    defns.traverse(mkTypeDef(schema))

  def mkTypeDef(schema: Schema)(td: TypeDefinition): Result[NamedType] = td match {
    case ScalarTypeDefinition(Name("Int"), _, _) => IntType.success
    case ScalarTypeDefinition(Name("Float"), _, _) => FloatType.success
    case ScalarTypeDefinition(Name("String"), _, _) => StringType.success
    case ScalarTypeDefinition(Name("Boolean"), _, _) => BooleanType.success
    case ScalarTypeDefinition(Name("ID"), _, _) => IDType.success
    case ScalarTypeDefinition(Name(nme), desc, _) => ScalarType(nme, desc).success
    case ObjectTypeDefinition(Name(nme), desc, fields0, ifs0, _) =>
      if (fields0.isEmpty) Result.failure(s"object type $nme must define at least one field")
      else
        for {
          fields <- fields0.traverse(mkField(schema))
          ifs = ifs0.map { case Ast.Type.Named(Name(nme)) => schema.ref(nme) }
        } yield ObjectType(nme, desc, fields, ifs)
    case InterfaceTypeDefinition(Name(nme), desc, fields0, ifs0, _) =>
      if (fields0.isEmpty) Result.failure(s"interface type $nme must define at least one field")
      else
        for {
          fields <- fields0.traverse(mkField(schema))
          ifs = ifs0.map { case Ast.Type.Named(Name(nme)) => schema.ref(nme) }
        } yield InterfaceType(nme, desc, fields, ifs)
    case UnionTypeDefinition(Name(nme), desc, _, members0) =>
      if (members0.isEmpty) Result.failure(s"union type $nme must define at least one member")
      else {
        val members = members0.map { case Ast.Type.Named(Name(nme)) => schema.ref(nme) }
        UnionType(nme, desc, members).success
      }
    case EnumTypeDefinition(Name(nme), desc, _, values0) =>
      if (values0.isEmpty) Result.failure(s"enum type $nme must define at least one enum value")
      else
        for {
          values <- values0.traverse(mkEnumValue)
        } yield EnumType(nme, desc, values)
    case InputObjectTypeDefinition(Name(nme), desc, fields0, _) =>
      if (fields0.isEmpty) Result.failure(s"input object type $nme must define at least one input field")
      else
        for {
          fields <- fields0.traverse(mkInputValue(schema))
        } yield InputObjectType(nme, desc, fields)
  }

  def mkField(schema: Schema)(f: FieldDefinition): Result[Field] = {
    val FieldDefinition(Name(nme), desc, args0, tpe0, dirs) = f
    for {
      args <- args0.traverse(mkInputValue(schema))
      tpe <- mkType(schema)(tpe0)
      deprecation <- parseDeprecated(dirs)
      (isDeprecated, reason) = deprecation
    } yield Field(nme, desc, args, tpe, isDeprecated, reason)
  }

  def mkType(schema: Schema)(tpe: Ast.Type): Result[Type] = {
    def loop(tpe: Ast.Type, nullable: Boolean): Result[Type] = {
      def wrap(tpe: Type): Type = if (nullable) NullableType(tpe) else tpe

      tpe match {
        case Ast.Type.List(tpe) => loop(tpe, true).map(tpe => wrap(ListType(tpe)))
        case Ast.Type.NonNull(Left(tpe)) => loop(tpe, false)
        case Ast.Type.NonNull(Right(tpe)) => loop(tpe, false)
        case Ast.Type.Named(Name(nme)) => wrap(ScalarType.builtIn(nme).getOrElse(schema.ref(nme))).success
      }
    }

    loop(tpe, true)
  }

  def mkInputValue(schema: Schema)(f: InputValueDefinition): Result[InputValue] = {
    val InputValueDefinition(Name(nme), desc, tpe0, default0, _) = f
    for {
      tpe <- mkType(schema)(tpe0)
      dflt <- default0.traverse(parseValue)
    } yield InputValue(nme, desc, tpe, dflt)
  }

  def mkEnumValue(e: EnumValueDefinition): Result[EnumValue] = {
    val EnumValueDefinition(Name(nme), desc, dirs) = e
    for {
      deprecation <- parseDeprecated(dirs)
      (isDeprecated, reason) = deprecation
    } yield EnumValue(nme, desc, isDeprecated, reason)
  }

  def parseDeprecated(directives: List[DefinedDirective]): Result[(Boolean, Option[String])] =
    directives.collect { case dir@DefinedDirective(Name("deprecated"), _) => dir } match {
      case Nil => (false, None).success
      case DefinedDirective(_, List((Name("reason"), Ast.Value.StringValue(reason)))) :: Nil => (true, Some(reason)).success
      case DefinedDirective(_, Nil) :: Nil => (true, Some("No longer supported")).success
      case DefinedDirective(_, _) :: Nil => Result.failure(s"deprecated must have a single String 'reason' argument, or no arguments")
      case _ => Result.failure(s"Only a single deprecated allowed at a given location")
    }

  // Share with Query parser
  def parseValue(value: Ast.Value): Result[Value] = {
    value match {
      case Ast.Value.IntValue(i) => IntValue(i).success
      case Ast.Value.FloatValue(d) => FloatValue(d).success
      case Ast.Value.StringValue(s) => StringValue(s).success
      case Ast.Value.BooleanValue(b) => BooleanValue(b).success
      case Ast.Value.EnumValue(e) => UntypedEnumValue(e.value).success
      case Ast.Value.Variable(v) => UntypedVariableValue(v.value).success
      case Ast.Value.NullValue => NullValue.success
      case Ast.Value.ListValue(vs) => vs.traverse(parseValue).map(ListValue.apply)
      case Ast.Value.ObjectValue(fs) =>
        fs.traverse { case (name, value) =>
          parseValue(value).map(v => (name.value, v))
        }.map(ObjectValue.apply)
    }
  }
}

object SchemaValidator {
  import SchemaRenderer.renderType

  def validateSchema(schema: Schema, defns: List[TypeDefinition]): Result[Unit] =
    validateReferences(schema, defns) *>
    validateUniqueDefns(schema) *>
    validateUniqueEnumValues(schema) *>
    validateImplementations(schema)

  def validateReferences(schema: Schema, defns: List[TypeDefinition]): Result[Unit] = {
    def underlyingName(tpe: Ast.Type): String =
      tpe match {
        case Ast.Type.List(tpe) => underlyingName(tpe)
        case Ast.Type.NonNull(Left(tpe)) => underlyingName(tpe)
        case Ast.Type.NonNull(Right(tpe)) => underlyingName(tpe)
        case Ast.Type.Named(Ast.Name(nme)) => nme
      }

    def referencedTypes(defns: List[TypeDefinition]): List[String] = {
      defns.flatMap {
        case ObjectTypeDefinition(_, _, fields, interfaces, _) =>
          (fields.flatMap(_.args.map(_.tpe)) ++ fields.map(_.tpe) ++ interfaces).map(underlyingName)
        case InterfaceTypeDefinition(_, _, fields, interfaces, _) =>
          (fields.flatMap(_.args.map(_.tpe)) ++ fields.map(_.tpe) ++ interfaces).map(underlyingName)
        case u: UnionTypeDefinition =>
          u.members.map(underlyingName)
        case _ => Nil
      }
    }

    val defaultTypes = List(StringType, IntType, FloatType, BooleanType, IDType)
    val typeNames = (defaultTypes ++ schema.types).map(_.name).toSet

    val problems =
      referencedTypes(defns).collect {
        case tpe if !typeNames.contains(tpe) => Problem(s"Reference to undefined type '$tpe'")
      }

    Result.fromProblems(problems)
  }

  def validateUniqueDefns(schema: Schema): Result[Unit] = {
    val dupes = schema.types.groupBy(_.name).collect {
      case (nme, tpes) if tpes.length > 1 => nme
    }.toSet

    val problems = schema.types.map(_.name).distinct.collect {
      case nme if dupes.contains(nme) => Problem(s"Duplicate definition of type '$nme' found")
    }

    Result.fromProblems(problems)
  }

  def validateUniqueEnumValues(schema: Schema): Result[Unit] = {
    val enums = schema.types.collect {
      case e: EnumType => e
    }

    val problems =
      enums.flatMap { e =>
        val duplicateValues = e.enumValues.groupBy(_.name).collect { case (nme, vs) if vs.length > 1 => nme }.toList
        duplicateValues.map(dupe => Problem(s"Duplicate definition of enum value '$dupe' for Enum type '${e.name}'"))
      }

    Result.fromProblems(problems)
  }

  def validateImplementations(schema: Schema): Result[Unit] = {

    def validateImplementor(impl: TypeWithFields): List[Problem] = {
      import impl.{name, fields, interfaces}

      interfaces.flatMap(_.dealias match {
        case iface: InterfaceType =>
          iface.fields.flatMap { ifField =>
            fields.find(_.name == ifField.name).map { implField =>
              val ifTpe = ifField.tpe
              val implTpe = implField.tpe

              val rp =
                if (implTpe <:< ifTpe) Nil
                else List(Problem(s"Field '${implField.name}' of type '$name' has type '${renderType(implTpe)}', however implemented interface '${iface.name}' requires it to be a subtype of '${renderType(ifTpe)}'"))

              val argsMatch =
                implField.args.corresponds(ifField.args) { case (arg0, arg1) =>
                  arg0.name == arg1.name && arg0.tpe == arg1.tpe
                }

              val ap =
                if (argsMatch) Nil
                else List(Problem(s"Field '${implField.name}' of type '$name' has has an argument list that does not conform to that specified by implemented interface '${iface.name}'"))

              rp ++ ap
            }.getOrElse(List(Problem(s"Field '${ifField.name}' from interface '${iface.name}' is not defined by implementing type '$name'")))
          }
        case other =>
          List(Problem(s"Non-interface type '${other.name}' declared as implemented by type '$name'"))
      })
    }

    val impls = schema.types.collect { case impl: TypeWithFields => impl }
    Result.fromProblems(impls.flatMap(validateImplementor))
  }
}

object SchemaRenderer {
  def renderSchema(schema: Schema): String = {
    def mkRootDef(fieldName: String)(tpe: NamedType): String =
      s"$fieldName: ${tpe.name}"

    val fields =
      mkRootDef("query")(schema.queryType) ::
        List(
          schema.mutationType.map(mkRootDef("mutation")),
          schema.subscriptionType.map(mkRootDef("subscription"))
        ).flatten

    val schemaDefn =
      if (fields.sizeCompare(1) == 0 && schema.queryType =:= schema.ref("Query")) ""
      else fields.mkString("schema {\n  ", "\n  ", "\n}\n")

    schemaDefn ++
      schema.types.map(renderTypeDefn).mkString("\n")
  }

  def renderTypeDefn(tpe: NamedType): String = {
    def renderField(f: Field): String = {
      val Field(nme, _, args, tpe, isDeprecated, reason) = f
      val dep = renderDeprecation(isDeprecated, reason)
      if (args.isEmpty)
        s"$nme: ${renderType(tpe)}" + dep
      else
        s"$nme(${args.map(renderInputValue).mkString(", ")}): ${renderType(tpe)}" + dep
    }

    tpe match {
      case tr: TypeRef => renderTypeDefn(tr.dealias)

      case ScalarType(nme, _) =>
        s"""scalar $nme"""

      case ObjectType(nme, _, fields, ifs0) =>
        val ifs = if (ifs0.isEmpty) "" else " implements " + ifs0.map(_.name).mkString("&")

        s"""|type $nme$ifs {
            |  ${fields.map(renderField).mkString("\n  ")}
            |}""".stripMargin

      case InterfaceType(nme, _, fields, ifs0) =>
        val ifs = if (ifs0.isEmpty) "" else " implements " + ifs0.map(_.name).mkString("&")

        s"""|interface $nme$ifs {
            |  ${fields.map(renderField).mkString("\n  ")}
            |}""".stripMargin

      case UnionType(nme, _, members) =>
        s"""union $nme = ${members.map(_.name).mkString(" | ")}"""

      case EnumType(nme, _, values) =>
        s"""|enum $nme {
            |  ${values.map(renderEnumValue).mkString("\n  ")}
            |}""".stripMargin

      case InputObjectType(nme, _, fields) =>
        s"""|input $nme {
            |  ${fields.map(renderInputValue).mkString("\n  ")}
            |}""".stripMargin
    }
  }

  def renderType(tpe: Type): String = {
    def loop(tpe: Type, nullable: Boolean): String = {
      def wrap(tpe: String) = if (nullable) tpe else s"$tpe!"

      tpe match {
        case NullableType(tpe) => loop(tpe, true)
        case ListType(tpe) => wrap(s"[${loop(tpe, false)}]")
        case nt: NamedType => wrap(nt.name)
      }
    }

    loop(tpe, false)
  }

  def renderEnumValue(v: EnumValue): String = {
    val EnumValue(nme, _, isDeprecated, reason) = v
    s"$nme" + renderDeprecation(isDeprecated, reason)
  }

  def renderInputValue(iv: InputValue): String = {
    val InputValue(nme, _, tpe, default) = iv
    val df = default.map(v => s" = ${renderValue(v)}").getOrElse("")
    s"$nme: ${renderType(tpe)}$df"
  }

  def renderValue(value: Value): String = value match {
    case IntValue(i) => i.toString
    case FloatValue(f) => f.toString
    case StringValue(s) => s""""$s""""
    case BooleanValue(b) => b.toString
    case IDValue(i) => s""""$i""""
    case TypedEnumValue(e) => e.name
    case ListValue(elems) => elems.map(renderValue).mkString("[", ", ", "]")
    case ObjectValue(fields) =>
      fields.map {
        case (name, value) => s"$name : ${renderValue(value)}"
      }.mkString("{", ", ", "}")
    case _ => "null"
  }

  def renderDeprecation(isDeprecated: Boolean, reason: Option[String]): String =
    if (isDeprecated) " @deprecated" + reason.fold("")(r => "(reason: \"" + r + "\")") else ""
}
