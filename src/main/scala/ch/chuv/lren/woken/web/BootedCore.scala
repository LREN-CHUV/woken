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

import scala.concurrent.duration._
import akka.actor.{ ActorRef, ActorRefFactory, ActorSystem }
import akka.util.Timeout
import akka.cluster.Cluster
import akka.cluster.client.ClusterClientReceptionist
import akka.http.scaladsl.Http
import akka.pattern.{ Backoff, BackoffSupervisor }
import cats.effect.IO
import ch.chuv.lren.woken.api.{ Api, MasterRouter }
import ch.chuv.lren.woken.config.{
  AlgorithmsConfiguration,
  AppConfiguration,
  DatabaseConfiguration,
  DatasetsConfiguration
}
import ch.chuv.lren.woken.core.{ CoordinatorConfig, Core, CoreActors }
import ch.chuv.lren.woken.dao.{ FeaturesDAL, MetadataRepositoryDAO, WokenRepositoryDAO }
import ch.chuv.lren.woken.service.{
  AlgorithmLibraryService,
  DispatcherService,
  JobResultService,
  VariablesMetaService
}
import ch.chuv.lren.woken.ssl.WokenSSLConfiguration
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings, Supervision }
import ch.chuv.lren.woken.backends.woken.WokenService
import com.typesafe.scalalogging.LazyLogging
import kamon.Kamon
import kamon.prometheus.PrometheusReporter
import kamon.system.SystemMetrics
import kamon.zipkin.ZipkinReporter

import scala.concurrent.{ ExecutionContextExecutor, Future }
import scala.language.postfixOps
import scala.sys.ShutdownHookThread

/**
  * This trait implements ``Core`` by starting the required ``ActorSystem`` and registering the
  * termination handler to stop the system when the JVM exits.
  */
@SuppressWarnings(Array("org.wartremover.warts.Throw", "org.wartremover.warts.NonUnitStatements"))
trait BootedCore
    extends Core
    with CoreActors
    with Api
    with StaticResources
    with WokenSSLConfiguration
    with LazyLogging {

  override lazy val appConfig: AppConfiguration = AppConfiguration
    .read(config)
    .valueOr(configurationFailed)

  logger.info(s"Starting actor system ${appConfig.clusterSystemName}")

  SystemMetrics.startCollecting()
  Kamon.addReporter(new PrometheusReporter)
  Kamon.addReporter(new ZipkinReporter)

  /**
    * Construct the ActorSystem we will use in our application
    */
  override lazy implicit val system: ActorSystem = ActorSystem(appConfig.clusterSystemName, config)
  lazy val actorRefFactory: ActorRefFactory      = system
  val decider: Supervision.Decider = {
    case err: RuntimeException =>
      logger.error(err.getMessage)
      Supervision.Resume
    case _ =>
      logger.error("Unknown error. Stopping the stream. ")
      Supervision.Stop
  }
  implicit lazy val actorMaterializer: ActorMaterializer = ActorMaterializer(
    ActorMaterializerSettings(system).withSupervisionStrategy(decider)
  )
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  private lazy val resultsDbConfig = DatabaseConfiguration
    .factory(config)("woken")
    .valueOr(configurationFailed)

  private lazy val featuresDbConnection = DatabaseConfiguration
    .factory(config)(jobsConf.featuresDb)
    .valueOr(configurationFailed)

  override lazy val featuresDAL = FeaturesDAL(featuresDbConnection)

  private lazy val jrsIO: IO[JobResultService] = for {
    xa <- DatabaseConfiguration.dbTransactor(resultsDbConfig)
    _  <- DatabaseConfiguration.testConnection[IO](xa)
    wokenDb = new WokenRepositoryDAO[IO](xa)
  } yield {
    JobResultService(wokenDb.jobResults)
  }
  override lazy val jobResultService: JobResultService = jrsIO.unsafeRunSync()

  private lazy val metaDbConfig = DatabaseConfiguration
    .factory(config)(jobsConf.metaDb)
    .valueOr(configurationFailed)

  private lazy val vmsIO: IO[VariablesMetaService] = for {
    xa <- DatabaseConfiguration.dbTransactor(metaDbConfig)
    _  <- DatabaseConfiguration.testConnection[IO](xa)
    metaDb = new MetadataRepositoryDAO[IO](xa)
  } yield {
    VariablesMetaService(metaDb.variablesMeta)
  }

  override lazy val variablesMetaService: VariablesMetaService = vmsIO.unsafeRunSync()

  private lazy val algorithmLibraryService: AlgorithmLibraryService = AlgorithmLibraryService()

  //Cluster(system).join(RemoteAddressExtension(system).getAddress())
  lazy val cluster = Cluster(system)

  override lazy val coordinatorConfig = CoordinatorConfig(
    chronosHttp,
    appConfig.dockerBridgeNetwork,
    featuresDAL,
    jobResultService,
    jobsConf,
    DatabaseConfiguration.factory(config)
  )

  private lazy val wokenService: WokenService = WokenService(coordinatorConfig.jobsConf.node)

  private lazy val dispatcherService: DispatcherService =
    DispatcherService(DatasetsConfiguration.datasets(config), wokenService)

  private lazy val mainRouterSupervisorProps = BackoffSupervisor.props(
    Backoff.onFailure(
      MasterRouter.props(appConfig,
                         coordinatorConfig,
                         variablesMetaService,
                         dispatcherService,
                         algorithmLibraryService,
                         AlgorithmsConfiguration.factory(config)),
      childName = "mainRouter",
      minBackoff = 100 milliseconds,
      maxBackoff = 1 seconds,
      randomFactor = 0.2
    )
  )

  /**
    * Create and start actor that acts as akka entry-point
    */
  override lazy val mainRouter: ActorRef =
    system.actorOf(mainRouterSupervisorProps, name = "entrypoint")

  ClusterClientReceptionist(system).registerService(mainRouter)

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
  val shutdownHook: ShutdownHookThread = sys.addShutdownHook {
    SystemMetrics.stopCollecting()
    binding
      .flatMap(_.unbind())
      .flatMap(_ => system.terminate())
      .onComplete(_ => ())
  }

  logger.info("Woken startup complete")

  ()

}
