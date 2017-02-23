package api

import akka.actor.ActorSystem
import config.{FederationDatabaseConfig, LdsmDatabaseConfig, ResultDatabaseConfig}
import core.{Core, CoreActors}
import spray.routing.HttpService

/**
 * The REST API layer. It exposes the REST services, but does not provide any
 * web server interface.<br/>
 * Notice that it requires to be mixed in with ``core.CoreActors``, which provides access
 * to the top-level actors that make up the system.
 */
trait Api extends HttpService with CoreActors with Core {

  protected implicit val system: ActorSystem

  val job_service = new JobService(chronosHttp, ResultDatabaseConfig.dal, FederationDatabaseConfig.config.map(_.dal), LdsmDatabaseConfig.dal)
  val mining_service = new MiningService(chronosHttp, ResultDatabaseConfig.dal, FederationDatabaseConfig.config.map(_.dal), LdsmDatabaseConfig.dal)

  val routes =
    new SwaggerService().routes ~
    job_service.routes ~
    mining_service.routes

}