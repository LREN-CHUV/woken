package dao

import slick.driver.JdbcProfile

/**
  * The Data Access Layer contains all components and a driver
  */
class DAL(val driver: JdbcProfile) extends JobResultComponent with DriverComponent {
  import driver.api._

  def create = jobResults.schema.create

}