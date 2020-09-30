package cx.swop.correlation.domain

import java.util.UUID

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

case class Error(msg: String, correlationId: UUID)

object Error {
  implicit val encoder: Encoder[Error] = deriveEncoder
}
