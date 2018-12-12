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

package ch.chuv.lren.woken.core

import ch.chuv.lren.woken.core.model.{ FeaturesTableDescription, TableColumn }
import ch.chuv.lren.woken.messages.query.filters.FilterRule
import ch.chuv.lren.woken.messages.variables.SqlType.SqlType
import doobie.util.fragment.Fragment
import doobie.implicits._

object sqlUtils {

  import acyclic.pkg

  /**
    * Internal utilities
    */
  def frName(table: FeaturesTableDescription): Fragment = Fragment.const(table.quotedName)
  def frName(col: TableColumn): Fragment                = Fragment.const(col.quotedName)
  def frType(col: TableColumn): Fragment                = Fragment.const(toSql(col.sqlType))
  def frConst(d: Int): Fragment                         = Fragment.const(d.toString)
  def frConst(d: Double): Fragment                      = Fragment.const(d.toString)

  def frNames(cols: List[TableColumn]): Fragment =
    Fragment.const(cols.map(_.quotedName).mkString(","))

  def frQualifiedName(table: FeaturesTableDescription, col: TableColumn): Fragment =
    Fragment.const(s"""${table.quotedName}.${col.quotedName}""")
  def frQualifiedNames(table: FeaturesTableDescription, cols: List[TableColumn]): Fragment =
    Fragment.const(cols.map(col => s"""${table.quotedName}.${col.quotedName}""").mkString(","))

  def frNameType(col: TableColumn): Fragment =
    Fragment.const(s"${col.quotedName} ${toSql(col.sqlType)}")
  def frNameType(cols: List[TableColumn]): Fragment =
    Fragment.const(
      cols
        .map { f =>
          s"${f.quotedName} ${toSql(f.sqlType)}"
        }
        .mkString(",")
    )
  def frEqual(table1: FeaturesTableDescription,
              cols1: List[TableColumn],
              table2: FeaturesTableDescription,
              cols2: List[TableColumn]): Fragment = {
    assert(cols1.size == cols2.size, "Lists should have the same size")
    val frEmpty = fr""
    cols1
      .map(frQualifiedName(table1, _))
      .zip(cols2.map(frQualifiedName(table2, _)))
      .map { case (c1, c2) => c1 ++ fr"=" ++ c2 }
      .foldRight(frEmpty) {
        case (start, rest) if rest == frEmpty => start
        case (start, rest)                    => start ++ fr"AND" ++ rest
      }
  }

  import ch.chuv.lren.woken.messages.variables.{ SqlType => SqlT }
  def toSql(sqlType: SqlType): String = sqlType match {
    case SqlT.int     => "int"
    case SqlT.numeric => "numeric"
    case SqlT.char    => "char(256)"
    case SqlT.varchar => "varchar(256)"
  }

  def frWhereFilter(filter: Option[FilterRule]): Fragment =
    filter.fold(fr"")(f => Fragment.const(" WHERE " + f.withAdaptedFieldName.toSqlWhere))

}
