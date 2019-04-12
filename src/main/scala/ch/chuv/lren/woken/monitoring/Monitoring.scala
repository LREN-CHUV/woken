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
import kamon.util.Registration
import kamon.zipkin.ZipkinReporter
import org.hyperic.sigar.{ Sigar, SigarLoader }

import scala.concurrent.ExecutionContext
import scala.language.higherKinds
import scala.util.{ Failure, Success, Try }

trait Monitoring[F[_]] {
  def unbind(): F[Unit]
}

class MonitoringImpl[F[_]: Async](
    core: CoreSystem,
    config: WokenConfiguration,
    reporterRegistrations: List[Registration]
) extends Monitoring[F] {

  import Monitoring.logger

  def unbind(): F[Unit] = Sync[F].defer {
    logger.info("Stop monitoring")

    val kamonConfig       = config.config.getConfig("kamon")
    val hostSystemMetrics = kamonConfig.getBoolean("system-metrics.host.enabled")
    val jvmSystemMetrics  = kamonConfig.getBoolean("system-metrics.jvm.enabled")

    if (hostSystemMetrics || jvmSystemMetrics) {
      SystemMetrics.stopCollecting()
    }

    reporterRegistrations.foreach(_.cancel())

    val ending = Kamon.stopAllReporters()
    Async[F].async { cb =>
      implicit val ec: ExecutionContext = ExecutionContext.global
      ending.onComplete {
        case Success(_)     => cb(Right(()))
        case Failure(error) => cb(Left(error))
      }
    }
  }
}

class NoMonitoring[F[_]: Async] extends Monitoring[F] {
  def unbind(): F[Unit] = Sync[F].unit
}

object Monitoring {

  private[monitoring] val logger: Logger = Logger("woken.Monitoring")

  /** Resource that creates and yields monitoring services with Kamon, guaranteeing cleanup. */
  def resource[F[_]: ConcurrentEffect: ContextShift: Timer](
      akkaServer: AkkaServer[F],
      config: WokenConfiguration
  ): Resource[F, Monitoring[F]] =
    // start the Kamon monitoring services
    Resource.make(Sync[F].delay {
      val monitoring = startMonitoring(akkaServer, config)

      logger.info("[OK] Monitoring Woken")
      monitoring
    })(_.unbind())

  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  private def startMonitoring[F[_]: Async](
      core: CoreSystem,
      config: WokenConfiguration
  ): Monitoring[F] = {
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

      val reporterRegistrations: List[Registration] =
        (if (kamonConfig.getBoolean("prometheus.enabled"))
           List(Kamon.addReporter(new PrometheusReporter))
         else Nil) ++
          (if (kamonConfig.getBoolean("zipkin.enabled"))
             List(Kamon.addReporter(new ZipkinReporter))
           else Nil)

      new MonitoringImpl[F](core, config, reporterRegistrations)
    } else {
      new NoMonitoring[F]
    }
  }
}
