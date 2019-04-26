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

package ch.chuv.lren.woken.main

import cats.effect.{ ExitCode, IO, IOApp }
import cats.syntax.all._

import ch.chuv.lren.woken.config.mainConfig
import ch.chuv.lren.woken.errors
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import scala.io.StdIn
import scala.concurrent.duration._

/**
  * Main run loop
  *
  * @author Ludovic Claude <ludovic.claude@chuv.ch>
  */
object Main extends IOApp {

  protected lazy val logger: Logger =
    Logger(LoggerFactory.getLogger("Woken"))

  def run(args: List[String]): IO[ExitCode] = {
    // Report automatically all errors to Bugsnag
    errors.reportErrorsToBugsnag()

    println("""
        |  _      __     __              __  _____
        | | | /| / /__  / /_____ ___    /  |/  / /
        | | |/ |/ / _ \/  '_/ -_) _ \  / /|_/ / /__
        | |__/|__/\___/_/\_\\__/_//_/ /_/  /_/____/
        |
      """.stripMargin)
    logger.info(s"Running on ${Runtime.getRuntime.availableProcessors} processors")

    mainConfig.flatMap { config =>
      MainServer.resource[IO](config).use { _ =>
        val io = for {
          _    <- IO(logger.info("[OK] Woken startup complete.")) // scalastyle:off
          _    <- IO(Console.println("Type 'exit' then <Enter> to exit.")) // scalastyle:off
          exit <- loop(stop = false)
        } yield exit

        io.handleErrorWith { err =>
          IO.delay {
            logger.error("Unexpected error", err)
            ExitCode.Error
          }
        }
      }
    }
  }

  private def loop(stop: Boolean): IO[ExitCode] =
    IO.suspend {
      if (stop)
        IO.delay {
          logger.info("Stopping Woken...")
          ExitCode.Success
        } else
        IO.sleep(1.second) *> IO
          .delay(Option(StdIn.readLine()).exists(_.toLowerCase == "exit"))
          .flatMap(loop)
    }
}
