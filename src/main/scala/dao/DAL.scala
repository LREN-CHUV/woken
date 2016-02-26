package dao

import java.sql.{DriverManager, ResultSet, ResultSetMetaData, Connection}
import java.time.{ZoneOffset, OffsetDateTime}

import core.model.JobResult
import doobie.imports._
import spray.json._

import scalaz.effect.IO

/**
  * Created by ludovic on 25/11/15.
  */
trait DAL {
}

object DAL {
  implicit val DateTimeMeta: Meta[OffsetDateTime] =
    Meta[java.sql.Timestamp].nxmap(
      ts => OffsetDateTime.of(ts.toLocalDateTime, ZoneOffset.UTC),
      dt => java.sql.Timestamp.valueOf(dt.toLocalDateTime)
    )
}

trait JobResultsDAL extends DAL {

  def findJobResults(jobId: String): List[JobResult]

}

class NodeDAL(xa: Transactor[IO]) extends JobResultsDAL {
  import DAL._

  def queryJobResults(jobId: String): ConnectionIO[List[JobResult]] = sql"select job_id, node, timestamp, data, error, shape, function from job_result where job_id = $jobId".query[JobResult].list

  override def findJobResults(jobId: String) = queryJobResults(jobId).transact(xa).unsafePerformIO

}

class FederationDAL(xa: Transactor[IO]) extends JobResultsDAL {
  import DAL._

  def queryJobResults(jobId: String): ConnectionIO[List[JobResult]] = sql"select job_id, node, timestamp, data, error, shape, function from job_result_nodes where job_id = $jobId".query[JobResult].list

  override def findJobResults(jobId: String) = queryJobResults(jobId).transact(xa).unsafePerformIO

}

class LdsmDAL(jdbcDriver: String, jdbcUrl: String, jdbcUser: String, jdbcPassword: String, table: String) extends DAL {

  Class.forName(jdbcDriver)
  val ldsmConnection: Connection = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword)

  case class ColumnMeta(index: Int, label: String, datatype: String)

  def runQuery(dbConnection: Connection, query: String): (List[ColumnMeta], Stream[JsObject]) = {
    val rs = dbConnection.prepareStatement(query).executeQuery
    implicit val cols = getColumnMeta(rs.getMetaData)
    (cols, getStreamOfResults(rs))
  }

  /**
    * Returns a list of columns for specified ResultSet which describes column properties we are interested in.
    */
  def getColumnMeta(rsMeta: ResultSetMetaData): List[ColumnMeta] =
    (for {
      idx <- (1 to rsMeta.getColumnCount)
      colName = rsMeta.getColumnLabel(idx).toLowerCase
      colType = rsMeta.getColumnClassName(idx)
    } yield ColumnMeta(idx, colName, colType)).toList

  /**
    * Creates a stream of results on top of a ResultSet.
    */
  def getStreamOfResults(rs: ResultSet)(implicit cols: List[ColumnMeta]): Stream[JsObject] =
    new Iterator[JsObject] {
      def hasNext = rs.next
      def next() = {
        rowToObj(rs)
      }
    }.toStream

  /**
    * Given a row from a ResultSet produces a JSON document.
    */
  def rowToObj(rs: ResultSet)(implicit cols: List[ColumnMeta]): JsObject = {
    val fields = for {
      ColumnMeta(index, label, datatype) <- cols
      clazz = Class.forName(datatype)
      value = columnValueGetter(datatype, index, rs)
    } yield (label -> value)
    JsObject(fields: _*)
  }

  /**
    * Takes a fully qualified Java type as String and returns one of the subtypes of JValue by fetching a value
    * from result set and converting it to proper type.
    * It supports only the most common types and everything else that does not match this conversion is converted
    * to String automatically. If you see that you results should contain more specific type instead of String
    * add conversion cases to {{{resultsetGetters}}} map.
    */
  def columnValueGetter(datatype: String, columnIdx: Int, rs: ResultSet): JsValue = {
    val obj = rs.getObject(columnIdx)
    if (obj == null)
      JsNull
    else {
      val converter = resultsetGetters getOrElse (datatype, (obj: Object) => JsString(obj.toString))
      converter(obj)
    }
  }

  val resultsetGetters: Map[String, Object => JsValue] = Map(
    "java.lang.Integer" -> ((obj: Object) => JsNumber(obj.asInstanceOf[Int])),
    "java.lang.Long" -> ((obj: Object) => JsNumber(obj.asInstanceOf[Long])),
    "java.lang.Double" -> ((obj: Object) => JsNumber(obj.asInstanceOf[Double])),
    "java.lang.Float" -> ((obj: Object) => JsNumber(obj.asInstanceOf[Float])),
    "java.lang.Boolean" -> ((obj: Object) => JsBoolean(obj.asInstanceOf[Boolean])),
    "java.lang.String" -> ((obj: Object) => JsString(obj.asInstanceOf[String])))

  def queryData(columns: Seq[String]) = JsArray(runQuery(ldsmConnection, s"select ${columns.mkString(",")} from $table")._2.toVector)

}