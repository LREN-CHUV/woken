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

package ch.chuv.lren.woken.ssl

import java.security.{ KeyStore, SecureRandom }
import javax.net.ssl.{ KeyManagerFactory, SSLContext, TrustManagerFactory }

import akka.actor.ActorSystem
import akka.http.scaladsl.{ ConnectionContext, HttpsConnectionContext }
import com.typesafe.sslconfig.akka.AkkaSSLConfig

trait WokenSSLConfiguration {

  implicit val system: ActorSystem
  val sslConfig = AkkaSSLConfig()

  val wokenSSLContext: SSLContext = {
    val keyStoreRes = "/woken.com.jks"
    val password    = "X9PYRiFaPV"

    val keyStore = KeyStore.getInstance("jks")
    keyStore.load(getClass.getResourceAsStream(keyStoreRes), password.toCharArray)
    val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(keyStore, password.toCharArray)
    val trustManagerFactory = TrustManagerFactory.getInstance("SunX509")
    trustManagerFactory.init(keyStore)

    val context = SSLContext.getInstance("TLS")
    context.init(keyManagerFactory.getKeyManagers,
                 trustManagerFactory.getTrustManagers,
                 new SecureRandom)

    context
  }

  val https: HttpsConnectionContext = ConnectionContext.https(wokenSSLContext)
}
