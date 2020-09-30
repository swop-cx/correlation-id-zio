package cx.swop.correlation

import java.util.UUID

import cats.data.{ Kleisli, NonEmptyList, OptionT }
import cats.effect
import cx.swop.correlation.db.{ FakeDatabase, FakePricingService }
import io.circe.Encoder
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.{ AuthMiddleware, Router }
import org.http4s._
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.circe.jsonEncoderOf
import zio._
import zio.internal.Platform
import zio.interop.catz._
import zio.logging.slf4j.Slf4jLogger
import zio.logging.{ log, LogAnnotation, Logging }
import cx.swop.correlation.domain.Error

import scala.concurrent.duration.{ FiniteDuration, NANOSECONDS, TimeUnit }

object Main extends App with Http4sDsl[RIO[Logging, *]] {

  implicit def circeJsonEncoder[A](implicit encoder: Encoder[A]): EntityEncoder[Task, A] = jsonEncoderOf[Task, A]

  override def run(args: List[String]): URIO[ZEnv, ExitCode] =
    serve(routes.orNotFound, 7777, "0.0.0.0")
      .foldCauseM(
        cause => log.error("Failed to start server", cause) *> ZIO.succeed(ExitCode.failure),
        _ => ZIO.succeed(ExitCode.success)
      )
      .provideSomeLayer[ZEnv](logger)

  def serve(httpApp: HttpApp[RIO[Logging, *]], port: Int, host: String): RIO[ZEnv with Logging, Unit] = {

    implicit val timer: effect.Timer[RIO[Logging, *]] =
      new effect.Timer[RIO[Logging, *]] {
        override final def clock: effect.Clock[RIO[Logging, *]] =
          new effect.Clock[RIO[Logging, *]] {
            override final def monotonic(unit: TimeUnit): RIO[Logging, Long] =
              zio.clock.nanoTime.map(unit.convert(_, NANOSECONDS)).provideLayer(ZEnv.live)

            override final def realTime(unit: TimeUnit): RIO[Logging, Long] =
              zio.clock.currentTime(unit).provideLayer(ZEnv.live)
          }

        override final def sleep(duration: FiniteDuration): RIO[Logging, Unit] =
          zio.clock.sleep(zio.duration.Duration.fromNanos(duration.toNanos)).provideLayer(ZEnv.live)
      }

    ZIO
      .runtime[ZEnv with Logging]
      .flatMap { implicit rts =>
        BlazeServerBuilder[RIO[Logging, *]](Platform.default.executor.asEC)
          .bindHttp(port, host)
          .withHttpApp(httpApp)
          .withServiceErrorHandler({ _ =>
            { case CorrelatedThrowable(uuid, cause) =>
              InternalServerError(Error(cause.getMessage, uuid))
            }
          })
          .serve
          .compile
          .drain
      }
  }

  val UserIdAnnotation: LogAnnotation[Option[Long]] = LogAnnotation[Option[Long]](
    name = "user-id",
    initialValue = None,
    combine = (_, r) => r,
    render = _.map(_.toString).getOrElse("undefined-user-id")
  )

  val logger: ULayer[Logging] =
    Slf4jLogger.makeWithAnnotationsAsMdc(
      List(LogAnnotation.CorrelationId, UserIdAnnotation),
      { (context, msg) =>
        val correlationId = LogAnnotation.CorrelationId.render(context.get(LogAnnotation.CorrelationId))
        val accountId     = UserIdAnnotation.render(context.get(UserIdAnnotation))
        "[correlation-id = %s][user-id = %s] - %s".format(correlationId, accountId, msg)
      }
    )

  val randomUUID: Task[UUID] = Task.effect(UUID.randomUUID())

  case class CorrelatedThrowable(correlationId: UUID, cause: Throwable) extends Throwable(cause)

  def correlationIdMiddleware(service: HttpRoutes[RIO[Logging, *]]): HttpRoutes[RIO[Logging, *]] =
    Kleisli { req =>
      val responseT = for {
        uuid <- randomUUID
        response <- log.locally(_.annotate(LogAnnotation.CorrelationId, Some(uuid)))(
          service(req).value.mapError(CorrelatedThrowable(uuid, _))
        )
      } yield response

      OptionT(responseT)
    }

  val userDB: Map[String, User] = Map(
    "1234" -> User(1234, "Big Corp Inc."),
    "4321" -> User(4321, "Smaller Corp Inc.")
  )

  case class User(id: Long, name: String)

  val authUser: Kleisli[OptionT[RIO[Logging, *], *], Request[RIO[Logging, *]], User] = Kleisli { req =>
    val key = req.params.get("api-key")
    OptionT.fromOption(key.flatMap(userDB.get))
  }

  val authMiddleware: AuthMiddleware[RIO[Logging, *], User] = { service =>
    Kleisli { (req: Request[RIO[Logging, *]]) =>
      val resp = authUser(req).value.flatMap {
        case Some(user) =>
          log.locally(_.annotate(UserIdAnnotation, Some(user.id)))(
            log.info(s"Authenticated user ${user.name}") *> service(AuthedRequest(user, req)).getOrElseF(NotFound())
          )
        case None =>
          log.warn("Unauthenticated access") *> Unauthorized(
            headers.`WWW-Authenticate`(NonEmptyList.of(Challenge("ApiKey", "Please obtain an API-key", Map.empty))),
            "Please obtain an API-key"
          )
      }
      OptionT.liftF(resp)
    }
  }

  val unauthenticated: HttpRoutes[RIO[Logging, *]] = HttpRoutes.of[RIO[Logging, *]] {
    case GET -> Root =>
      log.info("Serving the landing page") *> Ok("Hi, welcome to the exchange rate app, please get an API key")
    case GET -> Root / "pricing" =>
      log.info("Serving the pricing page") *> FakePricingService.findPricing() *> Ok()
  }

  val authenticated: AuthedRoutes[User, RIO[Logging, *]] = AuthedRoutes.of[User, RIO[Logging, *]] {
    case GET -> Root / "rate" as user =>
      log.info(s"Got request to find a rate for ${user.name}") *> FakeDatabase.findExchangeRate() >>= (Ok(_))
    case GET -> Root / "rate" / "fail" as user =>
      log.info(s"Got request to find a rate for ${user.name}") *> FakeDatabase.failToFindExchangeRate() >>= (Ok(_))
  }

  val routes =
    correlationIdMiddleware(
      Router(
        "/"    -> unauthenticated,
        "/api" -> authMiddleware(authenticated)
      )
    )
}
