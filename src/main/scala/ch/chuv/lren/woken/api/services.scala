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

package ch.chuv.lren.woken.api

import akka.http.scaladsl.model.{ StatusCode, StatusCodes }
import akka.http.scaladsl.server._
import StatusCodes._
import akka.http.scaladsl.model.ws.{ Message, UpgradeToWebSocket }
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import ch.chuv.lren.woken.authentication.BasicAuthenticator
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.ExecutionContext
import scala.util.Failure

/**
  * Provides a hook to catch exceptions and rejections from routes, allowing custom
  * responses to be provided, logs to be captured, and potentially remedial actions.
  *
  * Note that this is not marshalled, but it is possible to do so allowing for a fully
  * JSON API (e.g. see how Foursquare do it).
  */
trait FailureHandling {
  this: LazyLogging =>

  implicit def rejectionHandler: RejectionHandler = RejectionHandler.default

  implicit def exceptionHandler: ExceptionHandler = ExceptionHandler {

    case e: IllegalArgumentException =>
      ctx =>
        loggedFailureResponse(
          ctx,
          e,
          message = s"The server was asked a question that didn't make sense: ${e.getMessage}",
          error = StatusCodes.NotAcceptable
        )

    case e: NoSuchElementException =>
      ctx =>
        loggedFailureResponse(
          ctx,
          e,
          message = "The server is missing some information. Try again in a few moments.",
          error = NotFound
        )

    case t: Throwable =>
      ctx =>
        // note that toString here may expose information and cause a security leak, so don't do it.
        loggedFailureResponse(ctx, t)
  }

  private def loggedFailureResponse(
      ctx: RequestContext,
      thrown: Throwable,
      message: String = "The server is having problems.",
      error: StatusCode = StatusCodes.InternalServerError
  ) = {
    logger.error(ctx.request.toString, thrown)
    ctx.complete((error, message))
  }

}

trait SecuredRouteHelper extends BasicAuthenticator with Directives {
  this: LazyLogging =>

  implicit val executionContext: ExecutionContext
  implicit val timeout: Timeout

  def securePathWithWebSocket(pm: PathMatcher[Unit],
                              wsFlow: Flow[Message, Message, Any],
                              restRoute: Route): Route =
    path(pm) {
      authenticateBasicAsync(realm = "Woken Secure API", basicAuthenticator).apply { _ =>
        optionalHeaderValueByType[UpgradeToWebSocket](()) {
          case Some(upgrade) =>
            complete(upgrade.handleMessages(wsFlow.watchTermination() { (_, done) =>
              done.onComplete {
                case scala.util.Success(_) =>
                  logger.info(s"Web socket for $pm completed successfully.")
                case Failure(ex) =>
                  logger.error(s"Web socket for $pm completed with failure : $ex")
              }
            }))
          //}
          case None => restRoute
        }
      }
    }
}
