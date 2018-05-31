package ch.chuv.lren.woken.core.model

import ch.chuv.lren.woken.messages.query.UserId

case class TableDescription(
                           name: String,
                           primaryKey: List[String],
                           owner: Option[UserId]
                           )
