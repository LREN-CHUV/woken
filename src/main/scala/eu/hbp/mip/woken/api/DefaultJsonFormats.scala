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

package eu.hbp.mip.woken.api

import spray.http.StatusCode
import spray.httpx.SprayJsonSupport
import spray.httpx.marshalling.{ Marshaller, MetaMarshallers, ToResponseMarshaller }
import spray.json._

import scala.reflect.ClassTag

/**
  * Contains useful JSON formats: ``j.u.Date``, ``j.u.UUID`` and others; it is useful
  * when creating traits that contain the ``JsonReader`` and ``JsonWriter`` instances
  * for types that contain ``Date``s, ``UUID``s and such like.
  */
trait DefaultJsonFormats extends DefaultJsonProtocol with SprayJsonSupport with MetaMarshallers {

  /**
    * Computes ``RootJsonFormat`` for type ``A`` if ``A`` is object
    */
  def jsonObjectFormat[A: ClassTag]: RootJsonFormat[A] = new RootJsonFormat[A] {
    val ct: ClassTag[A]        = implicitly[ClassTag[A]]
    def write(obj: A): JsValue = JsObject("value" -> JsString(ct.runtimeClass.getSimpleName))
    def read(json: JsValue): A = ct.runtimeClass.newInstance().asInstanceOf[A]
  }

  // Following code inspired by http://www.cakesolutions.net/teamblogs/2012/12/10/errors-failures-im-a-teapot

  /**
    * Type alias for function that converts ``A`` to some ``StatusCode``
    * @tparam A the type of the input values
    */
  type ErrorSelector[A] = A => StatusCode

  /**
    * Marshals instances of ``Either[A, B]`` into appropriate HTTP responses by marshalling the values
    * in the left or right projections; and by selecting the appropriate HTTP status code for the
    * values in the left projection.
    *
    * @param ma marshaller for the left projection
    * @param mb marshaller for the right projection
    * @param esa the selector converting the left projection to HTTP status code
    * @tparam A the left projection
    * @tparam B the right projection
    * @return marshaller
    */
  implicit def errorSelectingEitherMarshaller[A, B](
      implicit ma: Marshaller[A],
      mb: Marshaller[B],
      esa: ErrorSelector[A]
  ): ToResponseMarshaller[Either[A, B]] =
    ToResponseMarshaller[Either[A, B]] { (value, ctx) =>
      value match {
        case Left(a) =>
          ToResponseMarshaller.fromMarshaller(esa(a))(ma)(a, ctx)
        case Right(b) =>
          ToResponseMarshaller.fromMarshaller()(mb)(b, ctx)
      }
    }

}
