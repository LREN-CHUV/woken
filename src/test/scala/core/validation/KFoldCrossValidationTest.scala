package core.validation

import org.scalatest._
import spray.json.{JsNumber, JsObject, JsArray}

import eu.hbp.mip.messages.external.{ExperimentQuery, Validation}

class KFoldCrossValidationTest extends FlatSpec with Matchers {

  "An experiment JSON object " should "be readable" in {

    import api.ApiJsonSupport._
    import spray.json._

    val source = """{"variables":[{"code":"LeftAmygdala"}],"grouping":[{"code":"COLPROT"}], "covariables":[{"code":"AGE"}], "filters":[], "algorithms":[{"code":"linearRegression", "name": "linearRegression", "parameters": []}], "validations":[{"code":"kfold", "name": "kfold", "parameters": [{"code": "k", "value": "2"}]}]}"""
    val jsonAst = source.parseJson
    val validation = jsonAst.convertTo[ExperimentQuery]

    println(validation)
  }

  "A validation JSON object " should "be readable" in {

    import api.ApiJsonSupport._
    import spray.json._

    val source = """{"code":"kfold", "name": "kfold", "parameters": [{"code": "k", "value": "2"}]}"""
    val jsonAst = source.parseJson
    val validation = jsonAst.convertTo[Validation]

    println(validation)
  }
}