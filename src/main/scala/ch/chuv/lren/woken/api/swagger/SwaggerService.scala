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

package ch.chuv.lren.woken.api.swagger

import com.github.swagger.akka.SwaggerHttpService
import com.github.swagger.akka.model.Info

@SuppressWarnings(Array("org.wartremover.warts.Any"))
object SwaggerService extends SwaggerHttpService {

  override val basePath    = "/"
  override val apiDocsPath = "api-docs"
  override val info        = Info(version = "0.1")

  override val apiClasses: Set[Class[_]] =
    Set(classOf[MiningServiceApi], classOf[MetadataServiceApi])
  override val unwantedDefinitions = Seq("Function1", "Function1RequestContextFutureRouteResult")

}
