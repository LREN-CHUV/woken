package core.validation

import com.opendatagroup.hadrian.jvmcompiler.PFAEngine
import org.scalatest._
import spray.json
import spray.json.{JsNumber, JsObject, JsString}

class KFoldCrossValidationTest extends FlatSpec with Matchers {

  "A simple regression model" should "validate" in {

    var model =
      """
     {
        |  "input": {
        |    "doc": "Input is the list of covariables and groups",
        |    "name": "DependentVariables",
        |    "type":"map",
        |    "values": "double"
        |  },
        |  "output": {
        |    "doc": "Output is the estimate of the variable",
        |    "type": "double"
        |  },
        |  "cells": {
        |    "model": {
        |      "type": {
        |        "name": "knn_model",
        |        "type":"record",
        |        "fields": [
        |          {
        |            "name": "k",
        |            "type": "int"
        |          },
        |          {
        |            "name": "samples",
        |            "type":{
        |              "type": "array",
        |              "items": {
        |                "type": "record",
        |                "name": "Sample",
        |                "fields": [
        |                  {
        |                    "name": "vars",
        |                    "type":{
        |                      "type": "array",
        |                      "items": "double"
        |                    }
        |                  },
        |                  {
        |                    "name": "label",
        |                    "type": "double"
        |                  }
        |                ]
        |              }
        |            }
        |          }
        |        ]
        |      },
        |      "init": {
        |        "k": 2,
        |        "samples": [{"vars":[1.0, 1.0], "label": 10.0},{"vars":[2.0, 2.0], "label": 20.0}]
        |      }
        |    }
        |  },
        |
        |  "fcns": {
        |    "toArray": {
        |      "params": [
        |        {
        |          "m": {
        |            "type": "map",
        |            "values": "double"
        |          }
        |        }
        |      ],
        |      "ret": {"type": "array", "items": "double"},
        |      "do": [
        |        {"let": {"input_map": "m"}},
        |        {
        |          "a.map": [
        |            {"type": {"type": "array", "items": "string"},
        |              "value": ["v1", "v2"]},
        |            {
        |              "params": [
        |                {
        |                  "key": {
        |                    "type": "string"
        |                  }
        |                }
        |              ],
        |              "ret": "double",
        |              "do": [
        |                {"attr": "input_map", "path": ["key"]}
        |              ]
        |            }
        |          ]
        |        }
        |      ]
        |    }
        |  },
        |
        |  "action": [
        |    {
        |      "let": {"model": {"cell": "model"}}
        |    },
        |    {
        |      "let": {
        |        "knn":
        |        {
        |          "model.neighbor.nearestK": [
        |            "model.k",
        |            {"u.toArray": ["input"]},
        |            "model.samples",
        |            {
        |              "params": [
        |                {
        |                  "x": {
        |                    "type": "array",
        |                    "items": "double"
        |                  }
        |                },
        |                {
        |                  "y": "Sample"
        |                }
        |              ],
        |              "ret": "double",
        |              "do": {
        |                "metric.simpleEuclidean": [
        |                  "x",
        |                  "y.vars"
        |                ]
        |              }
        |            }
        |          ]
        |        }
        |      }
        |    },
        |    {
        |      "let": {"label_list": {"type": {"type": "array", "items": "double"},
        |        "value": []}}
        |    },
        |    {
        |      "foreach": "neighbour",
        |      "in": "knn",
        |      "do": [
        |        {"set": {"label_list": {"a.append": ["label_list", "neighbour.label"]}}}
        |      ]
        |    },
        |    {
        |      "a.mean": ["label_list"]
        |    }
        |  ]
        |}
      """.stripMargin

    val engine = PFAEngine.fromJson(model).head

    val data =
      JsObject(
        "v1" -> JsNumber(1),
        "v2" -> JsNumber(2)) ::
        JsObject(
          "v1" -> JsNumber(2),
          "v2" -> JsNumber(3)) ::
        JsObject(
          "v1" -> JsNumber(1),
          "v2" -> JsNumber(5)) ::
        List()

    val labels: List[JsObject] = JsObject("output" -> JsNumber("1.0")) :: JsObject("output" -> JsNumber("10.0")) :: JsObject("output" -> JsNumber("1.0")) :: List()
    val models = Map("0" -> model, "1" -> model, "2" -> model)
    val validation = new KFoldCrossValidation(data.toStream, labels.toStream, 3)
    val results = validation.validate(models)

    println(results)

    /*results shouldBe a JsObject
    results.asJsObject.fields should contain ("kind" -> JsString("regression"))
    results.size should be (3)*/
  }

  "An experiment JSON object " should "be readable" in {

    import spray.json._
    import api.ApiJsonSupport._

    val source = """{"variables":[{"code":"LeftAmygdala"}],"grouping":[{"code":"COLPROT"}], "covariables":[{"code":"AGE"}], "filters":[], "algorithms":[{"code":"linearRegression", "label": "linearRegression", "parameters": []}], "validations":[{"code":"kfold", "label": "kfold", "parameters": [{"code": "k", "value": "2"}]}]}"""
    val jsonAst = source.parseJson
    val validation = jsonAst.convertTo[api.ExperimentQuery]

    println(validation)
  }

  "A validation JSON object " should "be readable" in {

    import spray.json._
    import api.ApiJsonSupport._

    val source = """{"code":"kfold", "label": "kfold", "parameters": [{"code": "k", "value": "2"}]}"""
    val jsonAst = source.parseJson
    val validation = jsonAst.convertTo[api.Validation]

    println(validation)
  }

  "Metrics " should "be correct" in {

    import org.scalactic.TolerantNumerics
    implicit val doubleEquality = TolerantNumerics.tolerantDoubleEquality(0.01)

    var r2 = new R2()
    var rmse = new RMSE()

    var y = Array[Double](123.56, 0.67, 1078.42, 64.2, 1.76, 1.23)
    var f = Array[Double](15.6, 0.0051, 23.5, 0.421, 1.2, 0.0325)

    y.zip(f).foreach({case (y, f) => r2.update(y, f); rmse.update(y, f)})

    r2.get() should equal (0.0)
    rmse.get() should equal (433.7)

    r2 = new R2()
    rmse = new RMSE()

    y = Array[Double](123.56, 0.67, 1078.42, 64.2, 1.76, 1.23)
    f = Array[Double](165.3, 1.65, 700.23, 66.7, 0.5, 2.3)

    y.zip(f).foreach({case (y, f) => r2.update(y, f); rmse.update(y, f)})

    r2.get() should equal (0.84153)
    rmse.get() should equal (155.34)
  }
}