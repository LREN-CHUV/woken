package api

import java.time.OffsetDateTime

import spray.json.JsValue

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

/*
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