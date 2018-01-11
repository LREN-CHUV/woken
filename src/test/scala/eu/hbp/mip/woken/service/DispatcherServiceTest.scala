package eu.hbp.mip.woken.service

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import com.typesafe.config.{Config, ConfigFactory}
import eu.hbp.mip.woken.backends.woken.WokenService
import eu.hbp.mip.woken.config.DatasetsConfiguration
import eu.hbp.mip.woken.messages.external.DatasetId
import org.scalatest.{Matchers, WordSpecLike}

class DispatcherServiceTest extends TestKit(ActorSystem("DispatcherServiceSpec")) with WordSpecLike with Matchers {

  implicit val mat: ActorMaterializer = ActorMaterializer()

  val config: Config = ConfigFactory.load()

  val wokenService = WokenService("test")

  val dispatcherService: DispatcherService =
    DispatcherService(DatasetsConfiguration.datasets(config), wokenService)

  "Dispatcher service" should {
    "identify locations to dispatch to from a list of datasets" in {

      val datasets = Set("sample", "churn", "remoteData1", "remoteData2", "remoteData3").map(DatasetId)

      val (remotes, local) = dispatcherService.dispatchTo(datasets)

      val expectedRemotes = Set("remoteData1", "remoteData2", "remoteData3").map(DatasetId)

      local shouldBe true

      remotes shouldBe expectedRemotes

    }

  }

}
