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
import cats.data.{ NonEmptyList, Validated }
import cats.effect.{ Effect, Resource }
import cats.implicits._
import ch.chuv.lren.woken.core.model.{ FeaturesTableDescription, TableColumn }
import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.core.model
import ch.chuv.lren.woken.core.validation.FeaturesSplitterDefinition
import ch.chuv.lren.woken.messages.datasets.DatasetId
import ch.chuv.lren.woken.cromwell.core.ConfigUtil._
import ch.chuv.lren.woken.dao.FeaturesTableRepository.Headers
import ch.chuv.lren.woken.messages.query.filters.FilterRule
import ch.chuv.lren.woken.messages.variables.SqlType
import ch.chuv.lren.woken.messages.variables.SqlType.SqlType
import doobie.enum.JdbcType
import utils._

import scala.language.higherKinds

class FeaturesRepositoryDAO[F[_]: Effect] private (
    val xa: Transactor[F],
    override val database: String,
    override val tables: Set[FeaturesTableDescription]
) extends FeaturesRepository[F] {

  override def featuresTable(dbSchema: Option[String],
                             table: String): F[Option[FeaturesTableRepository[F]]] = {

    val matchTable = { t: FeaturesTableDescription =>
      (t.schema == dbSchema || dbSchema.isEmpty && t.schema
        .contains("public")) && (t.name == table)
    }

    tables
      .find(matchTable)
      .map(t => FeaturesTableRepositoryDAO[F](xa, t))
      .sequence
  }
}

object FeaturesRepositoryDAO {

  def apply[F[_]: Effect](
      xa: Transactor[F],
      database: String,
      tables: Set[FeaturesTableDescription]
  ): F[Validation[FeaturesRepositoryDAO[F]]] = {

    case class Check(schema: String, table: String, column: String)

    def columnCheck(table: FeaturesTableDescription, column: TableColumn): Check =
      Check(table.schema.getOrElse("public"), table.name, column.name)

    // TODO: add "and is_identity='YES'" to the check. Problem: our tables don't have their primary key properly defined
    def checkPrimaryKey(check: Check): Fragment = sql"""
      SELECT 1 FROM information_schema.columns
        WHERE table_schema=${check.schema} and table_name=${check.table} and column_name=${check.column}"""

    def checkDatasetColumn(check: Check): Fragment =
      sql"""
      SELECT 1 FROM information_schema.columns
        WHERE table_schema=${check.schema} and table_name=${check.table} and column_name=${check.column}"""

    val empty: List[F[Option[String]]] = Nil
    val checks: List[F[Option[String]]] = tables
      .filter(_.validateSchema)
      .map { table =>
        val checkPk: List[F[Option[String]]] = table.primaryKey.map { pk =>
          checkPrimaryKey(columnCheck(table, pk)).query[Int].to[List].transact(xa).map { test =>
            if (test.nonEmpty) None
            else
              Some(s"Primary key ${pk.name} not found in table ${table.quotedName}")
          }
        }

        val checkDataset: List[F[Option[String]]] =
          table.datasetColumn.fold(empty) { datasetColumn =>
            val c =
              checkDatasetColumn(columnCheck(table, datasetColumn))
                .query[Int]
                .to[List]
                .transact(xa)
                .map { test =>
                  if (test.nonEmpty) None
                  else
                    Some(
                      s"Dataset column ${datasetColumn.name} not found in table ${table.quotedName}"
                    )
                }
            List(c)
          }

        checkPk ++ checkDataset
      }
      .fold(empty)(_ ++ _)

    val errors: F[List[String]] = checks.sequence
      .map(_.flatten)

    errors.map {
      case Nil                 => lift(new FeaturesRepositoryDAO(xa, database, tables))
      case error :: moreErrors => Validated.Invalid(NonEmptyList(error, moreErrors))
    }

  }

}

abstract class BaseFeaturesTableRepositoryDAO[F[_]: Effect] extends FeaturesTableRepository[F] {
  def xa: Transactor[F]

  override def count: F[Int] = {
    val q: Fragment = fr"SELECT count(*) FROM " ++ frName(table)
    q.query[Int]
      .unique
      .transact(xa)
  }

  override def count(dataset: DatasetId): F[Int] =
    table.datasetColumn.fold {
      if (dataset.code == table.quotedName || dataset.code == table.name) count
      else 0.pure[F]
    } { datasetColumn =>
      val q: Fragment = sql"SELECT count(*) FROM " ++ frName(table) ++ fr"WHERE " ++ frName(
        datasetColumn
      ) ++ fr" = ${dataset.code}"
      q.query[Int]
        .unique
        .transact(xa)
    }

  /**
    * Number of rows matching the filters.
    *
    * @param filters The filters used to filter rows
    * @return the number of rows in the dataset matching the filters, or the total number of rows if there are no filters
    */
  override def count(filters: Option[FilterRule]): F[Int] = {
    val q: Fragment = fr"SELECT count(*) FROM " ++ frName(table) ++ frWhereFilter(filters)
    q.query[Int]
      .unique
      .transact(xa)
  }

  import FeaturesTableRepositoryDAO.{ prepareHeaders, toJsValue }

  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  override def features(query: FeaturesQuery): F[(List[TableColumn], Stream[JsObject])] =
    connProg(query.sql).transact(xa).map {
      case (h, d) =>
        implicit val cols: Headers = h
        (h, d.map { row =>
          val fields = row.mapWithIndex {
            case (o, i) =>
              h(i).name -> toJsValue(o)
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
      headers <- prepareHeaders
      cols = (1 to headers.size).toList
      data <- HPS.executeQuery(readAll(cols))
    } yield (headers, data)

  /** Read the specified columns from the resultset. */
  private def readAll(cols: List[Int]): ResultSetIO[Data] =
    readOne(cols).whileM[Stream](HRS.next)

  /** Take a list of column offsets and read a parallel list of values. */
  private def readOne(cols: List[Int]): ResultSetIO[List[Object]] =
    cols.traverse(FRS.getObject) // always works

}

class FeaturesTableRepositoryDAO[F[_]: Effect] private (
    override val xa: Transactor[F],
    override val table: FeaturesTableDescription,
    override val columns: FeaturesTableRepository.Headers
) extends BaseFeaturesTableRepositoryDAO[F] {

override def createExtendedFeaturesTable(
                                          table: FeaturesTableDescription,
                                          tableColumns: List[TableColumn],
                                          filters: Option[FilterRule],
                                          newFeatures: List[TableColumn],
                                          splitters: List[FeaturesSplitterDefinition]
                                        ): Validation[Resource[F, FeaturesTableRepository[F]]] = {
  DynamicFeaturesTableRepositoryDAO(xa, table, tableColumns, filters, newFeatures, splitters)
}

}

object FeaturesTableRepositoryDAO {

  def apply[F[_]: Effect](xa: Transactor[F],
                         table: FeaturesTableDescription): F[FeaturesTableRepository[F]] =
    HC.prepareStatement(s"SELECT * FROM ${table.quotedName}")(prepareHeaders)
      .transact(xa)
      .map { headers =>
        new FeaturesTableRepositoryDAO(xa, table, headers)
      }

  private[dao] def prepareHeaders: PreparedStatementIO[Headers] =
    HPS.getColumnJdbcMeta.map(_.map { doobieMeta =>
      model.TableColumn(doobieMeta.name, toSql(doobieMeta.jdbcType))
    })

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private[dao] def toSql(jdbcType: JdbcType): SqlType = jdbcType match {
    case JdbcType.Char | JdbcType.NChar                                            => SqlType.char
    case JdbcType.VarChar | JdbcType.NVarChar | JdbcType.Clob                      => SqlType.varchar
    case JdbcType.BigInt | JdbcType.Integer | JdbcType.SmallInt | JdbcType.TinyInt => SqlType.int
    case JdbcType.Decimal | JdbcType.Double | JdbcType.Float | JdbcType.Real | JdbcType.Numeric =>
      SqlType.numeric
    case _ => throw new IllegalArgumentException(s"Unsupported type $jdbcType")
  }

  @SuppressWarnings(Array("org.wartremover.warts.Null", "org.wartremover.warts.Throw"))
  private[dao] def toJsValue(o: Object): JsValue = {
    import DefaultJsonProtocol.{ lift => _, _ }

    o match {
      case null                 => JsNull
      case s: String            => s.toJson
      case i: java.lang.Byte    => JsNumber(i.toInt)
      case i: java.lang.Integer => JsNumber(i)
      case l: java.lang.Long    => JsNumber(l)
      case d: java.lang.Double  => JsNumber(d)
      case f: java.lang.Float   => JsNumber(f.toDouble)
      case b: BigInt            => b.toJson
      case b: BigDecimal        => b.toJson
      case b: java.lang.Boolean => JsBoolean(b)
      case _                    => throw new IllegalStateException(s"Unsupported data type ${o.getClass}")
    }
  }
}

class DynamicFeaturesTableRepositoryDAO[F[_]: Effect] private (
    override val xa: Transactor[F],
    val view: FeaturesTableDescription,
    val viewColumns: List[TableColumn],
    val dynTable: FeaturesTableDescription,
    val newFeatures: List[TableColumn],
    val rndColumn: TableColumn
) extends BaseFeaturesTableRepositoryDAO[F] {

  override val table: FeaturesTableDescription = view
  override val columns: List[TableColumn]      = viewColumns

  def close(): F[Unit] = {
    val rmView     = fr"DELETE VIEW " ++ frName(view)
    val rmDynTable = fr"DELETE TABLE " ++ frName(dynTable)

    for {
      _ <- rmView.update.run.transact(xa)
      _ <- rmDynTable.update.run.transact(xa)
    } yield ()
  }

  override def createExtendedFeaturesTable(
                                            table: FeaturesTableDescription,
                                            tableColumns: List[TableColumn],
                                            filters: Option[FilterRule],
                                            newFeatures: List[TableColumn],
                                            splitters: List[FeaturesSplitterDefinition]
                                          ): Validation[Resource[F, FeaturesTableRepository[F]]] = "Impossible to extend an extended table".invalidNel

}

object DynamicFeaturesTableRepositoryDAO {

  def apply[F[_]: Effect](
      xa: Transactor[F],
      table: FeaturesTableDescription,
      tableColumns: List[TableColumn],
      filters: Option[FilterRule],
      newFeatures: List[TableColumn],
      splitters: List[FeaturesSplitterDefinition]
  ): Validation[Resource[F, FeaturesTableRepository[F]]] = {

    val extractPk: Validation[TableColumn] = table.primaryKey match {
      case pk :: Nil => lift(pk)
      case _ =>
        s"Dynamic features table expects a primary key of one column for table ${table.name}"
          .invalidNel[TableColumn]
    }

    val rndColumn  = TableColumn("_rnd", SqlType.int)
    val newColumns = newFeatures ++ splitters.map(_.splitColumn)

    val validatedDao = extractPk.map { pk =>
      // Work in context F
      val dynTableF = createDynamicTable(xa, table, pk, filters, rndColumn, newColumns)
      dynTableF.flatMap { dynTable =>
        val dynTableUpdates = splitters.map(_.fillSplitColumnSql(dynTable, rndColumn))
        for {
          _ <- dynTableUpdates.map(_.run.transact(xa)).sequence[F, Int]
          (dynView, dynColumns) <- createDynamicView(xa,
                                                     table,
                                                     pk,
                                                     tableColumns,
                                                     filters,
                                                     dynTable,
                                                     rndColumn,
                                                     newColumns)
        } yield
          new DynamicFeaturesTableRepositoryDAO(xa,
                                                dynView,
                                                dynColumns,
                                                dynTable,
                                                newColumns,
                                                rndColumn)

      }
    }

    validatedDao.map { dao =>
      Resource.make(dao)(_.close()).flatMap{ f: DynamicFeaturesTableRepositoryDAO[F] => {
        val repo: FeaturesTableRepository[F] = f
        Resource.make(Effect[F].delay(repo))(_ => Effect[F].delay(()))
      }}
    }
  }

  private def createDynamicTable[F[_]: Effect](
      xa: Transactor[F],
      table: FeaturesTableDescription,
      pk: TableColumn,
      filters: Option[FilterRule],
      rndColumn: TableColumn,
      newFeatures: List[TableColumn]
  ): F[FeaturesTableDescription] = {

    implicit val han: LogHandler = LogHandler.jdkLogHandler

    val genTableNum = sql"""
      SELECT nextval('gen_features_table_seq');
    """.query[Int].unique

    def createAdditionalFeaturesTable(dynTable: FeaturesTableDescription,
                                      pk: TableColumn): ConnectionIO[Int] = {
      val stmt = fr"CREATE TABLE " ++ frName(dynTable) ++ fr"(" ++ frName(pk) ++ frType(pk) ++ fr" PRIMARY KEY," ++
        frName(rndColumn) ++ fr" SERIAL," ++
        frNameType(newFeatures :+ rndColumn) ++ fr""")
       )
       WITH (
         OIDS=FALSE
       );
      """
      stmt.update.run
    }

    def fillAdditionalFeaturesTable(dynTable: FeaturesTableDescription,
                                    pk: TableColumn): ConnectionIO[Int] = {

      // Sample SQL statements used to build this:
      // create table cde_features_a_1 (subjectcode text primary key, rnd serial, win_1 int);
      // insert into cde_features_a_1 (subjectcode) (select subjectcode from cde_features_a where subjectage > 82 order by random());
      // with win as (select subjectcode, ntile(10) over (order by rnd) as win_1 from cde_features_a_1) update cde_features_a_1 set win_1=win.win_1 from win where cde_features_a_1.subjectcode=win.subjectcode;

      val insertRndStmt = fr"""SELECT setseed(" ++ frConst(table.seed) ++ fr");
        INSERT INTO """ ++ frName(dynTable) ++ fr"(" ++ frName(rndColumn) ++ fr") (SELECT " ++ frName(
        pk
      ) ++ fr" FROM " ++
        frName(table) ++ frWhereFilter(filters) ++ fr" ORDER BY random());"

      insertRndStmt.update.run
    }

    for {
      tableNum <- genTableNum.transact(xa)
      dynTable = table.copy(name = s"${table.name}__$tableNum")
      _ <- createAdditionalFeaturesTable(dynTable, pk).transact(xa)
      _ <- fillAdditionalFeaturesTable(dynTable, pk).transact(xa)
    } yield dynTable

  }

  private def createDynamicView[F[_]: Effect](
      xa: Transactor[F],
      table: FeaturesTableDescription,
      pk: TableColumn,
      tableColumns: List[TableColumn],
      filters: Option[FilterRule],
      dynTable: FeaturesTableDescription,
      rndColumn: TableColumn,
      newFeatures: List[TableColumn]
  ): F[(FeaturesTableDescription, Headers)] = {

    def createFeaturesView(table: FeaturesTableDescription,
                           pk: TableColumn,
                           tableColumns: Headers,
                           dynTable: FeaturesTableDescription,
                           dynTableColumns: Headers,
                           dynView: FeaturesTableDescription,
                           dynViewColumns: Headers): ConnectionIO[Int] = {

      val stmt = fr"CREATE OR REPLACE VIEW " ++ frName(dynView) ++
        fr"(" ++ frNames(dynViewColumns) ++ fr") AS SELECT" ++
        frQualifiedNames(table, tableColumns) ++ fr"," ++
        frQualifiedNames(dynTable, dynTableColumns) ++ fr" FROM " ++
        frName(table) ++ fr" LEFT OUTER JOIN " ++ frName(dynTable) ++ fr" ON " ++
        frEqual(table, List(pk), dynTable, List(pk))

      stmt.update.run
    }

    val dynTableColumns    = newFeatures ++ List(rndColumn: TableColumn)
    val dynViewDescription = dynTable.copy(name = s"${dynTable.name}v")
    val dynViewColumns     = tableColumns ++ newFeatures ++ List(rndColumn: TableColumn)

    for {
      _ <- createFeaturesView(table,
                              pk,
                              tableColumns,
                              dynTable,
                              dynTableColumns,
                              dynViewDescription,
                              dynViewColumns).transact(xa)
    } yield (dynViewDescription, dynViewColumns)

  }

}
