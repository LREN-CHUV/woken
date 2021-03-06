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
import cats.effect.ExitCase.{ Canceled, Completed, Error }
import cats.effect._
import cats.implicits._
import ch.chuv.lren.woken.backends.faas.chronos.ChronosExecutor
import ch.chuv.lren.woken.backends.woken.WokenClientService
import ch.chuv.lren.woken.backends.worker.WokenWorker
import ch.chuv.lren.woken.config.WokenConfiguration
import ch.chuv.lren.woken.dispatch.DispatchActors
import ch.chuv.lren.woken.errors.BugsnagErrorReporter
import ch.chuv.lren.woken.service._
import com.typesafe.scalalogging.Logger

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

  private val pubSub = DistributedPubSub(system)

  val backendServices: BackendServices[F] = {
    val wokenService: WokenClientService = WokenClientService(config.jobs.node)
    val dispatcherService: DispatcherService =
      DispatcherService(databaseServices.datasetService, wokenService)
    val wokenWorker = WokenWorker[F](pubSub, cluster)
    val algorithmExecutor = ChronosExecutor(
      system,
      chronosHttp,
      config.app.dockerBridgeNetwork,
      databaseServices.jobResultService,
      config.jobs,
      config.databaseConfig
    )
    val errorReporter = BugsnagErrorReporter(config.config)

    BackendServices(dispatcherService, algorithmExecutor, wokenWorker, errorReporter)
  }

  val dispatchActors = DispatchActors(system, config, backendServices, databaseServices)

  /**
    * Create and start actor that acts as akka entry-point
    */
  val mainRouter: ActorRef =
    system.actorOf(
      MasterRouter.roundRobinPoolProps(config, databaseServices, backendServices, dispatchActors),
      name = "entrypoint"
    )

  def startActors(): Unit = {
    pubSub.mediator ! DistributedPubSubMediator.Put(mainRouter)

    ClusterClientReceptionist(system).registerService(mainRouter)

    monitorDeadLetters()
  }

  def selfChecks(): Boolean = {
    logger.info("Self checks...")

    if (cluster.state.leader.isEmpty) {
      val seedNodes = config.config.getList("akka.cluster.seed-nodes").unwrapped()
      logger.error(
        s"[FAIL] Akka server is not running, it should have connected to seed nodes $seedNodes"
      )
      false
    } else {
      logger.info("[OK] Akka server joined the cluster.")
      true
    }
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
  private val logger: Logger = Logger("woken.AkkaServer")

  /** Resource that creates and yields an Akka server, guaranteeing cleanup. */
  def resource[F[_]: ConcurrentEffect: ContextShift: Timer](
      databaseServices: DatabaseServices[F],
      config: WokenConfiguration
  ): Resource[F, AkkaServer[F]] =
    Resource.makeCase(Sync[F].defer[AkkaServer[F]] {

      logger.debug(s"Akka configuration : ${config.config.getConfig("akka")}")

      logger.info(s"Start actor system ${config.app.clusterSystemName}...")

      val system: ActorSystem = ActorSystem(config.app.clusterSystemName, config.config)

      logger.info(s"Start cluster ${config.app.clusterSystemName}...")

      val cluster: Cluster = Cluster(system)

      logger.info(s"Cluster has roles ${cluster.selfRoles.mkString(",")}")

      val server = new AkkaServer[F](databaseServices, config, system, cluster)

      server.startActors()

      if (server.selfChecks())
        Sync[F].delay(server)
      else
        Sync[F].raiseError(new Exception("Akka server failed to start: self-checks did not pass"))

    }) { (akkaServer, exit) =>
      exit match {
        case Completed => akkaServer.unbind()
        case Canceled =>
          Sync[F]
            .delay(logger.info("Akka server execution cancelled"))
            .flatMap(_ => akkaServer.unbind())
        case Error(e) =>
          Sync[F].delay(logger.error(s"Akka server exited with error ${e.getMessage}", e))
      }
    }
}
