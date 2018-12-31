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

package ch.chuv.lren.woken.core.model.database

import ch.chuv.lren.woken.messages.query.UserId
import ch.chuv.lren.woken.messages.variables.SqlType.SqlType

/**
  * A column in a database.
  *
  * @param name Name of the column
  * @param sqlType Type of the column
  */
case class TableColumn(name: String, sqlType: SqlType) {

  /**
    * @return the quoted name of the column, for use in SELECT statements
    */
  def quotedName: String = s""""$name""""

}

// TODO: shouldn't the seed be moved into the query?

/**
  * Description of a table containing features.
  *
  * @param table Identifier of the table
  * @param primaryKey List of fields belonging to the primary key.
  * @param datasetColumn Column if it exists used to discriminate rows on the dataset owning them.
  * @param validateSchema Validate the schema, in particular the columns used for the primary key and the dataset.
  *                       This may need to be disabled as it relies on using the Postgres table 'information_schema'
  *                       which may not be available in some cases, in particular if the features table is a proxy table.
  * @param owner Owner of the table, or None for global tables.
  * @param seed Seed for random number generator. The same seed is used for all operations on the table in order to
  *             ensure reproducibility of the results.
  */
case class FeaturesTableDescription(
    table: TableId,
    primaryKey: List[TableColumn],
    datasetColumn: Option[TableColumn],
    validateSchema: Boolean,
    owner: Option[UserId],
    seed: Double
) {

  /**
    * @return the quoted name of the table, for use in SELECT statements
    */
  def quotedName: String =
    table.dbSchema.fold(s""""${table.name}"""")(sch => s""""$sch"."${table.name}"""")

}
