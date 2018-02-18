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

package ch.chuv.lren.woken.fp

import cats.Applicative
import cats.implicits._

import scala.language.higherKinds

object Traverse {

  def traverse[F[_]: Applicative, A, B](values: List[A])(func: A => F[B]): F[List[B]] =
    values.foldLeft(List.empty[B].pure[F]) { (accum, host) =>
      (accum, func(host)).mapN(_ :+ _)
    }

  def sequence[F[_]: Applicative, B](fs: List[F[B]]): F[List[B]] =
    traverse(fs)(identity)

  def traverse[F[_]: Applicative, A, B](values: Set[A])(func: A => F[B]): F[Set[B]] =
    values.foldLeft(Set.empty[B].pure[F]) { (accum, host) =>
      (accum, func(host)).mapN(_ + _)
    }

  def sequence[F[_]: Applicative, B](fs: Set[F[B]]): F[Set[B]] =
    traverse(fs)(identity)

}
