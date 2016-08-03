package eu.hbp.mip.validation

import com.opendatagroup.hadrian.jvmcompiler.PFAEngine
import org.scalatest._
import spray.json.{JsNumber, JsObject}

class ValidationActorTest extends FlatSpec with Matchers {

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
    //val validation = new KFoldCrossValidation(data.toStream, labels.toStream, 3)
    //val results = validation.validate(models)

    //println(results)

    /*results shouldBe a JsObject
    results.asJsObject.fields should contain ("kind" -> JsString("regression"))
    results.size should be (3)*/
  }
}