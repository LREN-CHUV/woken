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

import cats.effect.{ ExitCode, IO, IOApp }
import ch.chuv.lren.woken.config.WokenConfiguration
import ch.chuv.lren.woken.service.DatabaseServices
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

// TODO: follow https://github.com/gemini-hlsw/ocs3/blob/10ec44d34b2dbf23d660c30cf6079290a9527880/modules/main/src/main/scala/Main.scala

/**
  * Provides the web server (spray-can) for the REST api in ``Api``, using the actor system
  * defined in ``Core``.
  *
  * You may sometimes wish to construct separate ``ActorSystem`` for the web server machinery.
  * However, for this simple application, we shall use the same ``ActorSystem`` for the
  * entire application.
  *
  * Benefits of separate ``ActorSystem`` include the ability to use completely different
  * configuration, especially when it comes to the threading core.model.
  *
  * @author Ludovic Claude <ludovic.claude@chuv.ch>
  */
object Main extends IOApp {

  protected lazy val logger: Logger =
    Logger(LoggerFactory.getLogger("Woken"))

  def run(args: List[String]): IO[ExitCode] = {
    val config = WokenConfiguration()
    DatabaseServices.provide(config).use { databaseServices =>
      }
    beforeBoot()
    startActors()
    startServices()
    selfChecks()
    logger.info("Woken startup complete")
  }

}
