package api

import java.time.OffsetDateTime

import core.{JobResults, RestMessage}
import core.model.JobResult
import spray.http.StatusCodes
import spray.httpx.marshalling.ToResponseMarshaller
import spray.json.{RootJsonFormat, DefaultJsonProtocol, JsValue}

/**
  * A group object represents a variable scope. Each variable is associated to a group.
  * Each group can be contained in other group. The group chaining can be interpreted like a hierarchy.
  */
case class Group(
  /** Unique group code */
  code: String,
  /** Group label */
  label: String,
  /** Sub groups */
  groups: List[Subgroup] // TODO: rename to subgroups
)

case class Subgroup(
  /** Unique group code */
  code: String,
  /** Group label */
  label: String
)

case class VariableId(
  /** Unique variable code, used to request */
  code: String
)

case class Variable(
  /** Unique variable code, used to request */
  code: String,
  /** Variable label, used to display */
  label: String,
  /** Variable group (only the variable path) */
  group: Group
/* .... and so on ... */
)

case class Dataset(
  /** Unique code identifying the dataset */
  code: String,
  date: OffsetDateTime,
  header: JsValue,
  data: JsValue
)

object Operators extends Enumeration {
  type Operators = Value
  val eq = Value("eq")
  val lt = Value("lt")
  val gt = Value("gt")
  val lte = Value("lte")
  val gte = Value("gte")
  val neq = Value("neq")
  val in = Value("in")
  val notin = Value("notin")
  val between = Value("between")
}

case class Filter(
  variable: VariableId,
  operator: Operators.Operators,
  values: Seq[String]
)

case class Query(
  variables: Seq[VariableId],
  covariables: Seq[VariableId],
  grouping: Seq[VariableId],
  filters: Seq[Filter],
  algorithm: String
)

/*

  Filter:
    type: object
    description: A filter in a query
    properties:
      variable:
        description: |
          Variable used to filter, only code value is sent
        '$ref': '#/definitions/VariableId'
      operator:
        description: |
          Filter operator : eq, lt, gt, gte, lte, neq, in, notin, between.
        type: string
        enum:
          - eq
          - lt
          - gt
          - gte
          - lte
          - neq
          - in
          - notin
          - between
      values:
        description: |
          List of values used to filter.
          With operators “eq”, “lt”, “gt”, “gte”, “lte”, ”neq”, the filter mode “OR” is used.
          With operator “between”, only two values are sent, they represents the range limits.
        type: array
        items:
          type: string

  Query:
    type: object
    description: |
      A query object represents a request to the CHUV API.
      This object contains all information required by the API to compute a result (dataset) and return it.
    properties:
      variables:
        description: |
          List of variables used by the request, only code values are sent
        type: array
        items:
          $ref: '#/definitions/VariableId'
      covariables:
        description: |
          List of covariables used by the request, only code values are sent.
          These variables are returned in dataset object header.
        type: array
        items:
          $ref: '#/definitions/VariableId'
      grouping:
        description: |
          List of grouping variables used by the request, only code values are sent.
        type: array
        items:
          $ref: '#/definitions/VariableId'
      filters:
        description: |
          List of filters objects used by the request.
        type: array
        items:
          $ref: '#/definitions/Filter'
      request:
        description: Plot type
        type: string

  Variable:
    type: object
    description: A variable object represents a business variable. All variable information should be stored in this object.
    properties:
      code:
        type: string
        description: |
          Unique variable code, used to request
      label:
        type: string
        description: |
          Variable label, used to display
      group:
        description: |
          Variable group (only the variable path)
        '$ref': '#/definitions/Group'
      type:
        type: string
        description: |
          I: Integer, T: Text, N: Decimal, D: Date, B: Boolean.
        enum:
          - I # Integer
          - T # Text
          - N # Decimal
          - D # Date
          - B # Boolean
      length:
        type: integer
        description: |
          For text, number of characters of value
      minValue:
        type: number
        description: |
          Minimum allowed value (for integer or numeric)
      maxValue:
        type: number
        description: |
          Maximum allowed value (for integer or numeric)
      units:
        type: string
        description: Variable unit
      isVariable:
        type: boolean
        description: Can the variable can be used as a variable
      isGrouping:
        type: boolean
        description: Can the variable can be used as a group
      isCovariable:
        type: boolean
        description: Can the variable can be used as a covariable
      isFilter:
        type: boolean
        description: Can the variable can be used as a filter
      values:
        description: |
          List of variable values (if is an enumeration variable).
        type: array
        items:
          $ref: '#/definitions/Value'
  Value:
    type: object
    description: A value object is a business variable value. All value information should be stored in this object.
    properties:
      code:
        type: string
        description: |
          Unique code of value (for variable), used to request
      label:
        type: string
        description: |
          Label of value, used to display

 */