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

package ch.chuv.lren.woken.monitoring

import java.io.File

import cats.effect._
import ch.chuv.lren.woken.akka.AkkaServer
import ch.chuv.lren.woken.config.WokenConfiguration
import ch.chuv.lren.woken.core.Core
import com.typesafe.scalalogging.Logger
import kamon.Kamon
import kamon.prometheus.PrometheusReporter
import kamon.sigar.SigarProvisioner
import kamon.system.SystemMetrics
import kamon.zipkin.ZipkinReporter
import org.hyperic.sigar.{ Sigar, SigarLoader }
import org.slf4j.LoggerFactory

import scala.language.higherKinds
import scala.util.Try

case class KamonMonitoring[F[_]: ConcurrentEffect: Timer](core: Core, config: WokenConfiguration) {

  private val logger: Logger =
    Logger(LoggerFactory.getLogger("woken.KamonMonitoring"))

  def startReporters(): Unit = {
    val kamonConfig = config.config.getConfig("kamon")

    if (kamonConfig.getBoolean("enabled") || kamonConfig.getBoolean("prometheus.enabled") || kamonConfig
          .getBoolean("zipkin.enabled")) {

      logger.info("Kamon configuration:")
      logger.info(kamonConfig.toString)
      logger.info(s"Start monitoring...")

      Kamon.reconfigure(config.config)

      val hostSystemMetrics = kamonConfig.getBoolean("system-metrics.host.enabled")
      if (hostSystemMetrics) {
        logger.info(s"Start Sigar metrics...")
        Try {
          val sigarLoader = new SigarLoader(classOf[Sigar])
          sigarLoader.load()
        }

        Try(
          SigarProvisioner.provision(
            new File(System.getProperty("user.home") + File.separator + ".native")
          )
        ).recover { case e: Exception => logger.warn("Cannot provision Sigar", e) }

        if (SigarProvisioner.isNativeLoaded)
          logger.info("Sigar metrics are available")
        else
          logger.warn("Sigar metrics are not available")
      }

      if (hostSystemMetrics || kamonConfig.getBoolean("system-metrics.jvm.enabled")) {
        logger.info(s"Start collection of system metrics...")
        SystemMetrics.startCollecting()
      }

      if (kamonConfig.getBoolean("prometheus.enabled"))
        Kamon.addReporter(new PrometheusReporter)

      if (kamonConfig.getBoolean("zipkin.enabled"))
        Kamon.addReporter(new ZipkinReporter)
    }
  }

  def unbind(): F[Unit] = Sync[F].delay {
    SystemMetrics.stopCollecting()
    Kamon.stopAllReporters()
  }

}

object KamonMonitoring {

  /** Resource that creates and yields monitoring services with Kamon, guaranteeing cleanup. */
  def resource[F[_]: ConcurrentEffect: ContextShift: Timer](
      akkaServer: AkkaServer[F],
      config: WokenConfiguration
  ): Resource[F, KamonMonitoring[F]] =
    // start a new HTTP server with our service actor as the handler
    Resource.make(Sync[F].delay(new KamonMonitoring(akkaServer, config)))(_.unbind())

}
