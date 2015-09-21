package api

import spray.json.DefaultJsonProtocol

case class ResultDto(
                      requestId: String,
                      stats: Option[Stats],
                      model: Option[Model]
                      )

case class Stats(
                  min: List[Double],
                  max: List[Double],
                  median: List[Double],
                  q1: List[Double],
                  q3: List[Double]
                  )

case class Model(
                  betas: List[Double],
                  sigmas: List[Double]
                  )

object ResultDto extends DefaultJsonProtocol {
  implicit val statsFormat = jsonFormat5(Stats.apply)
  implicit val modelFormat = jsonFormat2(Model.apply)
  implicit val resultDtoFormat = jsonFormat3(ResultDto.apply)
}