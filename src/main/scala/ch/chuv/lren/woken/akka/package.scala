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

package ch.chuv.lren.woken

import cats.effect.{ ConcurrentEffect, ContextShift, Resource, Timer }
import ch.chuv.lren.woken.config.WokenConfiguration
import ch.chuv.lren.woken.service.DatabaseServices

import scala.language.higherKinds

/**
  * Akka is used for inter-process communication, internal actors and support for streaming
  */
package object akka {

  import acyclic.pkg

  /**
    * Resource that creates and yields an Akka server, guaranteeing cleanup.
    */
  def resource[F[_]: ConcurrentEffect: ContextShift: Timer](
      databaseServices: DatabaseServices[F],
      config: WokenConfiguration
  ): Resource[F, AkkaServer[F]] = AkkaServer.resource(databaseServices, config)

}
