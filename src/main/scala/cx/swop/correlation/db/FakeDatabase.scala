package cx.swop.correlation.db

import java.time.LocalDate

import cx.swop.correlation.domain.ExchangeRate
import zio.logging.{ log, Logging }
import zio.{ RIO, Task }

object FakeDatabase {

  def findExchangeRate(): RIO[Logging, ExchangeRate] =
    log.info("Querying DB for exchange rate") *>
      Task.effect(ExchangeRate("EUR", "USD", LocalDate.now(), BigDecimal("1.17"))) <*
      log.info("Found exchange rate")

  def failToFindExchangeRate(): RIO[Logging, ExchangeRate] =
    log.info("Querying DB for exchange rate") *>
      Task
        .fail(new RuntimeException("DB connection failed"))
        .tapCause(log.error("Failed to find exchange rate", _))

}
