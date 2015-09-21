package dao

import core.model.results.BoxPlotResult
import config.DatabaseConfig._
import profile.api._

trait BoxPlotResultDao {

  def create()

  def get(requestId: String): DBIO[Seq[BoxPlotResult]]

}

trait BoxPlotResultDaoSlickImpl extends BoxPlotResultDao {

  class BoxPlotResults(tag: Tag) extends Table[BoxPlotResult](tag, "box_plot_results") {
    def id: Rep[Int] = column[Int]("id", O.PrimaryKey, O.AutoInc)
    def requestId: Rep[String] = column[String]("request_id")
    def min: Rep[Double] = column[Double]("min")
    def q1: Rep[Double] = column[Double]("q1")
    def median: Rep[Double] = column[Double]("median")
    def q3: Rep[Double] = column[Double]("q3")
    def max: Rep[Double] = column[Double]("max")

    def * = (requestId, id, min, q1, median, q3, max) <>((BoxPlotResult.apply _).tupled, BoxPlotResult.unapply)
  }

  val boxPlotResults = TableQuery[BoxPlotResults]

  override def create() = boxPlotResults.schema.create

  override def get(requestId: String): DBIO[Seq[BoxPlotResult]] = boxPlotResults.filter(_.requestId === requestId).result

}

object BoxPlotResultDao extends BoxPlotResultDaoSlickImpl

