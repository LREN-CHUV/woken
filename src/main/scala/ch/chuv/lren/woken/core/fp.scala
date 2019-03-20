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

import cats.effect.{Effect, ExitCase, IO, LiftIO}
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future
import scala.language.higherKinds
import scala.util.control.NonFatal

object fp {

  def runNow[F[_]: Effect, M](m: F[M]): M           = Effect[F].toIO(m).unsafeRunSync()

  def runLater[F[_]: Effect, M](m: F[M]): Future[M] = {
    val errorRecovery: Throwable => F[M] = {
      case NonFatal(t) => Effect[F].raiseError(t)
      case fatal => throw fatal
    }
    runLater(m , errorRecovery)
  }

  def runLater[F[_]: Effect, M](m: F[M], errorRecovery: Throwable => F[M]): Future[M] =
    Effect[F].toIO(m).handleErrorWith(t => Effect[F].toIO(errorRecovery(t))).unsafeToFuture()

  def fromFuture[F[_]: Effect, R](f: => Future[R]): F[R] = implicitly[LiftIO[F]].liftIO(
    IO.fromFuture(IO(f))
  )

  def fromFutureWithGuarantee[F[_]: Effect, R](f: => Future[R],
                                               finalizer: ExitCase[Throwable] => IO[Unit]): F[R] =
    implicitly[LiftIO[F]].liftIO(
      IO.fromFuture(IO(f).guaranteeCase(finalizer))
    )

  def logErrorFinalizer(logger: Logger, error: => String): ExitCase[Throwable] => IO[Unit] = {
    case ExitCase.Error(t) =>
      IO.delay(
        logger.error(error, t)
      )
    case _ => IO(())
  }

  implicit class FutureExtended[R](val f: Future[R]) {
    def fromFuture[F[_]: Effect]: F[R] = fp.fromFuture(f)
    def fromFutureWithGuarantee[F[_]: Effect](finalizer: ExitCase[Throwable] => IO[Unit]): F[R] =
      fp.fromFutureWithGuarantee(f, finalizer)
  }

}
