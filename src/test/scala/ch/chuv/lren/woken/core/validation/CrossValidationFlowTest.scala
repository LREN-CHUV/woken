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

package ch.chuv.lren.woken.core.validation

import akka.actor.ActorSystem
import akka.testkit.TestKit
import ch.chuv.lren.woken.util.JsonUtils
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

class CrossValidationFlowTest
    extends TestKit(ActorSystem("MySpec"))
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with JsonUtils {
  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }
}
