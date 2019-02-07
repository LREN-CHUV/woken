package ch.chuv.lren.woken.core

import akka.event.Logging
import akka.stream.Attributes

object streams {

  /**
   * Stream attribute used to log stream elements at debug level
   */
  val debugElements: Attributes =
    Attributes.logLevels(
      onElement = Logging.DebugLevel,
      onFinish = Logging.InfoLevel,
      onFailure = Logging.ErrorLevel
    )

}
