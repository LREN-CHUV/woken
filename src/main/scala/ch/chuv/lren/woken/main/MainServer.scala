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

package ch.chuv.lren.woken.main

import cats.effect._
// Required, don't trust IntelliJ
import cats.implicits._
import ch.chuv.lren.woken.akka
import ch.chuv.lren.woken.config.WokenConfiguration
import ch.chuv.lren.woken.monitoring
import ch.chuv.lren.woken.service
import ch.chuv.lren.woken.web

import scala.language.higherKinds

object MainServer {

  /** A single-element stream that starts the server up and shuts it down on exit. */
  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  def resource[F[_]: ConcurrentEffect: ContextShift: Timer](
      cfg: WokenConfiguration
  )(implicit ev: ContextShift[IO]): Resource[F, Unit] =
    // TODO: backendServer should ensure connection to Chronos, worker Woken processes in distributed mode...
    // TODO: collect also from each resource a health check
    for {
      databaseServices <- service.databaseResource[F](cfg)
      akkaServer       <- akka.resource[F](databaseServices, cfg)
      _                <- web.resource[F](akkaServer, cfg)
      _                <- monitoring.resource[F](akkaServer, cfg)
    } yield ()

}
