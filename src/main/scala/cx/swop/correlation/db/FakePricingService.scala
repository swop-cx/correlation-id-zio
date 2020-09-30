package cx.swop.correlation.db

import zio.{ RIO, Task }
import zio.logging.{ log, Logging }

object FakePricingService {

  def findPricing(): RIO[Logging, Unit] =
    log.info("Getting pricing information") *>
      Task
        .fail(new RuntimeException("Cannot charge for only one exchange rate"))
        .tapCause(log.error("Failed to get pricing", _))

}
