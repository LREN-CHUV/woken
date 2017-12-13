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

package eu.hbp.mip.woken.config

import com.typesafe.config.Config
import eu.hbp.mip.woken.cromwell.core.ConfigUtil._
import cats.data.Validated._
import cats.implicits._

case class AlgorithmDefinition(code: String, dockerImage: String, predictive: Boolean)

// TODO: this should feed AlgorithmLibraryService with metadata

object AlgorithmsConfiguration {

  def read(config: Config, path: Seq[String]): Validation[AlgorithmDefinition] = {
    val algoConfig = config.validateConfig(path.mkString("."))

    algoConfig.andThen { c: Config =>
      val code        = path.lastOption.map(lift).getOrElse("Empty path".invalidNel[String])
      val dockerImage = c.validateString("dockerImage")
      val predictive  = c.validateBoolean("predictive")

      (code, dockerImage, predictive) mapN AlgorithmDefinition.apply
    }
  }

  def factory(config: Config): String => Validation[AlgorithmDefinition] =
    algorithm => read(config, List("algorithms", algorithm))

}
