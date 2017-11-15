/*
 * Copyright 2017 Human Brain Project MIP by LREN CHUV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hbp.mip.woken.web

/**
  * Provides the web server (spray-can) for the REST api in ``Api``, using the actor system
  * defined in ``Core``.
  *
  * You may sometimes wish to construct separate ``ActorSystem`` for the web server machinery.
  * However, for this simple application, we shall use the same ``ActorSystem`` for the
  * entire application.
  *
  * Benefits of separate ``ActorSystem`` include the ability to use completely different
  * configuration, especially when it comes to the threading core.model.
  */
object Web extends App with BootedCore with Rest
