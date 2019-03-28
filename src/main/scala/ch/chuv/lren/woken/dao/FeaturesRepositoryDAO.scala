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

import cats.Id
import cats.Monad
import cats.MonadError
import doobie._
import doobie.implicits._
import spray.json._
import cats.data.{ NonEmptyList, Validated }
import cats.effect.{ Effect, Resource }
import cats.implicits._
import ch.chuv.lren.woken.config.DatabaseConfiguration
import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.core.logging
import ch.chuv.lren.woken.core.model.database.{ FeaturesTableDescription, TableColumn }
import ch.chuv.lren.woken.core.model.database.sqlUtils._
import ch.chuv.lren.woken.messages.datasets.{ DatasetId, TableId }
import ch.chuv.lren.woken.cromwell.core.ConfigUtil._
import ch.chuv.lren.woken.dao.FeaturesTableRepository.Headers
import ch.chuv.lren.woken.messages.query.filters.FilterRule
import ch.chuv.lren.woken.messages.variables.SqlType
import ch.chuv.lren.woken.messages.variables.SqlType.SqlType
import doobie.enum.JdbcType
import doobie.util.analysis.ColumnMeta
import doobie.util.transactor.Strategy
import sup.HealthCheck

import scala.language.higherKinds

class FeaturesRepositoryDAO[F[_]] private (
    val xa: Transactor[F],
    override val database: DatabaseConfiguration
)(implicit F: MonadError[F, Throwable]) extends FeaturesRepository[F] {

  override def featuresTable(table: TableId): F[Option[FeaturesTableRepository[F]]] =
    database.tables
      .get(table)
      .map(
        t =>
          FeaturesTableRepositoryDAO[F](xa, t)
            .map(r => r: FeaturesTableRepository[F])
      )
      .sequence

  override def healthCheck: HealthCheck[F, Id] = validate(xa)

}

object FeaturesRepositoryDAO {

  implicit val han: LogHandler = logging.doobieLogHandler

  def apply[F[_]](
      xa: Transactor[F],
      database: DatabaseConfiguration,
      wokenRepository: WokenRepository[F]
  )(implicit F: MonadError[F, Throwable]): F[Validation[FeaturesRepositoryDAO[F]]] = {

    case class Check(schema: String, table: String, column: String)

    def columnCheck(table: FeaturesTableDescription, column: TableColumn): Check =
      Check(table.table.dbSchema, table.table.name, column.name)

    // TODO: add "and is_identity='YES'" to the check. Problem: our tables don't have their primary key properly defined
    def checkPrimaryKey(check: Check): Fragment = sql"""
      SELECT 1 FROM information_schema.columns
        WHERE table_schema=${check.schema} and table_name=${check.table} and column_name=${check.column}"""

    def checkDatasetColumn(check: Check): Fragment =
      sql"""
      SELECT 1 FROM information_schema.columns
        WHERE table_schema=${check.schema} and table_name=${check.table} and column_name=${check.column}"""

    val empty: List[F[Option[String]]] = Nil
    val checks: List[F[Option[String]]] = database.tables
      .filter(_._2.validateSchema)
      .map { table =>
        val tableDescription = table._2
        val checkPk: List[F[Option[String]]] = tableDescription.primaryKey.map { pk =>
          checkPrimaryKey(columnCheck(tableDescription, pk)).query[Int].to[List].transact(xa).map {
            test =>
              if (test.nonEmpty) None
              else
                Some(s"Primary key ${pk.name} not found in table ${table._2.quotedName}")
          }
        }

        val checkDataset: List[F[Option[String]]] =
          tableDescription.datasetColumn.fold(empty) { datasetColumn =>
            val c =
              checkDatasetColumn(columnCheck(tableDescription, datasetColumn))
                .query[Int]
                .to[List]
                .transact(xa)
                .map { test =>
                  if (test.nonEmpty) None
                  else
                    Some(
                      s"Dataset column ${datasetColumn.name} not found in table ${tableDescription.quotedName}"
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
      case Nil                 => new FeaturesRepositoryDAO(xa, database).validNel[String]
      case error :: moreErrors => Validated.Invalid(NonEmptyList(error, moreErrors))
    }

  }

}

abstract class BaseFeaturesTableRepositoryDAO[F[_]: Monad] extends FeaturesTableRepository[F] {
  def xa: Transactor[F]

  implicit val han: LogHandler = logging.doobieLogHandler

  protected lazy val defaultDataset: DatasetId = DatasetId(table.table.name)

  override def count: F[Int] = {
    val q: Fragment = fr"SELECT count(*) FROM" ++ frName(table)
    q.query[Int]
      .unique
      .transact(xa)
  }

  override def count(datasetId: DatasetId): F[Int] =
    table.datasetColumn.fold {
      if (datasetId.code == table.quotedName || datasetId.code == defaultDataset.code) count
      else 0.pure[F]
    } { datasetColumn =>
      def countDataset(dataset: String): Fragment =
        fr"SELECT count(*) FROM" ++ frName(table) ++ fr"WHERE" ++ frName(
          datasetColumn
        ) ++ fr"= $dataset"
      countDataset(datasetId.code)
        .query[Int]
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
    val q: Fragment = fr"SELECT count(*) FROM" ++ frName(table) ++ frWhereFilter(filters)
    q.query[Int]
      .unique
      .transact(xa)
  }

  /**
    * Number of rows grouped by a reference column
    *
    * @return a map containing the number of rows for each value of the group by column
    */
  override def countGroupBy(groupByColumn: TableColumn,
                            filters: Option[FilterRule]): F[Map[String, Int]] = {
    val q: Fragment = fr"SELECT" ++ frName(groupByColumn) ++ fr", count(*) FROM" ++
      frName(table) ++ frWhereFilter(filters) ++ fr"GROUP BY" ++ frName(groupByColumn)
    q.query[(String, Int)]
      .to[List]
      .transact(xa)
      .map(_.toMap)
  }

  override def datasets(filters: Option[FilterRule]): F[Set[DatasetId]] =
    table.datasetColumn.fold(
      count(filters).map(n => if (n == 0) Set[DatasetId]() else Set(defaultDataset))
    ) { dsCol =>
      val q = fr"SELECT DISTINCT" ++ frName(dsCol) ++ fr"as code FROM" ++ frName(table) ++ frWhereFilter(
        filters
      )
      q.query[DatasetId]
        .to[Set]
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
              h(i).name -> toJsValue(o, h(i).name)
          }
          JsObject(fields: _*)
        })
    }

  type Data = Stream[List[Object]]

  /** Construct a parameterized query and process it with a custom program. */
  private def connProg(sql: String): ConnectionIO[(Headers, Data)] =
    HC.prepareStatement(sql)(prepareAndExec)

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

class FeaturesTableRepositoryDAO[F[_]] private[dao] (
    override val xa: Transactor[F],
    override val table: FeaturesTableDescription,
    override val columns: FeaturesTableRepository.Headers
)(implicit F: MonadError[F, Throwable]) extends BaseFeaturesTableRepositoryDAO[F] {

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  override def createExtendedFeaturesTable(
      filters: Option[FilterRule],
      newFeatures: List[TableColumn],
      otherColumns: List[TableColumn],
      prefills: List[PrefillExtendedFeaturesTable],
      extendedTableNumber: Int
  ): Validation[Resource[F, FeaturesTableRepository[F]]] =
    ExtendedFeaturesTableRepositoryDAO(this,
                                       filters,
                                       newFeatures,
                                       otherColumns,
                                       prefills,
                                       extendedTableNumber)
      .map(_.widen[FeaturesTableRepository[F]])

  override def healthCheck: HealthCheck[F, Id] = validate(xa)
}

object FeaturesTableRepositoryDAO {

  def apply[F[_]](xa: Transactor[F],
                          table: FeaturesTableDescription)(implicit F: MonadError[F, Throwable]): F[FeaturesTableRepositoryDAO[F]] = {
    implicit val han: LogHandler = logging.doobieLogHandler

    HC.prepareStatement(s"SELECT * FROM ${table.quotedName} LIMIT 1")(prepareHeaders)
      .transact(xa)
      .map { headers =>
        new FeaturesTableRepositoryDAO(xa, table, headers)
      }
  }

  private[dao] def prepareHeaders: PreparedStatementIO[Headers] =
    HPS.getColumnJdbcMeta.map(_.map { doobieMeta =>
      TableColumn(doobieMeta.name, toSql(doobieMeta))
    })

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private[dao] def toSql(column: ColumnMeta): SqlType = column.jdbcType match {
    case JdbcType.Char | JdbcType.NChar                                            => SqlType.char
    case JdbcType.VarChar | JdbcType.NVarChar | JdbcType.Clob                      => SqlType.varchar
    case JdbcType.BigInt | JdbcType.Integer | JdbcType.SmallInt | JdbcType.TinyInt => SqlType.int
    case JdbcType.Decimal | JdbcType.Double | JdbcType.Float | JdbcType.Real | JdbcType.Numeric =>
      SqlType.numeric
    case _ =>
      throw new IllegalArgumentException(
        s"Unsupported type ${column.jdbcType} on column ${column.name}"
      )
  }

  @SuppressWarnings(Array("org.wartremover.warts.Null", "org.wartremover.warts.Throw"))
  private[dao] def toJsValue(o: Object, column: String): JsValue = {
    import DefaultJsonProtocol.{ lift => _, _ }

    o match {
      case null                    => JsNull
      case s: String               => s.toJson
      case i: java.lang.Byte       => JsNumber(i.toInt)
      case i: java.lang.Integer    => JsNumber(i)
      case l: java.lang.Long       => JsNumber(l)
      case d: java.lang.Double     => JsNumber(d)
      case f: java.lang.Float      => JsNumber(f.toDouble)
      case b: java.math.BigInteger => JsNumber(b.longValue())
      case b: java.math.BigDecimal => JsNumber(b.doubleValue())
      case b: BigInt               => b.toJson
      case b: BigDecimal           => b.toJson
      case b: java.lang.Boolean    => JsBoolean(b)
      case _ =>
        throw new IllegalStateException(s"Unsupported data type ${o.getClass} on column $column")
    }
  }
}

class ExtendedFeaturesTableRepositoryDAO[F[_]] private (
    val sourceTable: FeaturesTableRepositoryDAO[F],
    val view: FeaturesTableDescription,
    val viewColumns: List[TableColumn],
    val extTable: FeaturesTableDescription,
    val newFeatures: List[TableColumn],
    val rndColumn: TableColumn
)(implicit F: MonadError[F, Throwable]) extends BaseFeaturesTableRepositoryDAO[F] {

  override val xa: Transactor[F]               = sourceTable.xa
  override val table: FeaturesTableDescription = view
  override val columns: List[TableColumn]      = viewColumns

  override protected lazy val defaultDataset: DatasetId = DatasetId(sourceTable.table.table.name)

  def close(): F[Unit] = {
    val rmView     = fr"DROP VIEW IF EXISTS" ++ frName(view)
    val rmDynTable = fr"DROP TABLE IF EXISTS" ++ frName(extTable)

    for {
      _ <- rmView.update.run.transact(xa)
      _ <- rmDynTable.update.run.transact(xa)
    } yield ()
  }

  override def createExtendedFeaturesTable(
      filters: Option[FilterRule],
      newFeatures: List[TableColumn],
      otherColumns: List[TableColumn],
      prefills: List[PrefillExtendedFeaturesTable],
      extendedTableNumber: Int
  ): Validation[Resource[F, FeaturesTableRepository[F]]] =
    "Impossible to extend an extended table".invalidNel

  override def healthCheck: HealthCheck[F, Id] = validate(xa)
}

object ExtendedFeaturesTableRepositoryDAO {

  implicit val han: LogHandler = logging.doobieLogHandler

  def apply[F[_]](
      sourceTable: FeaturesTableRepositoryDAO[F],
      filters: Option[FilterRule],
      newFeatures: List[TableColumn],
      otherColumns: List[TableColumn],
      prefills: List[PrefillExtendedFeaturesTable],
      extendedTableNumber: Int
  )(implicit F: MonadError[F, Throwable]): Validation[Resource[F, ExtendedFeaturesTableRepositoryDAO[F]]] = {

    val xa       = sourceTable.xa
    val setSeed  = fr"SELECT setseed(" ++ frConst(sourceTable.table.seed) ++ fr");"
    val seededXa = Transactor.strategy.set(xa, Strategy.default.copy(before = setSeed.query.unique))
    val extractPk: Validation[TableColumn] = sourceTable.table.primaryKey match {
      case pk :: Nil => pk.validNel[String]
      case _ =>
        val sourceTableName = sourceTable.table.table.name
        s"Extended features table expects a primary key of one column for table $sourceTableName"
          .invalidNel[TableColumn]
    }

    val rndColumn  = TableColumn("_rnd", SqlType.numeric)
    val newColumns = newFeatures ++ otherColumns

    val validatedDao: Validated[NonEmptyList[String], F[ExtendedFeaturesTableRepositoryDAO[F]]] =
      extractPk.map { pk =>
        val daoC = for {
          extTable <- createExtendedTable(sourceTable.table,
                                          pk,
                                          filters,
                                          rndColumn,
                                          newColumns,
                                          extendedTableNumber)
          _ <- prefills
            .traverse(_.prefillExtendedTableSql(sourceTable.table, extTable, rndColumn).run)
          extViewCols <- createExtendedView(sourceTable.table,
                                            pk,
                                            sourceTable.columns,
                                            filters,
                                            extTable,
                                            rndColumn,
                                            newColumns)
        } yield
          new ExtendedFeaturesTableRepositoryDAO(sourceTable,
                                                 extViewCols._1,
                                                 extViewCols._2,
                                                 extTable,
                                                 newColumns,
                                                 rndColumn)
        daoC.transact(seededXa)
      }

    validatedDao.map { dao =>
      Resource.make(dao)(_.close())
    }
  }

  private def createExtendedTable(
      table: FeaturesTableDescription,
      pk: TableColumn,
      filters: Option[FilterRule],
      rndColumn: TableColumn,
      newFeatures: List[TableColumn],
      tableNum: Int
  ): ConnectionIO[FeaturesTableDescription] = {

    def createAdditionalFeaturesTable(extTable: FeaturesTableDescription,
                                      pk: TableColumn): ConnectionIO[Int] = {
      val stmt = fr"CREATE TABLE" ++ frName(extTable) ++ fr"(" ++ frName(pk) ++ frType(pk) ++ fr"PRIMARY KEY," ++
        frNameType(newFeatures :+ rndColumn) ++ fr"""
       )
       WITH (
         OIDS=FALSE
       );
      """
      stmt.update.run
    }

    def fillAdditionalFeaturesTable(extTable: FeaturesTableDescription,
                                    pk: TableColumn): ConnectionIO[Int] = {

      // Sample SQL statements used to build this:
      // create table cde_features_a_1 (subjectcode text primary key, rnd serial, win_1 int);
      // insert into cde_features_a_1 (subjectcode) (select subjectcode from cde_features_a where subjectage > 82 order by random());
      // with win as (select subjectcode, ntile(10) over (order by rnd) as win_1 from cde_features_a_1) update cde_features_a_1 set win_1=win.win_1 from win where cde_features_a_1.subjectcode=win.subjectcode;

      val insertRndStmt = fr"INSERT INTO" ++ frName(extTable) ++ fr"(" ++ frNames(
        List(pk, rndColumn)
      ) ++ fr") (SELECT" ++ frName(pk) ++
        fr", random() as" ++ frName(rndColumn) ++ fr"FROM" ++
        frName(table) ++ frWhereFilter(filters) ++ fr"ORDER BY" ++ frName(rndColumn) ++ fr");"

      insertRndStmt.update.run
    }

    val extTable = table.copy(table = table.table.copy(name = s"${table.table.name}__$tableNum"),
                              validateSchema = false)
    for {
      _ <- createAdditionalFeaturesTable(extTable, pk)
      _ <- fillAdditionalFeaturesTable(extTable, pk)
    } yield extTable

  }

  private def createExtendedView(
      table: FeaturesTableDescription,
      pk: TableColumn,
      tableColumns: List[TableColumn],
      filters: Option[FilterRule],
      extTable: FeaturesTableDescription,
      rndColumn: TableColumn,
      newFeatures: List[TableColumn]
  ): ConnectionIO[(FeaturesTableDescription, Headers)] = {

    def createFeaturesView(table: FeaturesTableDescription,
                           pk: TableColumn,
                           tableColumns: Headers,
                           extTable: FeaturesTableDescription,
                           extTableColumns: Headers,
                           extView: FeaturesTableDescription,
                           extViewColumns: Headers): ConnectionIO[Int] = {

      val stmt = fr"CREATE OR REPLACE VIEW" ++ frName(extView) ++
        fr"(" ++ frNames(extViewColumns) ++ fr") AS SELECT" ++
        frQualifiedNames(table, tableColumns) ++ fr"," ++
        frQualifiedNames(extTable, extTableColumns.filter(_ != pk)) ++ fr"FROM" ++
        frName(table) ++ fr"INNER JOIN" ++ frName(extTable) ++ fr"ON" ++
        frEqual(table, List(pk), extTable, List(pk))

      stmt.update.run
    }

    val extTableColumns = newFeatures ++ List(rndColumn)
    val extViewDescription =
      extTable.copy(table = table.table.copy(name = s"${extTable.table.name}v"))
    val extViewColumns = tableColumns ++ extTableColumns.filter(_ != pk)

    for {
      _ <- createFeaturesView(table,
                              pk,
                              tableColumns,
                              extTable,
                              extTableColumns,
                              extViewDescription,
                              extViewColumns)
    } yield (extViewDescription, extViewColumns)

  }

}
