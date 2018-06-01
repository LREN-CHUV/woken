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

import ch.chuv.lren.woken.messages.variables.{
  EnumeratedValue,
  SqlType,
  VariableMetaData,
  VariableType
}

object CdeVariables {

  val apoe4 = VariableMetaData(
    "apoe4",
    "ApoE4",
    VariableType.polynominal,
    Some(SqlType.int),
    Some("adni-merge"),
    Some(
      "Apolipoprotein E (APOE) e4 allele: is the strongest risk factor for Late Onset Alzheimer Disease (LOAD). At least one copy of APOE-e4 "
    ),
    None,
    Some(
      List(EnumeratedValue("0", "0"), EnumeratedValue("1", "1"), EnumeratedValue("2", "2"))
    ),
    None,
    None,
    None,
    None,
    Set()
  )
  val leftHipocampus = VariableMetaData("lefthippocampus",
                                        "Left Hippocampus",
                                        VariableType.real,
                                        None,
                                        Some("lren-nmm-volumes"),
                                        Some(""),
                                        Some("cm3"),
                                        None,
                                        None,
                                        None,
                                        None,
                                        None,
                                        Set())

}
