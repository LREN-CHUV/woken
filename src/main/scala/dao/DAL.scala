package dao

import slick.driver.JdbcProfile

trait DAL extends JobResultComponent with DriverComponent

/**
  * The Data Access Layer contains all components and a driver
  */
class NodeDAL(val driver: JdbcProfile) extends DAL with NodeJobResultComponent {
  import driver.api._

  def create = jobResults.schema.create

}

class FederationDAL(val driver: JdbcProfile) extends DAL with FederationJobResultComponent {
  // create schema not possible with Denodo
}