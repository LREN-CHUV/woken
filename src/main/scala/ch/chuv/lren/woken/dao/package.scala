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

package ch.chuv.lren.woken
import ch.chuv.lren.woken.core.model.{ FeaturesTableDescription, TableColumn }
import ch.chuv.lren.woken.messages.variables.SqlType.SqlType
import doobie.Fragment

package object dao {

  import acyclic.pkg

  /**
    * Internal utilities
    */
  private[dao] object utils {
    def frName(table: FeaturesTableDescription): Fragment = Fragment.const(table.quotedName)
    def frName(col: TableColumn): Fragment                = Fragment.const(col.quotedName)
    def frType(col: TableColumn): Fragment                = Fragment.const(toSql(col.sqlType))
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

    import ch.chuv.lren.woken.messages.variables.{ SqlType => SqlT }
    def toSql(sqlType: SqlType): String = sqlType match {
      case SqlT.int     => "int"
      case SqlT.numeric => "number"
      case SqlT.char    => "char(256)"
      case SqlT.varchar => "varchar(256)"
    }

  }

}
