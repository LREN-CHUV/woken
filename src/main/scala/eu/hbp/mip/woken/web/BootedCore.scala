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

package eu.hbp.mip.woken.web

import scala.concurrent.duration._
import akka.actor.{ ActorRef, ActorRefFactory, ActorSystem }
import akka.util.Timeout
import akka.cluster.Cluster
import akka.cluster.client.ClusterClientReceptionist
import akka.http.scaladsl.Http
import akka.pattern.{ Backoff, BackoffSupervisor }
import cats.effect.IO
import eu.hbp.mip.woken.api.{ Api, MasterRouter }
import eu.hbp.mip.woken.config.{
  AlgorithmsConfiguration,
  AppConfiguration,
  DatabaseConfiguration,
  DatasetsConfiguration
}
import eu.hbp.mip.woken.core.{ CoordinatorConfig, Core, CoreActors }
import eu.hbp.mip.woken.dao.{ FeaturesDAL, MetadataRepositoryDAO, WokenRepositoryDAO }
import eu.hbp.mip.woken.service.{
  AlgorithmLibraryService,
  DispatcherService,
  JobResultService,
  VariablesMetaService
}
import eu.hbp.mip.woken.ssl.WokenSSLConfiguration
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings, Supervision }
import eu.hbp.mip.woken.backends.woken.WokenService
import org.slf4j.LoggerFactory

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
    with WokenSSLConfiguration {

  private val logger = LoggerFactory.getLogger("BootedCore")

  override lazy val appConfig: AppConfiguration = AppConfiguration
    .read(config)
    .getOrElse(throw new IllegalStateException("Invalid configuration"))

  logger.info(s"Starting actor system ${appConfig.clusterSystemName}")

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
    .getOrElse(throw new IllegalStateException("Invalid configuration"))

  private lazy val featuresDbConnection = DatabaseConfiguration
    .factory(config)(jobsConf.featuresDb)
    .getOrElse(throw new IllegalStateException("Invalid configuration"))

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
    .getOrElse(throw new IllegalStateException("Invalid configuration"))

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
    binding
      .flatMap(_.unbind())
      .flatMap(_ => system.terminate())
      .onComplete(_ => ())
  }

  logger.info("Woken startup complete")

  ()

}
