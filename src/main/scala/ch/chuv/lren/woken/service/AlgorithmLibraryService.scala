/*
 * Copyright (C) 2017  LREN CHUV for Human Brain Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ch.chuv.lren.woken.service

import spray.json._

// TODO: merge/provide with AlgorithmLookup ?

class AlgorithmLibraryService {

  // TODO Gather this information from all the containers
  private val methods_mock =
    """
        {
            "algorithms": [
            {
                "code": "histograms",
                "label": "Histograms",
                "type": ["statistics"],
                "environment": "Python",
                "description": "Histograms...",
                "docker_image": "hbpmip/python-histograms:0.3.7",
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
                "environment": "python",
                "description": "Statistics Summary...",
                "docker_image": "hbpmip/python-summary-statistics:0.3.0",
                "constraints": {
                    "variable": {
                      "real": true,
                      "integer": true,
                      "binominal": true,
                      "polynominal": true
                    }
                }
            },
            {
                "code": "linearRegression",
                "label": "Linear Regression",
                "type": ["statistics"],
                "docker_image": "hbpmip/python-linear-regression:0.0.7",
                "environment": "Python",
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
                "docker_image": "hbpmip/python-anova:0.3.6",
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
                "docker_image": "hbpmip/java-rapidminer-knn:0.2.2",
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
                "docker_image": "hbpmip/java-rapidminer-naivebayes:0.2.1",
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
                "disable": false,
                "type": ["features_extraction"],
                "maturity": "experimental",
                "docker_image": "hbpmip/python-tsne:0.4.0",
                "environment": "Python",
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
            },
            {
                "code": "ggparci",
                "label": "ggparci",
                "disable": false,
                "type": ["dataset_exploration"],
                "maturity": "experimental",
                "docker_image": "hbpmip/r-ggparci:0.2.0",
                "environment": "R",
                "description": "Parallel coordinate plot with added confidence interval bands",
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
                    "mixed": true
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
  """.parseJson.asJsObject

  def algorithms(): JsObject = methods_mock

}

object AlgorithmLibraryService {

  def apply(): AlgorithmLibraryService = new AlgorithmLibraryService()

}
