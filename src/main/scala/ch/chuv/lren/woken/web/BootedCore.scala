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

package ch.chuv.lren.woken.web

import java.io.File

import scala.concurrent.duration._
import akka.actor.ActorRef
import akka.util.Timeout
import akka.cluster.Cluster
import akka.cluster.client.ClusterClientReceptionist
import akka.cluster.pubsub.{ DistributedPubSub, DistributedPubSubMediator }
import akka.http.scaladsl.Http
import akka.pattern.{ Backoff, BackoffSupervisor }
import cats.effect.IO
import ch.chuv.lren.woken.api.{ Api, MasterRouter }
import ch.chuv.lren.woken.config.{
  AlgorithmsConfiguration,
  DatabaseConfiguration,
  DatasetsConfiguration
}
import ch.chuv.lren.woken.core.{ CoordinatorConfig, Core, CoreActors }
import ch.chuv.lren.woken.dao.{
  FeaturesDAL,
  FeaturesRepositoryDAO,
  MetadataRepositoryDAO,
  WokenRepositoryDAO
}
import ch.chuv.lren.woken.service._
import ch.chuv.lren.woken.ssl.WokenSSLConfiguration
import ch.chuv.lren.woken.backends.woken.WokenClientService
import ch.chuv.lren.woken.kamon.KamonSupport
import com.typesafe.scalalogging.LazyLogging
import kamon.Kamon
import kamon.prometheus.PrometheusReporter
import kamon.sigar.SigarProvisioner
import kamon.system.SystemMetrics
import kamon.zipkin.ZipkinReporter
import org.hyperic.sigar.{ Sigar, SigarLoader }

import scala.concurrent.Future
import scala.language.postfixOps
import scala.sys.ShutdownHookThread
import scala.util.Try

/**
  * This trait implements ``Core`` by starting the required ``ActorSystem`` and registering the
  * termination handler to stop the system when the JVM exits.
  *
  * @author Ludovic Claude <ludovic.claude@chuv.ch>
  */
@SuppressWarnings(Array("org.wartremover.warts.Throw", "org.wartremover.warts.NonUnitStatements"))
trait BootedCore
    extends Core
    with CoreActors
    with Api
    with StaticResources
    with WokenSSLConfiguration
    with LazyLogging {

  override def beforeBoot(): Unit =
    KamonSupport.startReporters(config)

  private lazy val resultsDbConfig = DatabaseConfiguration
    .factory(config)("woken")
    .valueOr(configurationFailed)

  private lazy val featuresDbConnection = DatabaseConfiguration
    .factory(config)(jobsConfig.featuresDb)
    .valueOr(configurationFailed)

  override lazy val featuresDAL = FeaturesDAL(featuresDbConnection)

  private lazy val fsIO: IO[FeaturesService] = for {
    xa <- DatabaseConfiguration.dbTransactor(featuresDbConnection)
    _  <- DatabaseConfiguration.testConnection[IO](xa)
    featuresDb = new FeaturesRepositoryDAO[IO](xa)
  } yield {
    FeaturesService(featuresDb)
  }
  private lazy val featuresService: FeaturesService = fsIO.unsafeRunSync()

  private lazy val jrsIO: IO[JobResultService] = for {
    xa <- DatabaseConfiguration.dbTransactor(resultsDbConfig)
    _  <- DatabaseConfiguration.testConnection[IO](xa)
    wokenDb = new WokenRepositoryDAO[IO](xa)
  } yield {
    JobResultService(wokenDb.jobResults)
  }
  override lazy val jobResultService: JobResultService = jrsIO.unsafeRunSync()

  override lazy val coordinatorConfig = CoordinatorConfig(
    chronosHttp,
    appConfig.dockerBridgeNetwork,
    featuresDAL,
    jobResultService,
    jobsConfig,
    DatabaseConfiguration.factory(config)
  )

  private lazy val datasetsService: DatasetService = ConfBasedDatasetService(config)

  private def mainRouterSupervisorProps = {

    val wokenService: WokenClientService = WokenClientService(coordinatorConfig.jobsConf.node)
    val dispatcherService: DispatcherService =
      DispatcherService(DatasetsConfiguration.datasets(config), wokenService)
    val algorithmLibraryService: AlgorithmLibraryService = AlgorithmLibraryService()

    val metaDbConfig = DatabaseConfiguration
      .factory(config)(jobsConfig.metaDb)
      .valueOr(configurationFailed)

    val vmsIO: IO[VariablesMetaService] = for {
      xa <- DatabaseConfiguration.dbTransactor(metaDbConfig)
      _  <- DatabaseConfiguration.testConnection[IO](xa)
      metaDb = new MetadataRepositoryDAO[IO](xa)
    } yield {
      VariablesMetaService(metaDb.variablesMeta)
    }

    val variablesMetaService: VariablesMetaService = vmsIO.unsafeRunSync()

    BackoffSupervisor.props(
      Backoff.onFailure(
        MasterRouter.props(
          config,
          appConfig,
          coordinatorConfig,
          datasetsService,
          variablesMetaService,
          dispatcherService,
          algorithmLibraryService,
          AlgorithmsConfiguration.factory(config)
        ),
        childName = "mainRouter",
        minBackoff = 100 milliseconds,
        maxBackoff = 1 seconds,
        randomFactor = 0.2
      )
    )
  }

  /**
    * Create and start actor that acts as akka entry-point
    */
  override lazy val mainRouter: ActorRef =
    system.actorOf(mainRouterSupervisorProps, name = "entrypoint")

  override lazy val cluster: Cluster = Cluster(system)

  override def startActors(): Unit = {
    logger.info(s"Start actor system ${appConfig.clusterSystemName}...")
    logger.info(s"Cluster has roles ${cluster.selfRoles.mkString(",")}")

    val mediator = DistributedPubSub(system).mediator

    mediator ! DistributedPubSubMediator.Put(mainRouter)

    ClusterClientReceptionist(system).registerService(mainRouter)

    monitorDeadLetters()
  }

  override def startServices(): Unit = {
    logger.info(s"Start web server on port ${appConfig.webServicesPort}")

    implicit val timeout: Timeout = Timeout(5.seconds)

    if (appConfig.webServicesHttps) Http().setDefaultServerHttpContext(https)

    // start a new HTTP server on port 8080 with our service actor as the handler
    val binding: Future[Http.ServerBinding] = Http().bindAndHandle(routes,
                                                                   interface =
                                                                     appConfig.networkInterface,
                                                                   port = appConfig.webServicesPort)

    /**
      * Ensure that the constructed ActorSystem is shut down when the JVM shuts down
      */
    val _: ShutdownHookThread = sys.addShutdownHook {
      SystemMetrics.stopCollecting()
      binding
        .flatMap(_.unbind())
        .flatMap(_ => system.terminate())
        .onComplete(_ => ())
    }

  }

  override def selfChecks(): Unit = {
    logger.info("Self checks...")

    logger.info("Check configuration of datasets...")

    datasetsService.datasets().filter(_.location.isEmpty).foreach { dataset =>
      dataset.tables.foreach { tableName =>
        val table = featuresService.featuresTable(tableName)
        if (table.count(dataset.dataset) == 0) {
          logger.error(
            s"Table $tableName contains no value for dataset ${dataset.dataset.code}"
          )
          System.exit(1)
        }
      }
    }

    logger.info("[OK] Self checks passed")
  }

}
