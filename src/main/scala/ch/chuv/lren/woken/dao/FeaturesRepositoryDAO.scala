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

import doobie._
import doobie.implicits._
import spray.json._
import cats.Monad
import cats.data.{NonEmptyList, Validated}
import cats.implicits._
import ch.chuv.lren.woken.core.model.{FeaturesTableDescription, TableColumn}
import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.core.model
import ch.chuv.lren.woken.messages.datasets.DatasetId
import ch.chuv.lren.woken.cromwell.core.ConfigUtil._
import ch.chuv.lren.woken.dao.FeaturesTableRepository.Headers
import ch.chuv.lren.woken.messages.variables.SqlType
import ch.chuv.lren.woken.messages.variables.SqlType.SqlType
import doobie.enum.JdbcType

import scala.language.higherKinds

class FeaturesRepositoryDAO[F[_]: Monad] private (
    val xa: Transactor[F],
    override val tables: Set[FeaturesTableDescription]
) extends FeaturesRepository[F] {

  override def featuresTable(table: String): F[Option[FeaturesTableRepository[F]]] =
    tables.find(_.name == table).map(t => FeaturesTableRepositoryDAO[F](xa, t)).sequence

}

object FeaturesRepositoryDAO {

  def apply[F[_]: Monad](
      xa: Transactor[F],
      tables: Set[FeaturesTableDescription]
  ): F[Validation[FeaturesRepositoryDAO[F]]] = {

    def checkPrimaryKey(table: FeaturesTableDescription, pk: TableColumn): Fragment = sql"""
      SELECT EXISTS (SELECT 1 FROM information_schema.columns
        WHERE table_schema='${table.schema.getOrElse("public")}' and table_name='${table.name}' and column_name='${pk.name}' and is_identity=true)"""

    def checkDatasetColumn(table: FeaturesTableDescription, datasetColumn: TableColumn): Fragment =
      sql"""
      SELECT EXISTS (SELECT 1 FROM information_schema.columns
        WHERE table_schema='${table.schema.getOrElse("public")}' and table_name='${table.name}' and column_name='${datasetColumn.name}')"""

    val checks: List[F[Option[String]]] = tables
      .map { table =>
        val checkPk: List[F[Option[String]]] = table.primaryKey.map { pk =>
          checkPrimaryKey(table, pk).query[Boolean].unique.transact(xa).map { test =>
            if (test) None
            else
              Some(s"Primary key ${pk.name} not found in table ${table.quotedName}")
          }
        }

        val checkDataset: List[F[Option[String]]] =
          table.datasetColumn.fold(Nil.asInstanceOf[List[F[Option[String]]]]) { datasetColumn =>
            val c =
              checkDatasetColumn(table, datasetColumn).query[Boolean].unique.transact(xa).map {
                test =>
                  if (test) None
                  else
                    Some(
                      s"Dataset column ${datasetColumn.name} not found in table ${table.quotedName}"
                    )
              }
            List(c)
          }

        checkPk ++ checkDataset
      }
      .reduce(_ ++ _)

    val errors: F[List[String]] = checks.sequence
      .map(_.flatten)

    errors.map {
      case Nil                 => lift(new FeaturesRepositoryDAO(xa, tables))
      case error :: moreErrors => Validated.Invalid(NonEmptyList(error, moreErrors))
    }

  }

}

abstract class BaseFeaturesTableRepositoryDAO[F[_]: Monad] extends FeaturesTableRepository[F] {
  def xa: Transactor[F]

  def table: FeaturesTableDescription

  override def count: F[Int] = {
    val q: Fragment = fr"SELECT count(*) FROM " ++ Fragment.const(table.name)
    q.query[Int]
      .unique
      .transact(xa)
  }

  override def count(dataset: DatasetId): F[Int] =
    table.datasetColumn.fold {
      if (dataset.code == table.quotedName || dataset.code == table.name) count
      else 0.pure[F]
    } { datasetColumn =>
      val q: Fragment = sql"SELECT count(*) FROM " ++ Fragment
        .const(table.name) ++ fr"WHERE " ++ Fragment.const(datasetColumn.name) ++ fr" = ${dataset.code}"
      q.query[Int]
        .unique
        .transact(xa)
    }

}

class FeaturesTableRepositoryDAO[F[_]: Monad] private (override val xa: Transactor[F],
                                               override val table: FeaturesTableDescription,
                                              override val headers: FeaturesTableRepository.Headers
                                             )
    extends BaseFeaturesTableRepositoryDAO[F] {

  import DefaultJsonProtocol.{ lift => _, _ }

  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  override def features(query: FeaturesQuery): F[(List[TableColumn], Stream[JsObject])] =
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
    HC.prepareStatement(s"SELECT setseed(${table.seed}); $sql")(prepareAndExec)

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

}

object FeaturesTableRepositoryDAO {

  def apply[F[_]: Monad](xa: Transactor[F], table: FeaturesTableDescription): F[FeaturesTableRepository[F]] = {
    HC.prepareStatement(s"SELECT 1")(prepareHeaders)
      .transact(xa)
      .map { headers => new FeaturesTableRepositoryDAO(xa, table, headers) }
  }

  private[dao] def prepareHeaders: PreparedStatementIO[Headers] =
    HPS.getColumnJdbcMeta.map(_.map{ doobieMeta =>
      model.TableColumn(doobieMeta.name, toSql(doobieMeta.jdbcType))
    })

  private[dao] def toSql(jdbcType: JdbcType): SqlType = jdbcType match {
    case JdbcType.Char | JdbcType.NChar => SqlType.char
    case JdbcType.VarChar | JdbcType.NVarChar | JdbcType.Clob => SqlType.varchar
    case JdbcType.BigInt | JdbcType.Integer | JdbcType.SmallInt | JdbcType.TinyInt => SqlType.int
    case JdbcType.Decimal | JdbcType.Double | JdbcType.Float | JdbcType.Real | JdbcType.Numeric => SqlType.numeric
    case _ => throw new IllegalArgumentException(s"Unsupported type $jdbcType")
  }

}

class DynamicFeaturesTableRepositoryDAO[F[_]: Monad] private (override val xa: Transactor[F],
                                                     override val table: FeaturesTableDescription,
                                                     val newFeatures: List[TableColumn])
    extends BaseFeaturesTableRepositoryDAO[F] {


//  def update(): F[Unit] = {}

}

object DynamicFeaturesTableRepositoryDAO {

  def apply[F[_]: Monad](xa: Transactor[F],
  table: FeaturesTableDescription,
   newFeatures: List[TableColumn]): Validation[F[DynamicFeaturesTableRepositoryDAO[F]]] = {

    val extractPk: Validation[TableColumn] = table.primaryKey match {
      case pk :: Nil => lift(pk)
      case _ => s"Dynamic features table expects a primary key of one column for table ${table.name}".invalidNel[TableColumn]
    }

    for {
      dynTable <- extractPk.andThen{ pk => lift(create(xa, table, pk, newFeatures)) }
      validatedDao = for {
        d <- dynTable
        dao = new DynamicFeaturesTableRepositoryDAO(xa, d, newFeatures)
      } yield dao
    } yield validatedDao

  }

  private def create[F[_]: Monad](xa: Transactor[F],  table: FeaturesTableDescription, pk: TableColumn, newFeatures: List[TableColumn]): F[FeaturesTableDescription] = {

    val genTableNum = sql"""
      SELECT nextval('gen_features_table_seq');
    """.query[Int].unique

    import ch.chuv.lren.woken.messages.variables.{ SqlType => SqlT }
    def toSql(sqlType: SqlType): String = sqlType match {
      case SqlT.int => "int"
      case SqlT.numeric => "number"
      case SqlT.char => "char(256)"
      case SqlT.varchar => "varchar(256)"
    }

    def createAdditionalFeaturesTable(dynTable: FeaturesTableDescription, pk: TableColumn): ConnectionIO[Int] = {
      val stmt = fr"CREATE TABLE " ++ Fragment.const(dynTable.quotedName) ++ fr"(" ++
        Fragment.const(pk.name) ++ Fragment.const(toSql(pk.sqlType)) ++fr"""NOT NULL,
      (
        _window_ int,
        _rnd_ int,
      """ ++ Fragment.const(newFeatures.map{ f => s"${f.name} ${toSql(f.sqlType)}" }.mkString(",")) ++
        fr"CONSTRAINT pk_" ++ Fragment.const(dynTable.name) ++ fr"PRIMARY KEY (" ++ Fragment.const(pk.name) ++ fr""")
      )
      WITH (
        OIDS=FALSE
      );
      """
      stmt.update.run
    }

    def fillAdditionalFeaturesTable(dynTable: FeaturesTableDescription, pk: TableColumn): ConnectionIO[Int] = {
???
    }

    def createFeaturesView(dynTable: FeaturesTableDescription): ConnectionIO[Int] =
      sql"""
        CREATE OR REPLACE VIEW {{ view.name }} ({{{ view.columns }}})
          AS SELECT {{{ table1.qualifiedColumns }}},{{{ table2.qualifiedColumnsNoId }}} FROM {{ table1.name }}
              LEFT OUTER JOIN {{ table2.name }} ON ({{{ table1.qualifiedId }}} = {{{ table2.qualifiedId }}});
      """.update.run

    for {
      tableNum <- genTableNum.transact(xa)
      dynTable = table.copy(name = s"${table.name}__$tableNum")
      _        <- createAdditionalFeaturesTable(dynTable, pk).transact(xa)
      _        <- fillAdditionalFeaturesTable(dynTable, pk).transact(xa)
    // TODO: view not ready yet
      //_        <- createFeaturesView(dynTable).transact(xa)

    } yield dynTable

  }

}
