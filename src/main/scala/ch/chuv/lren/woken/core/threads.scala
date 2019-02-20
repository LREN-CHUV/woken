package ch.chuv.lren.woken.core

import java.util.concurrent.{ExecutorService, Executors}

import cats.effect.{Resource, Sync}
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
