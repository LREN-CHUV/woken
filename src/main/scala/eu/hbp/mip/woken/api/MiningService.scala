/*
 * Copyright 2017 Human Brain Project MIP by LREN CHUV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hbp.mip.woken.api

import akka.actor.{ ActorRef, ActorRefFactory, ActorSystem }
import com.typesafe.config.ConfigFactory
import eu.hbp.mip.woken.authentication.BasicAuthentication
import eu.hbp.mip.woken.config.{
  DatabaseConfiguration,
  JobsConfiguration,
  MetaDatabaseConfig,
  WokenConfig
}
import spray.http.MediaTypes._
import spray.http._
import spray.routing.Route
import eu.hbp.mip.woken.messages.external._
import eu.hbp.mip.woken.core._
import eu.hbp.mip.woken.dao.{ FeaturesDAL, JobResultsDAL }

object MiningService {

  // TODO Gather this information from all the containers
  val methods_mock =
    """
        {
            "algorithms": [
            {
                "code": "histograms",
                "label": "Histograms",
                "type": ["statistics"],
                "environment": "Python",
                "description": "Histograms...",
                "docker_image": "hbpmip/python-histograms:7be184a",
                "constraints": {
                    "variable": {
                      "real": true,
                      "integer": true,
                      "binominal": false,
                      "polynominal": false
                    }
                }
            },
            {
                "code": "statisticsSummary",
                "label": "Statistics Summary",
                "type": ["statistics"],
                "environment": "R",
                "description": "Statistics Summary...",
                "docker_image": "hbpmip/r-summary-stats:52198fd",
                "constraints": {
                    "variable": {
                      "real": true,
                      "integer": true,
                      "binominal": false,
                      "polynominal": false
                    }
                }
            },
            {
                "code": "linearRegression",
                "label": "Linear Regression",
                "type": ["statistics"],
                "docker_image": "hbpmip/r-linear-regression:52198fd",
                "environment": "R",
                "description": "Standard Linear Regression...",
                "parameters": [],
                "constraints": {
                    "variable": {
                      "real": true,
                      "integer": true,
                      "binominal": false,
                      "polynominal": false
                    },
                    "groupings": {
                        "min_count": 0,
                        "max_count": 1
                    },
                    "covariables": {
                        "min_count": 0,
                        "max_count": null
                    },
                    "mixed": true
                }
            },
            {
                "code": "anova",
                "label": "Anova",
                "type": ["statistics"],
                "docker_image": "hbpmip/python-anova:0.3.1",
                "environment": "Python",
                "description": "ANOVA...",
                "parameters": [{
                    "code": "design",
                    "label": "design",
                    "default_value": "factorial",
                    "type": "enumeration",
                    "values": ["factorial", "additive"],
                    "description": "The type of multi-factors design. Choose 'factorial' to enable interactions analysis or 'additive' for a model without no interaction at all."
                }],
                "constraints": {
                    "variable": {
                      "real": true,
                      "integer": true,
                      "binominal": false,
                      "polynominal": false
                    },
                    "groupings": {
                        "min_count": 1,
                        "max_count": null
                    },
                    "covariables": {
                        "min_count": 0,
                        "max_count": null
                    },
                    "mixed": true
                }
            },
            {
                "code": "knn",
                "label": "K-nearest neighbors",
                "type": ["predictive_model"],
                "docker_image": "hbpmip/java-rapidminer-knn:latest",
                "environment": "Java/RapidMiner",
                "description": "K-nearest neighbors...",
                "parameters": [{
                    "code": "k",
                    "label": "k",
                    "default_value": 5,
                    "type": "int",
                    "constraints": {
                        "min": 1,
                        "max": null
                    },
                    "description": "The number of closest neighbours to take into consideration. Typical values range from 2 to 10."
                }],
                "constraints": {
                    "variable": {
                      "real": true,
                      "integer": true,
                      "binominal": true,
                      "polynominal": true
                    },
                    "groupings": {
                        "min_count": 0,
                        "max_count": 0
                    },
                    "covariables": {
                        "min_count": "1",
                        "max_count": null
                    },
                    "mixed": false
                }
            },
            {
                "code": "gpr",
                "label": "Gaussian Process Regression",
                "type": ["predictive_model"],
                "environment": "Java/GPJ",
                "disable": true
            },
            {
                "code": "svm",
                "label": "SVM",
                "type": ["predictive_model"],
                "environment": "Java/RapidMiner",
                "disable": true
            },
            {
                "code": "ffneuralnet",
                "label": "Feedforward Neural Network",
                "type": ["predictive_model"],
                "environment": "Java/RapidMiner",
                "disable": true
            },
            {
                "code": "randomforest",
                "label": "Random Forest",
                "type": ["predictive_model"],
                "environment": "Java/RapidMiner",
                "disable": true
            },
            {
                "code": "naiveBayes",
                "label": "Naive Bayes",
                "type": ["predictive_model"],
                "docker_image": "hbpmip/java-rapidminer-naivebayes:latest",
                "environment": "Java/RapidMiner",
                "description": "Naive Bayes...",
                "parameters": [],
                "constraints": {
                    "variable": {
                      "real": false,
                      "integer": false,
                      "binominal": true,
                      "polynominal": true
                    },
                    "groupings": {
                        "min_count": 0,
                        "max_count": 0
                    },
                    "covariables": {
                        "min_count": 1,
                        "max_count": null
                    },
                    "mixed": false
                }
            },
            {
                "code": "tSNE",
                "label": "tSNE",
                "disable": true,
                "type": ["features_extraction"],
                "docker_image": "hbpmip/r-tsne:latest",
                "environment": "R",
                "description": "tSNE...",
                "parameters": [],
                "constraints": {
                    "variable": {
                      "real": true,
                      "integer": true,
                      "binominal": true,
                      "polynominal": true
                    },
                    "groupings": {
                        "min_count": 0,
                        "max_count": 0
                    },
                    "covariables": {
                        "min_count": 1,
                        "max_count": null
                    },
                    "mixed": false
                }
            }
            ],
            "validations": [{
                "code": "kFoldCrossValidation",
                "label": "Random k-fold Cross Validation",
                "parameters": [{
                    "code": "fold",
                    "label": "Fold",
                    "default_value": 5,
                    "type": "int",
                    "constraints": {
                        "min": 2,
                        "max": 20
                    },
                    "description": "The number of cross-validation fold"
                }]
            }],
            "metrics": {
                "regression": [
                    {
                        "code": "MSE",
                        "label": "Mean square error",
                        "type": "numeric",
                        "tooltip": "To be completed"
                    },
                    {
                        "code": "RMSE" ,
                        "label": "Root mean square error",
                        "type": "numeric",
                        "tooltip": "To be completed"
                    },
                    {
                        "code": "MAE",
                        "label": "Mean absolute error",
                        "type": "numeric",
                        "tooltip": "To be completed"
                    },
                    {
                        "code": "R-squared",
                        "label": "Coefficient of determination (R²)",
                        "type": "numeric",
                        "tooltip": "To be completed"
                    },
                    {
                        "code": "Explained variance",
                        "label": "Explained variance",
                        "type": "numeric",
                        "tooltip": "To be completed"
                    }
                ],
                "binominal_classification": [
                    {
                        "code": "Confusion matrix",
                        "label": "Confusion matrix",
                        "type": "confusion_matrix",
                        "tooltip": "To be completed"
                    },
                    {
                        "code": "Accuracy",
                        "label": "Mean square error",
                        "type": "numeric",
                        "tooltip": "To be completed"
                    },
                    {
                        "code": "Precision",
                        "label": "Root mean square error",
                        "type": "numeric",
                        "tooltip": "To be completed"
                    },
                    {
                        "code": "Sensitivity",
                        "label": "Mean absolute error",
                        "type": "numeric",
                        "tooltip": "To be completed"
                    },
                    {
                        "code": "False positive rate",
                        "label": "False positive rate",
                        "type": "numeric",
                        "tooltip": "To be completed"
                    }
                ],
                "classification": [
                    {
                        "code": "Confusion matrix",
                        "label": "Confusion matrix",
                        "type": "confusion_matrix",
                        "tooltip": "To be completed"
                    },
                    {
                        "code": "Accuracy",
                        "label": "Accuracy",
                        "type": "numeric",
                        "tooltip": "To be completed"
                    },
                    {
                        "code": "Weighted Precision",
                        "label": "Weighted Precision",
                        "type": "numeric",
                        "tooltip": "To be completed"
                    },
                    {
                        "code": "Weighted Recall",
                        "label": "Weighted Recall",
                        "type": "numeric",
                        "tooltip": "To be completed"
                    },
                    {
                        "code": "Weighted F1-score",
                        "label": "Weighted F1-score",
                        "type": "numeric",
                        "tooltip": "To be completed"
                    },
                    {
                        "code": "Weighted false positive rate",
                        "label": "Weighted false positive rate",
                        "type": "numeric",
                        "tooltip": "To be completed"
                    }
                ]
            }
        }
  """
}

// this trait defines our service behavior independently from the service actor
class MiningService(val chronosService: ActorRef,
                    val featuresDatabase: FeaturesDAL,
                    val resultDatabase: JobResultsDAL,
                    val metaDbConfig: MetaDatabaseConfig,
                    val jobsConf: JobsConfiguration,
                    val defaultFeaturesTable: String)(implicit system: ActorSystem)
    extends MiningServiceApi
    with PerRequestCreator
    with DefaultJsonFormats
    with BasicAuthentication {

  override def context: ActorRefFactory = system

  implicit val executionContext = context.dispatcher

  val routes: Route = mining ~ experiment ~ listMethods

  import ApiJsonSupport._
  import CoordinatorActor._

  implicit object EitherErrorSelector extends ErrorSelector[ErrorResponse.type] {
    def apply(v: ErrorResponse.type): StatusCode = StatusCodes.BadRequest
  }

  // TODO: improve passing configuration around
  private lazy val config = ConfigFactory.load()
  private lazy val coordinatorConfig = CoordinatorConfig(chronosService,
                                                         featuresDatabase,
                                                         resultDatabase,
                                                         WokenConfig.app.dockerBridgeNetwork,
                                                         jobsConf,
                                                         DatabaseConfiguration.factory(config))

  override def listMethods: Route = path("mining" / "list-methods") {
    authenticate(basicAuthenticator) { user =>
      import spray.json._

      get {
        respondWithMediaType(`application/json`) {
          complete(MiningService.methods_mock.parseJson.compactPrint)
        }
      }
    }
  }

  override def mining: Route = path("mining" / "job") {
    authenticate(basicAuthenticator) { user =>
      import FunctionsInOut._

      post {
        entity(as[MiningQuery]) {
          case MiningQuery(variables, covariables, groups, filters, Algorithm(c, n, p))
              if c == "" || c == "data" =>
            ctx =>
              {
                ctx.complete(
                  featuresDatabase.queryData(defaultFeaturesTable, {
                    variables ++ covariables ++ groups
                  }.distinct.map(_.code))
                )
              }

          case query: MiningQuery =>
            val job = miningQuery2job(metaDbConfig)(query)
            miningJob(coordinatorConfig) {
              Start(job)
            }
        }
      }
    }
  }

  override def experiment: Route = path("mining" / "experiment") {
    authenticate(basicAuthenticator) { user =>
      import FunctionsInOut._

      post {
        entity(as[ExperimentQuery]) { query: ExperimentQuery =>
          {
            val job = experimentQuery2job(metaDbConfig)(query)
            experimentJob(coordinatorConfig) {
              ExperimentActor.Start(job)
            }
          }
        }
      }
    }
  }

  def newCoordinatorActor(coordinatorConfig: CoordinatorConfig): ActorRef =
    context.actorOf(CoordinatorActor.props(coordinatorConfig))

  def newExperimentActor(coordinatorConfig: CoordinatorConfig): ActorRef =
    context.actorOf(ExperimentActor.props(coordinatorConfig))

  def miningJob(coordinatorConfig: CoordinatorConfig)(message: RestMessage): Route =
    ctx => perRequest(ctx, newCoordinatorActor(coordinatorConfig), message)

  def experimentJob(coordinatorConfig: CoordinatorConfig)(message: RestMessage): Route =
    ctx => perRequest(ctx, newExperimentActor(coordinatorConfig), message)

}
