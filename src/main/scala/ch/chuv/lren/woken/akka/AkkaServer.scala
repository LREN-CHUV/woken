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

package ch.chuv.lren.woken.akka

import akka.actor.{ ActorRef, ActorSystem }
import akka.cluster.Cluster
import akka.cluster.client.ClusterClientReceptionist
import akka.cluster.pubsub.{ DistributedPubSub, DistributedPubSubMediator }
import akka.pattern.{ Backoff, BackoffSupervisor }
import cats.effect._
import ch.chuv.lren.woken.backends.woken.WokenClientService
import ch.chuv.lren.woken.config.{ DatasetsConfiguration, WokenConfiguration }
import ch.chuv.lren.woken.service._
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.language.higherKinds
import scala.util.{ Failure, Success }

/**
  * This server manages the Akka system by starting the required ``ActorSystem``, registering the
  * termination handler to stop the system when the JVM exits and starting the main actors.
  *
  * The main actors include:
  *
  * - the distributed pub sub mediator,
  * - the main router actor, which is exposed to the cluster client receptionist to provide remote access.
  * - Chronos http actor with throttling to prevent overflowing Chronos with burst of requests.
  * - a monitor for dead letter messages.
  *
  * @author Ludovic Claude <ludovic.claude@chuv.ch>
  */
@SuppressWarnings(Array("org.wartremover.warts.Throw", "org.wartremover.warts.NonUnitStatements"))
class AkkaServer[F[_]: ConcurrentEffect: ContextShift: Timer](
    val databaseServices: DatabaseServices[F],
    override val config: WokenConfiguration,
    override implicit val system: ActorSystem,
    override implicit val cluster: Cluster
) extends CoreSystem
    with CoreActors {

  override protected def logger: Logger = AkkaServer.logger

  private def mainRouterSupervisorProps = {

    val wokenService: WokenClientService = WokenClientService(config.jobs.node)
    val dispatcherService: DispatcherService =
      DispatcherService(DatasetsConfiguration.datasets(config.config), wokenService)
    val backendServices = BackendServices(dispatcherService, chronosHttp)

    BackoffSupervisor.props(
      Backoff.onFailure(
        MasterRouter.props(
          config,
          databaseServices,
          backendServices
        ),
        childName = "mainRouter",
        minBackoff = 100.milliseconds,
        maxBackoff = 1.seconds,
        randomFactor = 0.2
      )
    )
  }

  /**
    * Create and start actor that acts as akka entry-point
    */
  val mainRouter: ActorRef =
    system.actorOf(mainRouterSupervisorProps, name = "entrypoint")

  def startActors(): Unit = {

    val mediator = DistributedPubSub(system).mediator

    mediator ! DistributedPubSubMediator.Put(mainRouter)

    ClusterClientReceptionist(system).registerService(mainRouter)

    monitorDeadLetters()

  }

  def selfChecks(): Unit = {
    logger.info("Self checks...")

    if (cluster.state.leader.isEmpty) {
      logger.info("[FAIL] Akka server is not running")
      Effect[F].toIO(unbind()).unsafeRunSync()
    } else
      logger.info("[OK] Akka server joined the cluster.")
  }

  def unbind(): F[Unit] =
    Sync[F].defer {
      cluster.leave(cluster.selfAddress)
      val ending = system.terminate()
      Async[F].async { cb =>
        ending.onComplete {
          case Success(_)     => cb(Right(()))
          case Failure(error) => cb(Left(error))
        }
      }
    }
}

object AkkaServer {
  private val logger: Logger = Logger(LoggerFactory.getLogger("woken.AkkaServer"))

  /** Resource that creates and yields an Akka server, guaranteeing cleanup. */
  def resource[F[_]: ConcurrentEffect: ContextShift: Timer](
      databaseServices: DatabaseServices[F],
      config: WokenConfiguration
  ): Resource[F, AkkaServer[F]] =
    Resource.make(Sync[F].delay {

      logger.debug(s"Akka configuration : ${config.config.getConfig("akka")}")

      logger.info(s"Start actor system ${config.app.clusterSystemName}...")

      val system: ActorSystem = ActorSystem(config.app.clusterSystemName, config.config)

      logger.info(s"Start cluster ${config.app.clusterSystemName}...")

      val cluster: Cluster = Cluster(system)

      logger.info(s"Cluster has roles ${cluster.selfRoles.mkString(",")}")

      val server = new AkkaServer[F](databaseServices, config, system, cluster)

      server.startActors()
      server.selfChecks()

      server
    })(_.unbind())
}
