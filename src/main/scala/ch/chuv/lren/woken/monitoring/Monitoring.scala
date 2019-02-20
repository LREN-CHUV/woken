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
import ch.chuv.lren.woken.akka.{ AkkaServer, CoreSystem }
import ch.chuv.lren.woken.config.WokenConfiguration
import com.typesafe.scalalogging.Logger
import kamon.Kamon
import kamon.prometheus.PrometheusReporter
import kamon.sigar.SigarProvisioner
import kamon.system.SystemMetrics
import kamon.zipkin.ZipkinReporter
import org.hyperic.sigar.{ Sigar, SigarLoader }

import scala.concurrent.ExecutionContext
import scala.language.higherKinds
import scala.util.{ Failure, Success, Try }

case class Monitoring[F[_]: ConcurrentEffect: Timer](core: CoreSystem, config: WokenConfiguration) {

  import Monitoring.logger

  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  def startReporters(): Unit = {
    val kamonConfig = config.config.getConfig("kamon")

    if (kamonConfig.getBoolean("enabled") || kamonConfig.getBoolean("prometheus.enabled") || kamonConfig
          .getBoolean("zipkin.enabled")) {

      logger.debug("Kamon configuration:")
      logger.debug(kamonConfig.toString)

      logger.info(s"Start monitoring...")

      Kamon.reconfigure(config.config)

      val hostSystemMetrics = kamonConfig.getBoolean("system-metrics.host.enabled")
      val jvmSystemMetrics  = kamonConfig.getBoolean("system-metrics.jvm.enabled")

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

      if (hostSystemMetrics || jvmSystemMetrics) {
        logger.info(s"Start collection of system metrics...")
        SystemMetrics.startCollecting()
      }

      if (kamonConfig.getBoolean("prometheus.enabled"))
        Kamon.addReporter(new PrometheusReporter)

      if (kamonConfig.getBoolean("zipkin.enabled"))
        Kamon.addReporter(new ZipkinReporter)
    }
  }

  def unbind(): F[Unit] = Sync[F].defer {
    logger.info("Stop monitoring")
    logger.warn("Stopping here", new Exception("Stopping system"))

    val kamonConfig       = config.config.getConfig("kamon")
    val hostSystemMetrics = kamonConfig.getBoolean("system-metrics.host.enabled")
    val jvmSystemMetrics  = kamonConfig.getBoolean("system-metrics.jvm.enabled")

    if (hostSystemMetrics || jvmSystemMetrics) {
      SystemMetrics.stopCollecting()
    }

    implicit val ec: ExecutionContext = ExecutionContext.global
    val ending                        = Kamon.stopAllReporters()
    Async[F].async { cb =>
      ending.onComplete {
        case Success(_)     => cb(Right(()))
        case Failure(error) => cb(Left(error))
      }
    }
  }
}

object Monitoring {

  private val logger: Logger = Logger("woken.Monitoring")

  /** Resource that creates and yields monitoring services with Kamon, guaranteeing cleanup. */
  def resource[F[_]: ConcurrentEffect: ContextShift: Timer](
      akkaServer: AkkaServer[F],
      config: WokenConfiguration
  ): Resource[F, Monitoring[F]] =
    // start the Kamon monitoring services
    Resource.make(Sync[F].delay {
      val monitoring = new Monitoring(akkaServer, config)
      monitoring.startReporters()

      logger.info("[OK] Monitoring Woken")
      monitoring
    })(_.unbind())

}
