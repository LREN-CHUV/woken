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

object SwaggerService extends SwaggerHttpService {

  //  override def apiTypes   = Seq(typeOf[MiningServiceApi])
  //  override def apiVersion = "0.2"
  //  override def baseUrl    = "/" // let swagger-ui determine the host and port
  //  override def docsPath   = "api-docs"
  //  override def apiInfo    = Some(new ApiInfo("Api users", "", "", "", "", ""))
  override def apiClasses: Set[Class[_]] = Set(classOf[MiningServiceApi])
}
