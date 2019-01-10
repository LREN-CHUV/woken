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

import acolyte.jdbc.Implicits._
import acolyte.jdbc.RowLists.rowList1
import acolyte.jdbc._
import cats.effect.IO
import cats.scalatest.{ ValidatedMatchers, ValidatedValues }
import ch.chuv.lren.woken.messages.query.{ CodeValue, ValidationSpec }
import ch.chuv.lren.woken.messages.query.filters.{ InputType, Operator, SingleFilterRule }
import ch.chuv.lren.woken.validation.KFoldFeaturesSplitterDefinition
import org.scalatest.{ Matchers, WordSpec }

class ExtendedFeaturesTableRepositoryDAOTest
    extends WordSpec
    with Matchers
    with DAOTest
    with FeaturesTableTestSupport
    with ValidatedMatchers
    with ValidatedValues {

  val sampleTableHandler: ScalaCompositeHandler = AcolyteDSL.handleStatement
    .withQueryDetection("^SELECT ") // regex test from beginning
    .withQueryHandler { e: QueryExecution =>
      e.sql.trim match {
        case _ => throw new IllegalArgumentException(s"Unhandled $e")
      }
    }

  val cdeTableHandler1: ScalaCompositeHandler = AcolyteDSL.handleStatement
    .withQueryDetection("^SELECT ") // regex test from beginning
    .withQueryHandler { e: QueryExecution =>
      e.sql.trim match {
        case """SELECT setseed( 0.67 );""" => rowList1(classOf[Double]) :+ 0.67

        case """SELECT count(*) FROM "cde_features_a__1v"""" =>
          rowList1(classOf[Int]) :+ 99

        case _ =>
          fail(s"Unhandled $e")
      }
    }
    .withUpdateHandler { e: UpdateExecution =>
      val sql = e.sql.trim.replaceAll("""[\s\n]+""", " ")
      if (sql.startsWith("CREATE TABLE")) {
        sql shouldBe """CREATE TABLE "cde_features_a__1" ( "subjectcode" varchar(256) PRIMARY KEY, "_rnd" numeric ) WITH ( OIDS=FALSE );"""
        AcolyteDSL.updateResult(1, RowLists.longList.append(1L))
      } else if (sql.startsWith("INSERT INTO")) {
        sql shouldBe """INSERT INTO "cde_features_a__1" ( "subjectcode","_rnd" ) (SELECT "subjectcode" , random() as "_rnd" FROM "cde_features_a" ORDER BY "_rnd" );"""
        AcolyteDSL.updateResult(1, RowLists.longList.append(1L))
      } else if (sql.startsWith("CREATE OR REPLACE VIEW")) {
        sql shouldBe """CREATE OR REPLACE VIEW "cde_features_a__1v" ( "subjectcode","apoe4","lefthippocampus","dataset","_rnd" ) AS SELECT "cde_features_a"."subjectcode","cde_features_a"."apoe4","cde_features_a"."lefthippocampus","cde_features_a"."dataset" , "cde_features_a__1"."_rnd" FROM "cde_features_a" INNER JOIN "cde_features_a__1" ON "cde_features_a"."subjectcode" = "cde_features_a__1"."subjectcode""""
        AcolyteDSL.updateResult(1, RowLists.longList.append(1L))
      } else if (sql.startsWith("DROP VIEW")) {
        sql shouldBe """DROP VIEW IF EXISTS "cde_features_a__1v""""
        AcolyteDSL.updateResult(1, RowLists.longList.append(1L))
      } else if (sql.startsWith("DROP TABLE")) {
        sql shouldBe """DROP TABLE IF EXISTS "cde_features_a__1""""
        AcolyteDSL.updateResult(1, RowLists.longList.append(1L))
      } else {
        fail(s"Unhandled $sql")
      }
    }

  val cdeTableHandler2: ScalaCompositeHandler = AcolyteDSL.handleStatement
    .withQueryDetection("^SELECT ") // regex test from beginning
    .withQueryHandler { e: QueryExecution =>
      e.sql.trim match {
        case """SELECT setseed( 0.67 );""" => rowList1(classOf[Double]) :+ 0.67

        case """SELECT count(*) FROM "cde_features_a__1v"""" =>
          rowList1(classOf[Int]) :+ 15

        case _ =>
          fail(s"Unhandled $e")
      }
    }
    .withUpdateHandler { e: UpdateExecution =>
      val sql = e.sql.trim.replaceAll("""[\s\n]+""", " ")
      if (sql.startsWith("CREATE TABLE")) {
        sql shouldBe """CREATE TABLE "cde_features_a__1" ( "subjectcode" varchar(256) PRIMARY KEY, "_rnd" numeric ) WITH ( OIDS=FALSE );"""
        AcolyteDSL.updateResult(1, RowLists.longList.append(1L))
      } else if (sql.startsWith("INSERT INTO")) {
        sql shouldBe """INSERT INTO "cde_features_a__1" ( "subjectcode","_rnd" ) (SELECT "subjectcode" , random() as "_rnd" FROM "cde_features_a" WHERE "apoe4" = 2 ORDER BY "_rnd" );"""
        AcolyteDSL.updateResult(1, RowLists.longList.append(1L))
      } else if (sql.startsWith("CREATE OR REPLACE VIEW")) {
        sql shouldBe """CREATE OR REPLACE VIEW "cde_features_a__1v" ( "subjectcode","apoe4","lefthippocampus","dataset","_rnd" ) AS SELECT "cde_features_a"."subjectcode","cde_features_a"."apoe4","cde_features_a"."lefthippocampus","cde_features_a"."dataset" , "cde_features_a__1"."_rnd" FROM "cde_features_a" INNER JOIN "cde_features_a__1" ON "cde_features_a"."subjectcode" = "cde_features_a__1"."subjectcode""""
        AcolyteDSL.updateResult(1, RowLists.longList.append(1L))
      } else if (sql.startsWith("DROP VIEW")) {
        sql shouldBe """DROP VIEW IF EXISTS "cde_features_a__1v""""
        AcolyteDSL.updateResult(1, RowLists.longList.append(1L))
      } else if (sql.startsWith("DROP TABLE")) {
        sql shouldBe """DROP TABLE IF EXISTS "cde_features_a__1""""
        AcolyteDSL.updateResult(1, RowLists.longList.append(1L))
      } else {
        fail(s"Unhandled $sql")
      }
    }

  val cdeTableHandler3: ScalaCompositeHandler = AcolyteDSL.handleStatement
    .withQueryDetection("^SELECT ") // regex test from beginning
    .withQueryHandler { e: QueryExecution =>
      e.sql.trim match {
        case """SELECT setseed( 0.67 );""" => rowList1(classOf[Double]) :+ 0.67

        case """SELECT count(*) FROM "cde_features_a__1v"""" =>
          rowList1(classOf[Int]) :+ 15

        case _ =>
          fail(s"Unhandled $e")
      }
    }
    .withUpdateHandler { e: UpdateExecution =>
      val sql = e.sql.trim.replaceAll("""[\s\n]+""", " ")
      if (sql.startsWith("CREATE TABLE")) {
        sql shouldBe """CREATE TABLE "cde_features_a__1" ( "subjectcode" varchar(256) PRIMARY KEY, "_win_kfold_5" int,"_rnd" numeric ) WITH ( OIDS=FALSE );"""
        AcolyteDSL.updateResult(1, RowLists.longList.append(1L))
      } else if (sql.startsWith("INSERT INTO")) {
        sql shouldBe """INSERT INTO "cde_features_a__1" ( "subjectcode","_rnd" ) (SELECT "subjectcode" , random() as "_rnd" FROM "cde_features_a" WHERE "apoe4" = 2 ORDER BY "_rnd" );"""
        AcolyteDSL.updateResult(1, RowLists.longList.append(1L))
      } else if (sql.startsWith("WITH")) {
        sql shouldBe """WITH "win" as (SELECT "subjectcode" , ntile( 5 ) over (order by "_rnd" ) as win FROM "cde_features_a__1" ) UPDATE "cde_features_a__1" SET "_win_kfold_5" = "win".win FROM win WHERE "cde_features_a__1"."subjectcode" = "win"."subjectcode" ;"""
        AcolyteDSL.updateResult(1, RowLists.longList.append(1L))
      } else if (sql.startsWith("CREATE OR REPLACE VIEW")) {
        sql shouldBe """CREATE OR REPLACE VIEW "cde_features_a__1v" ( "subjectcode","apoe4","lefthippocampus","dataset","_win_kfold_5","_rnd" ) AS SELECT "cde_features_a"."subjectcode","cde_features_a"."apoe4","cde_features_a"."lefthippocampus","cde_features_a"."dataset" , "cde_features_a__1"."_win_kfold_5","cde_features_a__1"."_rnd" FROM "cde_features_a" INNER JOIN "cde_features_a__1" ON "cde_features_a"."subjectcode" = "cde_features_a__1"."subjectcode""""
        AcolyteDSL.updateResult(1, RowLists.longList.append(1L))
      } else if (sql.startsWith("DROP VIEW")) {
        sql shouldBe """DROP VIEW IF EXISTS "cde_features_a__1v""""
        AcolyteDSL.updateResult(1, RowLists.longList.append(1L))
      } else if (sql.startsWith("DROP TABLE")) {
        sql shouldBe """DROP TABLE IF EXISTS "cde_features_a__1""""
        AcolyteDSL.updateResult(1, RowLists.longList.append(1L))
      } else {
        fail(s"Unhandled $sql")
      }
    }

  "ExtendedFeaturesTableRepositoryDAO" should {

    "not be used from a table without a primary key" in withRepository[FeaturesTableRepositoryDAO[
      IO
    ]](
      sampleTableHandler,
      xa => {
        val wokenRepository = new WokenInMemoryRepository[IO]()
        val sourceTable =
          new FeaturesTableRepositoryDAO[IO](xa, sampleTable, sampleHeaders, wokenRepository)
        val extendedTableFromNoKeyTable = ExtendedFeaturesTableRepositoryDAO
          .apply[IO](sourceTable, None, Nil, Nil, Nil, wokenRepository.nextTableSeqNumber)
        extendedTableFromNoKeyTable should haveInvalid(
          "Extended features table expects a primary key of one column for table Sample"
        )
        sourceTable
      }
    ) { dao =>
      dao.table.table.name shouldBe "Sample"
    }

    "create an extended table without any new columns and use it" in withRepositoryResource[
      ExtendedFeaturesTableRepositoryDAO[
        IO
      ]
    ](
      cdeTableHandler1,
      xa => {
        val wokenRepository = new WokenInMemoryRepository[IO]()
        val sourceTable =
          new FeaturesTableRepositoryDAO[IO](xa, cdeTable, cdeHeaders, wokenRepository)
        ExtendedFeaturesTableRepositoryDAO
          .apply[IO](sourceTable, None, Nil, Nil, Nil, wokenRepository.nextTableSeqNumber)
          .value
      }
    ) { dao =>
      dao.count.unsafeRunSync() shouldBe 99
    }

    "create an extended table filtering original data and without any new columns and use it" in withRepositoryResource[
      ExtendedFeaturesTableRepositoryDAO[
        IO
      ]
    ](
      cdeTableHandler2,
      xa => {
        val wokenRepository = new WokenInMemoryRepository[IO]()
        val sourceTable =
          new FeaturesTableRepositoryDAO[IO](xa, cdeTable, cdeHeaders, wokenRepository)
        val filter =
          SingleFilterRule("apoe4", "apoe4", "number", InputType.number, Operator.equal, List("2"))
        ExtendedFeaturesTableRepositoryDAO
          .apply[IO](sourceTable, Some(filter), Nil, Nil, Nil, wokenRepository.nextTableSeqNumber)
          .value
      }
    ) { dao =>
      dao.count.unsafeRunSync() shouldBe 15
    }

    "create an extended table filtering original data and with new columns and use it" in withRepositoryResource[
      ExtendedFeaturesTableRepositoryDAO[
        IO
      ]
    ](
      cdeTableHandler3,
      xa => {
        val wokenRepository = new WokenInMemoryRepository[IO]()
        val sourceTable =
          new FeaturesTableRepositoryDAO[IO](xa, cdeTable, cdeHeaders, wokenRepository)
        val filter =
          SingleFilterRule("apoe4", "apoe4", "number", InputType.number, Operator.equal, List("2"))
        val validationSpec = ValidationSpec("kfold", List(CodeValue("k", "5")))
        val splitterDef    = KFoldFeaturesSplitterDefinition(validationSpec, 5)
        val newFeatures    = List(splitterDef.splitColumn)
        val prefills       = List(splitterDef)

        ExtendedFeaturesTableRepositoryDAO
          .apply[IO](sourceTable,
                     Some(filter),
                     newFeatures,
                     Nil,
                     prefills,
                     wokenRepository.nextTableSeqNumber)
          .value
      }
    ) { dao =>
      dao.count.unsafeRunSync() shouldBe 15
    }

  }
}
