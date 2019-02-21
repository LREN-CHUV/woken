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

// Portions of code copied from Doobie, under Apache2.0 copyright

package ch.chuv.lren.woken.core

import java.util.concurrent.{ ExecutorService, Executors }

import cats.effect.{ Resource, Sync }
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.ExecutionContext
import scala.language.higherKinds

object threads extends LazyLogging {

  /** Resource yielding an `ExecutionContext` backed by a fixed-size pool. */
  def fixedThreadPool[F[_]](size: Int)(
      implicit sf: Sync[F]
  ): Resource[F, ExecutionContext] = {
    val alloc = sf.delay(Executors.newFixedThreadPool(size))
    val free  = (es: ExecutorService) => sf.delay(es.shutdown())
    Resource.make(alloc)(free).map(executor => ExecutionContext.fromExecutor(executor, reporter))
  }

  /** Resource yielding an `ExecutionContext` backed by an unbounded thread pool. */
  def cachedThreadPool[F[_]](
      implicit sf: Sync[F]
  ): Resource[F, ExecutionContext] = {
    val alloc = sf.delay(Executors.newCachedThreadPool)
    val free  = (es: ExecutorService) => sf.delay(es.shutdown())
    Resource.make(alloc)(free).map(executor => ExecutionContext.fromExecutor(executor, reporter))
  }

  private def reporter(t: Throwable): Unit =
    logger.error(s"Uncaught error: ${t.getMessage}", t)
}
