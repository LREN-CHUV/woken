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
import akka.actor.{
  ActorRef,
  ActorRefFactory,
  ActorSystem,
  Address,
  ExtendedActorSystem,
  Extension,
  ExtensionKey,
  Props
}
import akka.util.Timeout
import akka.cluster.Cluster
import akka.http.scaladsl.Http
import cats.effect.IO
import eu.hbp.mip.woken.api.{ Api, MasterRouter }
import eu.hbp.mip.woken.config.{ AlgorithmsConfiguration, AppConfiguration, DatabaseConfiguration }
import eu.hbp.mip.woken.core.{ CoordinatorConfig, Core, CoreActors }
import eu.hbp.mip.woken.core.validation.ValidationPoolManager
import eu.hbp.mip.woken.dao.{ FeaturesDAL, MetadataRepositoryDAO, WokenRepositoryDAO }
import eu.hbp.mip.woken.service.{ AlgorithmLibraryService, JobResultService, VariablesMetaService }
import eu.hbp.mip.woken.ssl.WokenSSLConfiguration
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer

class RemoteAddressExtensionImpl(system: ExtendedActorSystem) extends Extension {
  def getAddress: Address =
    system.provider.getDefaultAddress
}

object RemoteAddressExtension extends ExtensionKey[RemoteAddressExtensionImpl]

/**
  * This trait implements ``Core`` by starting the required ``ActorSystem`` and registering the
  * termination handler to stop the system when the JVM exits.
  */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
trait BootedCore
    extends Core
    with CoreActors
    with Api
    with StaticResources
    with WokenSSLConfiguration {

  private lazy val appConfig = AppConfiguration
    .read(config)
    .getOrElse(throw new IllegalStateException("Invalid configuration"))

  /**
    * Construct the ActorSystem we will use in our application
    */

  override lazy implicit val system: ActorSystem    = ActorSystem(appConfig.systemName)
  lazy val actorRefFactory: ActorRefFactory         = system
  implicit val actorMaterializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext                     = system.dispatcher

  private lazy val resultsDbConfig = DatabaseConfiguration
    .factory(config)("woken")
    .getOrElse(throw new IllegalStateException("Invalid configuration"))

  private lazy val featuresDbConnection = DatabaseConfiguration
    .factory(config)(jobsConf.featuresDb)
    .getOrElse(throw new IllegalStateException("Invalid configuration"))

  private lazy val featuresDAL = FeaturesDAL(featuresDbConnection)

  private lazy val jrsIO: IO[JobResultService] = for {
    xa <- DatabaseConfiguration.dbTransactor(resultsDbConfig)
    _  <- DatabaseConfiguration.testConnection[IO](xa)
    wokenDb = new WokenRepositoryDAO[IO](xa)
  } yield {
    JobResultService(wokenDb.jobResults)
  }
  private lazy val jobResultService: JobResultService = jrsIO.unsafeRunSync()

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

  private lazy val variablesMetaService: VariablesMetaService = vmsIO.unsafeRunSync()

  private lazy val algorithmLibraryService: AlgorithmLibraryService = AlgorithmLibraryService()

  //Cluster(system).join(RemoteAddressExtension(system).getAddress())
  lazy val cluster = Cluster(system)

  private lazy val coordinatorConfig = CoordinatorConfig(
    chronosHttp,
    appConfig.dockerBridgeNetwork,
    featuresDAL,
    jobResultService,
    jobsConf,
    DatabaseConfiguration.factory(config)
  )

  /**
    * Create and start actor that acts as akka entry-point
    */
  val mainRouter: ActorRef =
    system.actorOf(
      MasterRouter.props(appConfig,
                         coordinatorConfig,
                         variablesMetaService,
                         algorithmLibraryService,
                         AlgorithmsConfiguration.factory(config)),
      name = "entrypoint"
    )

  /**
    * Create and start actor responsible to register validation node
    */
  val validationRegisterActor: ActorRef = system.actorOf(Props[ValidationPoolManager])

  implicit val timeout: Timeout = Timeout(5.seconds)

  Http().setDefaultServerHttpContext(https)

  // start a new HTTP server on port 8080 with our service actor as the handler
  val binding = Http().bindAndHandle(routes,
                                     interface = appConfig.networkInterface,
                                     port = appConfig.webServicesPort,
                                     connectionContext = https)

  /**
    * Ensure that the constructed ActorSystem is shut down when the JVM shuts down
    */
  sys.addShutdownHook {
    binding
      .flatMap(_.unbind())
      .onComplete(_ => system.terminate())
  }

}
