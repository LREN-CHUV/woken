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

import java.sql.ResultSetMetaData

import cats.Monad
import cats.implicits._
import ch.chuv.lren.woken.core.features.FeaturesQuery
import doobie._
import doobie.implicits._
import spray.json._
import DefaultJsonProtocol._
import ch.chuv.lren.woken.messages.datasets.DatasetId

import scala.language.higherKinds

class FeaturesRepositoryDAO[F[_]: Monad](val xa: Transactor[F]) extends FeaturesRepository[F] {

  override def featuresTable(table: String, seed: Double = 0.67): FeaturesTableRepository[F] =
    new FeaturesTableRepositoryDAO[F](xa, table, seed)

}

class FeaturesTableRepositoryDAO[F[_]: Monad](val xa: Transactor[F],
                                              val table: String,
                                              val seed: Double = 0.67)
    extends FeaturesTableRepository[F] {

  override def count: F[Int] = {
    val q: Fragment = fr"SELECT count(*) FROM " ++ Fragment.const(table)
    q.query[Int]
      .unique
      .transact(xa)
  }

  override def count(dataset: DatasetId): F[Int] = {

    val checkDatasetColumn = sql"""
      SELECT EXISTS (SELECT 1 FROM information_schema.columns
        WHERE table_name=$table and column_name='dataset')"""

    checkDatasetColumn.query[Boolean].unique.transact(xa).flatMap { hasDatasetColumn =>
      if (hasDatasetColumn) {
        val q: Fragment = sql"SELECT count(*) FROM " ++ Fragment
          .const(table) ++ fr"WHERE dataset = ${dataset.code}"
        q.query[Int]
          .unique
          .transact(xa)
      } else count
    }
  }

  override def features(query: FeaturesQuery): F[(List[ColumnMeta], Stream[JsObject])] =
    connProg(query.sql).transact(xa).map {
      case (h, d) =>
        implicit val cols: Headers = h
        (h, d.map { row =>
          val fields = row.mapWithIndex {
            case (o, i) =>
              h(i).label -> {
                o match {
                  case null                 => JsNull
                  case s: String            => s.toJson
                  case i: java.lang.Integer => JsNumber(i)
                  case l: java.lang.Long    => JsNumber(l)
                  case d: java.lang.Double  => JsNumber(d)
                  case f: java.lang.Float   => JsNumber(f.toDouble)
                  case b: BigInt            => b.toJson
                  case b: BigDecimal        => b.toJson
                  case b: java.lang.Boolean => JsBoolean(b)
                }
              }
          }
          JsObject(fields: _*)
        })
    }

  type Data = Stream[List[Object]]

  /** Construct a parameterized query and process it with a custom program. */
  private def connProg(sql: String): ConnectionIO[(Headers, Data)] =
    HC.prepareStatement(s"SELECT setseed($seed); $sql")(prepareAndExec)

  /** Configure and run a PreparedStatement. We don't know the column count or types. */
  private def prepareAndExec: PreparedStatementIO[(Headers, Data)] =
    for {
      md <- HPS.getMetaData // lots of useful info here
      cols = (1 to md.getColumnCount).toList
      meta = getColumnMeta(md, cols)
      data <- HPS.executeQuery(readAll(cols))
    } yield (meta, data)

  /** Read the specified columns from the resultset. */
  private def readAll(cols: List[Int]): ResultSetIO[Data] =
    readOne(cols).whileM[Stream](HRS.next)

  /** Take a list of column offsets and read a parallel list of values. */
  private def readOne(cols: List[Int]): ResultSetIO[List[Object]] =
    cols.traverse(FRS.getObject) // always works

  private def getColumnMeta(md: ResultSetMetaData, cols: List[Int]): Headers =
    for {
      col <- cols
      colName = md.getColumnLabel(col).toLowerCase
      colType = md.getColumnClassName(col)
    } yield ColumnMeta(col, colName, colType)

}
