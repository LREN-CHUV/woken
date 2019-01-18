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

package ch.chuv.lren.woken.dispatch

/**
  * Copy left Alexander Hasselbach
  *
  * Usage:
  *
  * val E = b.add(splitEither[Throwable,Int])
  * val parsed = b.add(Flow[Either[Throwable,Int]])
  * val valids = b.add(Flow[Int])
  * val invalids = b.add(Flow[Throwable])
  *
  * parsed ~> E.in
  * E.left  ~> invalids
  * E.right ~> valids
  *
  */
import akka.NotUsed
import akka.stream.Graph
import akka.stream.scaladsl.{ Broadcast, Flow, GraphDSL }

object GraphUtils {
  def splitEither[L, R]: Graph[EitherFanOutShape[Either[L, R], L, R], NotUsed] =
    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      val input    = b.add(Flow[Either[L, R]])
      val bcast    = b.add(Broadcast[Either[L, R]](outputPorts = 2))
      val leftOut  = b.add(Flow[Either[L, R]].collect { case Left(l) => l })
      val rightOut = b.add(Flow[Either[L, R]].collect { case Right(r) => r })

      input ~> bcast ~> leftOut
      bcast ~> rightOut

      new EitherFanOutShape(input.in, leftOut.out, rightOut.out)
    }

  ///

  import _root_.akka.stream.{ FanOutShape, Inlet, Outlet }

  class EitherFanOutShape[In, L, R](_init: FanOutShape.Init[In]) extends FanOutShape[In](_init) {
    def this(name: String) = this(FanOutShape.Name[In](name))
    def this(in: Inlet[In], left: Outlet[L], right: Outlet[R]) =
      this(FanOutShape.Ports(in, left :: right :: Nil))

    override protected def construct(init: FanOutShape.Init[In]): FanOutShape[In] =
      new EitherFanOutShape(init)
    override def deepCopy(): EitherFanOutShape[In, L, R] =
      super.deepCopy().asInstanceOf[EitherFanOutShape[In, L, R]]

    val left: Outlet[L]  = newOutlet[L]("left")
    val right: Outlet[R] = newOutlet[R]("right")
  }

}
