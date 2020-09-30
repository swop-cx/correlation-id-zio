package cx.swop.correlation.domain

import java.time.LocalDate

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

case class ExchangeRate(baseCurrency: String, quoteCurrency: String, date: LocalDate, quote: BigDecimal)

object ExchangeRate {
  implicit val encoder: Encoder[ExchangeRate] = deriveEncoder
}
