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

package ch.chuv.lren.woken.kamon

import com.typesafe.config.{Config, ConfigValueFactory}
import com.typesafe.scalalogging.Logger
import kamon.Kamon
import kamon.prometheus.PrometheusReporter
import kamon.zipkin.ZipkinReporter
import org.slf4j.LoggerFactory

object KamonSupport {

  private val logger: Logger =
    Logger(LoggerFactory.getLogger("WokenKamonSupportInTest"))

  def startReporters(config: Config): Unit = {
    val kamonConfig = config
      .getConfig("kamon")

    if (kamonConfig.getBoolean("enabled") || kamonConfig.getBoolean(
      "prometheus.enabled") || kamonConfig
      .getBoolean("zipkin.enabled")) {

      logger.info("Kamon configuration:")
      logger.info(config.getConfig("kamon").toString)
      logger.info(s"Start monitoring...")

      Kamon.reconfigure(config.withValue("kamon.trace.join-remote-parents-with-same-span-id",
        ConfigValueFactory.fromAnyRef("yes"))
      )

      if (kamonConfig.getBoolean("zipkin.enabled"))
        Kamon.addReporter(new ZipkinReporter)
       Kamon.addReporter(new PrometheusReporter)
    }
  }

}
