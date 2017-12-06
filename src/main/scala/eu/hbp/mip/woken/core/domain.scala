/*
 * Copyright 2017 Human Brain Project MIP by LREN CHUV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hbp.mip.woken.core

import akka.actor.ReceiveTimeout
import spray.http.StatusCodes
import spray.httpx.marshalling.ToResponseMarshaller
import spray.json.{ DefaultJsonProtocol, JsString, JsValue, RootJsonFormat }

import eu.hbp.mip.woken.core.model.JobResult

// TODO: move to json.rest?

// Messages

trait RestMessage {
  def marshaller: ToResponseMarshaller[this.type]
}

// Exceptions

object DefaultMarshallers extends DefaultJsonProtocol {

  import spray.httpx.SprayJsonSupport._

  implicit object ReceiveTimeoutFormat extends RootJsonFormat[ReceiveTimeout] {
    override def write(r: ReceiveTimeout) = JsString("ReceiveTimeoutFormat")
    override def read(json: JsValue): ReceiveTimeout = json match {
      case JsString("ReceiveTimeoutFormat") => ReceiveTimeout
      case _                                => throw new IllegalArgumentException("Expected 'ReceiveTimeoutFormat'")
    }
  }
  implicit val ReceiveTimeoutMarshaller: ToResponseMarshaller[ReceiveTimeout] =
    ToResponseMarshaller.fromMarshaller(StatusCodes.BadRequest)

}
