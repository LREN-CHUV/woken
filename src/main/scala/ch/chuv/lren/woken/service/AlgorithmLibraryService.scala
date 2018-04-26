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
                "docker_image": "hbpmip/python-histograms:0.5.0",
                "constraints": {
                    "variable": {
                      "real": true,
                      "integer": true,
                      "binominal": false,
                      "polynominal": false
                    },
                    "groupings": {
                        "min_count": 0,
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
                "code": "statisticsSummary",
                "label": "Statistics Summary",
                "type": ["statistics"],
                "environment": "python",
                "description": "Statistics Summary...",
                "docker_image": "hbpmip/python-summary-statistics:0.3.1",
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
                "docker_image": "hbpmip/python-linear-regression:0.2.0",
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
                        "min_count": 1,
                        "max_count": null
                    },
                    "mixed": true
                }
            },
            {
                "code": "sgdLinearModel",
                "label": "SGD Linear model",
                "type": ["predictive_model"],
                "docker_image": "hbpmip/python-sgd-linear-model:0.1.5",
                "environment": "Python",
                "description": "Linear model using Stochastic Gradient Descent...",
                "parameters": [{
                    "code": "alpha",
                    "label": "alpha",
                    "default_value": 0.0001,
                    "type": "number",
                    "constraints": {
                        "min": 0.0,
                        "max": null
                   },
                    "description": "Constant that multiplies the regularization term. Defaults to 0.0001 Also used to compute learning_rate when set to ‘optimal’."
                }, {
                    "code": "penalty",
                    "label": "penalty",
                    "default_value": "l2",
                    "type": "enumeration",
                    "values": ["none", "l2", "l1", "elasticnet"],
                    "description": "The penalty (aka regularization term) to be used. Defaults to ‘l2’ which is the standard regularizer for linear SVM models. ‘l1’ and ‘elasticnet’ might bring sparsity to the model (feature selection) not achievable with ‘l2’."
                }, {
                    "code": "l1_ratio",
                    "label": "l1_ratio",
                    "default_value": 0.15,
                    "type": "number",
                    "constraints": {
                        "min": 0.0,
                        "max": 1.0
                   },
                  "description": "The Elastic Net mixing parameter, with 0 <= l1_ratio <= 1. l1_ratio=0 corresponds to L2 penalty, l1_ratio=1 to L1. Defaults to 0.15."
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
                        "min_count": 1,
                        "max_count": null
                    },
                    "mixed": true
                }
            },
            {
                "code": "naiveBayes",
                "label": "Naive Bayes",
                "type": ["predictive_model"],
                "docker_image": "hbpmip/python-sgd-naive-bayes:0.1.5",
                "environment": "Python",
                "description": "Naive Bayes using Stochastic Gradient Descent",
                "parameters": [{
                    "code": "alpha",
                    "label": "alpha",
                    "default_value": 1.0,
                    "type": "number",
                    "constraints": {
                        "min": 0.0,
                        "max": 1.0
                   },
                    "description": "Additive (Laplace/Lidstone) smoothing parameter (0 for no smoothing, default to 1.)"
                }, {
                    "code": "class_prior",
                    "label": "class_prior",
                    "default_value": null,
                    "type": "string",
                    "description": "Prior probabilities of the classes. If specified the priors are not adjusted according to the data. Must be numbers between 0 and 1 and sum to 1. Pass real values separated by comma."
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
                        "min_count": 1,
                        "max_count": null
                    },
                    "mixed": true
                }
            },
            {
                "code": "sgdNeuralNetwork",
                "label": "SGD Neural Network",
                "type": ["predictive_model"],
                "docker_image": "hbpmip/python-sgd-neural-network:0.1.5",
                "environment": "Python",
                "description": "Neural Network using Stochastic Gradient Descent...",
                "parameters": [{
                    "code": "hidden_layer_sizes",
                    "label": "hidden_layer_sizes",
                    "default_value": "100",
                    "type": "string",
                    "description": "The ith element represents the number of neurons in the ith hidden layer. Pass integers separated by comma."
                }, {
                    "code": "activation",
                    "label": "activation",
                    "default_value": "relu",
                    "type": "enumeration",
                    "values": ["identity", "logistic", "tanh", "relu"],
                    "description": "Activation function for the hidden layer."
                }, {
                    "code": "alpha",
                    "label": "alpha",
                    "default_value": 0.0001,
                    "type": "number",
                    "constraints": {
                        "min": 0.0,
                        "max": null
                   },
                  "description": "L2 penalty (regularization term) parameter."
                }, {
                    "code": "learning_rate_init",
                    "label": "learning_rate_init",
                    "default_value": 0.001,
                    "type": "number",
                    "constraints": {
                        "min": 0.0,
                        "max": 1.0
                   },
                   "description": "The initial learning rate used. It controls the step-size in updating the weights."
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
                        "min_count": 1,
                        "max_count": null
                    },
                    "mixed": true
                }
            },
            {
                "code": "gradientBoosting",
                "label": "Gradient Boosting",
                "type": ["predictive_model"],
                "docker_image": "hbpmip/python-gradient-boosting:0.1.5",
                "environment": "Python",
                "description": "Gradient Boosting...",
                "parameters": [{
                    "code": "learning_rate",
                    "label": "learning_rate",
                    "default_value": 0.1,
                    "type": "number",
                    "constraints": {
                        "min": 0.0,
                        "max": 1.0
                    },
                    "description": "learning rate shrinks the contribution of each tree by learning_rate. There is a trade-off between learning_rate and n_estimators."
                }, {
                    "code": "n_estimators",
                    "label": "n_estimators",
                    "default_value": 100,
                    "type": "int",
                    "constraints": {
                        "min": 0,
                        "max": null
                    },
                    "description": "The number of boosting stages to perform. Gradient boosting is fairly robust to over-fitting so a large number usually results in better performance."
                }, {
                    "code": "max_depth",
                    "label": "max_depth",
                    "default_value": 3,
                    "type": "int",
                    "constraints": {
                        "min": 1,
                        "max": 10
                    },
                    "description": "maximum depth of the individual regression estimators. The maximum depth limits the number of nodes in the tree. Tune this parameter for best performance; the best value depends on the interaction of the input variables."
                }, {
                    "code": "min_samples_split",
                    "label": "min_samples_split",
                    "default_value": 2,
                    "type": "int",
                    "constraints": {
                        "min": 1,
                        "max": null
                    },
                    "description": "The minimum number of samples required to split an internal node."
                }, {
                    "code": "min_samples_leaf",
                    "label": "min_samples_leaf",
                    "default_value": 1,
                    "type": "int",
                    "constraints": {
                        "min": 1,
                        "max": null
                    },
                    "description": "The minimum number of samples required to be at a leaf node."
                }, {
                    "code": "min_weight_fraction_leaf",
                    "label": "min_weight_fraction_leaf",
                    "default_value": 0.0,
                    "type": "numeric",
                    "constraints": {
                        "min": 0.0,
                        "max": 1.0
                    },
                    "description": "The minimum weighted fraction of the sum total of weights (of all the input samples) required to be at a leaf node. Samples have equal weight when sample_weight is not provided."
                }, {
                    "code": "min_impurity_decrease",
                    "label": "min_impurity_decrease",
                    "default_value": 0.0,
                    "type": "numeric",
                    "constraints": {
                        "min": 0.0,
                        "max": null
                    },
                    "description": "A node will be split if this split induces a decrease of the impurity greater than or equal to this value."
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
                        "min_count": 1,
                        "max_count": null
                    },
                    "mixed": true
                }
            },
            {
                "code": "anova",
                "label": "Anova",
                "type": ["statistics"],
                "docker_image": "hbpmip/python-anova:0.4.0",
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
                        "min_count": 1,
                        "max_count": null
                    },
                    "mixed": true
                }
            },
            {
                "code": "knn",
                "label": "K-nearest neighbors",
                "type": ["predictive_model"],
                "docker_image": "hbpmip/python-knn:0.3.0",
                "environment": "Python",
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
            },            {
                "code": "correlationHeatmap",
                "label": "Correlation heatmap",
                "type": ["statistics"],
                "environment": "python",
                "description": "Correlation heatmap...",
                "docker_image": "hbpmip/python-correlation-heatmap:0.1.4",
                "constraints": {
                    "variable": {
                      "real": true,
                      "integer": true,
                      "binominal": false,
                      "polynominal": false
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
                "code": "hinmine",
                "label": "JSI HinMine",
                "type": ["features_extraction"],
                "docker_image": "hbpmip/python-jsi-hinmine:0.2.2",
                "environment": "Python",
                "description": "The HinMine algorithm is an algorithm designed to construct network-analysis-based feature vectors for data instances that can be either nodes in a network or standard data instances with a fixed set of numeric features. In this implementation, the input for the algorithm is a set of data instances, and the output of the algorithm is a new data set with the same instances, but new features constructed out of them.",
                "parameters": [{
                    "code": "damping",
                    "label": "damping",
                    "default_value": 0.1,
                    "type": "number",
                    "constraints": {
                        "min": 0.0,
                        "max": 1.0
                    },
                    "description": "The variable *p* used in the construction of the P-PR vectors during propositionalization. The value of this variable can be any real number between *0* and *1*. Smaller values of the damping factor ensure faster calculation of the feature vectors, however larger values of *p* mean that the algorithm is capable of performing longer walks, exploring more of the structure of the data."
                },{
                    "code": "normalize",
                    "label": "normalize",
                    "default_value": "False",
                    "type": "enumeration",
                    "values": ["False", "True"],
                    "description": "This variable determines whether the feature values of the input data instances should be normalized or not. If True, then the values of each feature are normalized to be between 0 and 1. This allows the algorithm to fairly compare two features measured with incomparable units. The value of this variable should be False if the difference in the size of the features carries inherent meaning."
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
                "code": "hedwig",
                "label": "JSI Hedwig",
                "type": ["features_extraction"],
                "docker_image": "hbpmip/python-jsi-hedwig:1.0.4",
                "environment": "Python",
                "description": "The Hedwig algorithm for subgroup discovery is a data mining algorithm designed for exploratory data analysis of a data set",
                "parameters": [{
                    "code": "beam",
                    "label": "bean",
                    "default_value": 10,
                    "type": "int",
                    "constraints": {
                        "min": 1,
                        "max": null
                    },
                    "description": "The size of the beam to be used in the search. Larger values of this variable cause the search of the algorithm to take longer and return more high quality rules."
                },{
                    "code": "support",
                    "label": "support",
                    "default_value": "0.1",
                    "type": "number",
                    "constraints": {
                        "min": 0.0,
                        "max": 1.0
                    },
                    "description": "The minimum relative support of the rules, discovered by Hedwig. The value of this parameter must be between 0 and 1 as the parameter represents the ration of the covered examples in the entire data set."
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
                "code": "tSNE",
                "label": "tSNE",
                "disable": false,
                "type": ["features_extraction"],
                "maturity": "experimental",
                "docker_image": "hbpmip/python-tsne:0.4.1",
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
