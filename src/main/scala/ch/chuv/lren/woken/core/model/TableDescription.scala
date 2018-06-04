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

package ch.chuv.lren.woken.core.model

import ch.chuv.lren.woken.messages.query.UserId
import ch.chuv.lren.woken.messages.variables.SqlType.SqlType

/**
  * A column in a database.
  *
  * @param name Name of the column
  * @param sqlType Type of the column
  */
case class TableColumn(name: String, sqlType: SqlType)

/**
  * Description of a table containing features.
  *
  * @param database Name of the database, must be defined in DatabaseConfiguration
  * @param tableName Name of the table
  * @param primaryKey List of fields belonging to the primary key
  * @param owner Owner of the table, or None for global tables
  */
case class TableDescription(
    database: String,
    tableName: String,
    primaryKey: List[TableColumn],
    owner: Option[UserId]
)
