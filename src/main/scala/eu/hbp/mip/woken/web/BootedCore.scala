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
import akka.actor.{ActorRef, ActorRefFactory, ActorSystem, Address, ExtendedActorSystem, Extension, ExtensionKey, Props}
import akka.io.IO
import akka.util.Timeout
import akka.cluster.Cluster
import cats.effect.IO
import com.typesafe.config.{Config, ConfigFactory}
import spray.can.Http
import eu.hbp.mip.woken.api.{Api, MasterRouter, RoutedHttpService}
import eu.hbp.mip.woken.config.{DatabaseConfiguration, MetaDatabaseConfig, ResultDatabaseConfig, WokenConfig}
import eu.hbp.mip.woken.core.{Core, CoreActors}
import eu.hbp.mip.woken.config.WokenConfig.app
import eu.hbp.mip.woken.core.validation.ValidationPoolManager
import eu.hbp.mip.woken.dao.{FeaturesDAL, NodeDAL, WokenRepositoryDAO}
import eu.hbp.mip.woken.service.JobResultService
import eu.hbp.mip.woken.ssl.WokenSSLConfiguration

class RemoteAddressExtensionImpl(system: ExtendedActorSystem) extends Extension {
  def getAddress: Address =
    system.provider.getDefaultAddress
}
object RemoteAddressExtension extends ExtensionKey[RemoteAddressExtensionImpl]

/**
  * This trait implements ``Core`` by starting the required ``ActorSystem`` and registering the
  * termination handler to stop the system when the JVM exits.
  */
trait BootedCore
    extends Core
    with CoreActors
    with Api
    with StaticResources
    with WokenSSLConfiguration {

  /**
    * Construct the ActorSystem we will use in our application
    */
  override lazy val system: ActorSystem              = ActorSystem(app.systemName)
  override lazy val actorRefFactory: ActorRefFactory = system

  override lazy val config: Config = ConfigFactory.load()
  private lazy val resultsDbConfig = DatabaseConfiguration
    .factory(config)("woken")
    .getOrElse(throw new IllegalStateException("Invalid configuration"))

  private lazy val featuresDbConnection = DatabaseConfiguration
    .factory(config)(WokenConfig.defaultSettings.defaultDb)
    .getOrElse(throw new IllegalStateException("Invalid configuration"))

  override lazy val featuresDAL = FeaturesDAL(featuresDbConnection)

  override lazy val resultsDAL: NodeDAL = ResultDatabaseConfig(resultsDbConfig).dal
  val resultDbConfig: DatabaseConfiguration = ???
  for {
    xa <- DatabaseConfiguration.dbTransactor[IO](resultDbConfig)
    wokenDb = new WokenRepositoryDAO[IO](xa)
  } yield {
    JobResultService(wokenDb.jobResults)
  }

  private lazy val metaDbConnection = DatabaseConfiguration
    .factory(config)(WokenConfig.defaultSettings.defaultMetaDb)
    .getOrElse(throw new IllegalStateException("Invalid configuration"))

  override lazy val metaDbConfig = MetaDatabaseConfig(metaDbConnection)

  //Cluster(system).join(RemoteAddressExtension(system).getAddress())
  lazy val cluster = Cluster(system)

  /**
    * Create and start our service actor
    */
  val rootService: ActorRef =
    system.actorOf(Props(new RoutedHttpService(routes ~ staticResources)), app.jobServiceName)

  /**
    * Create and start actor that acts as akka entry-point
    */
  val mainRouter: ActorRef =
    system.actorOf(MasterRouter.props(this, featuresDAL, resultsDAL, metaDbConfig),
                   name = "entrypoint")

  /**
    * Create and start actor responsible to register validation node
    */
  val validationRegisterActor: ActorRef = system.actorOf(Props[ValidationPoolManager])

  implicit val timeout: Timeout = Timeout(5.seconds)

  // start a new HTTP server on port 8080 with our service actor as the handler
  IO(Http)(system) ! Http.Bind(rootService, interface = app.interface, port = app.port)

  /**
    * Ensure that the constructed ActorSystem is shut down when the JVM shuts down
    */
  sys.addShutdownHook(system.shutdown())

}
