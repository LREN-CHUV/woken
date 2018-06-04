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

package ch.chuv.lren.woken

import ch.chuv.lren.woken.config.{ AlgorithmDefinition, AlgorithmEngine }
import ch.chuv.lren.woken.messages.query.{ AlgorithmSpec, CodeValue, ExecutionPlan }

/**
  * Some predefined objects to be used in the tests
  */
object Predefined {

  object Algorithms {

    val knnWithK5: AlgorithmSpec = AlgorithmSpec(
      code = "knn",
      parameters = List(CodeValue("k", "5")),
      step = None
    )

    val knnDefinition = AlgorithmDefinition(
      "knn",
      "hbpmip/python-knn",
      predictive = true,
      variablesCanBeNull = false,
      covariablesCanBeNull = false,
      engine = AlgorithmEngine.Docker,
      distributedExecutionPlan = ExecutionPlan.scatterGather
    )

    val anovaFactorial = AlgorithmSpec("anova", List(CodeValue("design", "factorial")), None)

    val anovaDefinition = AlgorithmDefinition(
      "anova",
      "hbpmip/python-anova",
      predictive = false,
      variablesCanBeNull = false,
      covariablesCanBeNull = false,
      engine = AlgorithmEngine.Docker,
      distributedExecutionPlan = ExecutionPlan.scatterGather
    )
  }

}
