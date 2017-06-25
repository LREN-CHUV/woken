package eu.hbp.mip.woken.web

import spray.routing.HttpService
import spray.http.StatusCodes

trait StaticResources extends HttpService {

  val staticResources =
    get {
      path("") {
        pathEndOrSingleSlash {
          getFromResource("swagger-ui/index.html")
        }
      } ~
        pathPrefix("webjars") {
          getFromResourceDirectory("META-INF/resources/webjars")
      } ~
      path("favicon.ico") {
        complete(StatusCodes.NotFound)
      } ~
      path(Rest) {
        path => getFromResource("root/%s" format path)
      }
    }

}