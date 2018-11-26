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

package ch.chuv.lren.woken.core.model

import ch.chuv.lren.woken.messages.query.ExecutionPlan

/** Engine to use when executing the algorithm.
  *
  * Values are:
  * - Docker
  * - Validation
  */
object AlgorithmEngine extends Enumeration {
  type AlgorithmEngine = Value

  // read-write and read-only.
  val Docker, Validation = Value
}

import AlgorithmEngine.AlgorithmEngine

/** Definition of an algorithm, including its capabilities and runtime behaviour.
  *
  * @param code Code identifying the algorithm
  * @param dockerImage Name of the Docker image, including its version number
  * @param predictive Is the algorithm predictive?
  * @param variablesCanBeNull Can the target variables be null? Useful for supervised learning type of algorithms.
  * @param covariablesCanBeNull Can the independant variables be null?
  * @param engine Engine for the execution of the algorithm. Values are Docker, Validation
  * @param distributedExecutionPlan Execution plan to use when running the algorithm in a distributed mode.
  */
case class AlgorithmDefinition(code: String,
                               dockerImage: String,
                               predictive: Boolean,
                               variablesCanBeNull: Boolean,
                               covariablesCanBeNull: Boolean,
                               engine: AlgorithmEngine,
                               distributedExecutionPlan: ExecutionPlan
                               // TODO: shape of intermediate results, we assume Python serialization for now
)
