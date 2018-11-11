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

package ch.chuv.lren.woken.dao

import java.time.{ OffsetDateTime, ZoneOffset }

import doobie._
import com.typesafe.scalalogging.LazyLogging
import org.postgresql.util.PGobject
import spray.json._

import scala.language.higherKinds
import scala.reflect.runtime.universe.TypeTag
import scala.util.Try

/**
  * Data Access Layer
  */
trait Repository extends LazyLogging {

  // TODO: add health checks
  // def healthCheck: Validation[]

  protected implicit val JsObjectMeta: Meta[JsObject] =
    Meta.Advanced.other[PGobject]("json").timap[JsObject](
      // failure raises an exception
      a => a.getValue.parseJson.asJsObject)(
      a => {
        val o = new PGobject
        o.setType("json")
        o.setValue(a.compactPrint)
        o
      }
    )


  protected implicit val DateTimeMeta: Meta[OffsetDateTime] =
    Meta[java.sql.Timestamp].timap(ts => OffsetDateTime.of(ts.toLocalDateTime, ZoneOffset.UTC))(
                                  dt => java.sql.Timestamp.valueOf(dt.toLocalDateTime))

  protected implicit val ListStringMeta: Meta[List[String]] =
    Meta[String].timap(
      _.split(",").toList)(
      _.mkString(",")
    )

  protected def codecMeta[A: RootJsonFormat: TypeTag]: Meta[A] =
    Meta[JsObject].timap[A](
      json =>
        Try(json.convertTo[A])
          .recover { case e: Exception => logger.warn(s"Cannot convert $json", e); throw e }
          .getOrElse(throw new IllegalArgumentException(s"Invalid Json $json")))(
      _.toJson.asJsObject
    )

}
