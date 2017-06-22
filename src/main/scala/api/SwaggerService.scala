package eu.hbp.mip.woken.api

import akka.actor.ActorRefFactory
import com.gettyimages.spray.swagger.SwaggerHttpService
import com.wordnik.swagger.model.ApiInfo

import scala.reflect.runtime.universe._

class SwaggerService(implicit override val actorRefFactory: ActorRefFactory) extends SwaggerHttpService {

  override def apiTypes = Seq(typeOf[JobServiceDoc], typeOf[MiningServiceDoc])
  override def apiVersion = "0.2"
  override def baseUrl = "/" // let swagger-ui determine the host and port
  override def docsPath = "api-docs"
  override def apiInfo = Some(new ApiInfo("Api users", "", "", "", "", ""))

}
