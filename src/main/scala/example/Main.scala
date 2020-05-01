package example

import java.time.OffsetDateTime
import java.util.Properties

import scala.collection.immutable.ArraySeq
import scala.collection.mutable

import cats.implicits._
import example.Main.SingleTestResult.HttpResult
import sttp.client.asynchttpclient.zio.{AsyncHttpClientZioBackend, SttpClient}
import sttp.client.basicRequest
import sttp.model._
import zio._
import zio.clock.Clock
import zio.console._
import zio.duration._
import zio.interop.catz.core._
import zio.stream._

object Main extends App {

  val StableAfter: Duration = 20.seconds

  case class Node(name: String, addr: Uri)
  case class Endpoint(
      method: Method,
      path: List[String]
  )

  case class Cluster(nodes: List[Node])

  case class RunInfo(
      cluster: Cluster,
      nodeToTrigger: Node,
      endpointToTrigger: Endpoint,
      endpointsToMonitor: List[Endpoint],
      monitorRequestCount: Int,
      description: String
  )

  case class SingleTestRequest(
      node: Node,
      endpoint: Endpoint
  ) {
    lazy val method = endpoint.method
    lazy val uri    = node.addr.path(endpoint.path)
  }

  case class SingleTestResult(
      request: SingleTestRequest,
      timestamp: OffsetDateTime,
      httpResult: SingleTestResult.HttpResult
  ) {
    def isSuccess: Boolean =
      httpResult match {
        case HttpResult.HttpResponse(code, _, _) => code.isSuccess
        case HttpResult.ConnectionFailed(_)      => false
      }
  }
  object SingleTestResult {
    sealed trait HttpResult extends Serializable with Product
    object HttpResult {
      case class HttpResponse(
          code: StatusCode,
          body: String,
          responseTime: Duration
      ) extends HttpResult
      case class ConnectionFailed(underlying: Throwable) extends HttpResult
    }
  }

  def getProperties(key: String): ZIO[Properties, Throwable, String] =
    ZIO.accessM[Properties] { p =>
      Task(Option(p.getProperty(key)))
        .collect(new Exception(s"key $key not found")) { case Some(v) => v }
    }

  def getConfig(fileName: String): Task[RunInfo] = {
    val properties = for {
      file0 <- Task(Option(scala.io.Source.fromFile(fileName).bufferedReader()))
      file <- file0 match {
                case Some(value) => ZIO.succeed(value)
                case None =>
                  ZIO.fail(new Exception(s"Config file: $fileName not found"))
              }
      properties <- Task { val prop = new Properties(); prop.load(file); prop }
    } yield properties

    def parseNode(s: String): IO[Throwable, Node] = {
      val Array(name, uri) = s.split(" ")
      ZIO.fromEither(Uri.parse(uri.trim)).bimap(new Exception(_), Node(name, _))
    }

    def parseEndpoint(s: String): IO[Throwable, Endpoint] = {
      val Array(method, path) = s.trim.stripPrefix("/").split(" ")
      val result = Method.safeApply(method).map { method =>
        Endpoint(
          method,
          path.trim
            .stripPrefix("\"")
            .stripSuffix("\"")
            .split("/")
            .filterNot(_ == "")
            .toList
        )
      }
      ZIO.fromEither(result).mapError(new Exception(_))
    }

    properties.flatMap { p =>
      (
        getProperties("node.list").flatMap(s => ZIO.foreach(s.split(";"))(parseNode)),
        getProperties("node.to_trigger"),
        getProperties("endpoint.trigger").flatMap(parseEndpoint),
        getProperties("endpoint.monitor").flatMap(s => ZIO.foreach(s.split(";"))(parseEndpoint)),
        getProperties("description"),
        getProperties("monitor.request.count").flatMap(s =>
          ZIO
            .fromOption(s.toIntOption)
            .mapError(_ => new Exception(s"$s can't be parsed as Int"))
        )
      ).tupled.flatMap {
        case (
              nodeList,
              nodeToTrigger,
              endpointTrigger,
              endpointMonitor,
              description,
              monitorRequestCount
            ) =>
          ZIO.fromOption(nodeList.find(_.name == nodeToTrigger)).mapError(_ =>
            new Exception(s"node.to_trigger=$nodeToTrigger is not found in the list of nodes")
          ).map { nodeToTrigger =>
            RunInfo(
              Cluster(nodeList),
              nodeToTrigger = nodeToTrigger,
              endpointToTrigger = endpointTrigger,
              endpointsToMonitor = endpointMonitor,
              monitorRequestCount = monitorRequestCount,
              description = description
            )
          }
      }
        .provide(p)
    }
  }

  def fireRequests(
      requests: List[SingleTestRequest]
  ): ZStream[
    Console with Clock with SttpClient,
    Nothing,
    (Int, Fiber[Nothing, List[SingleTestResult]])
  ] = {
    ZStream.unwrap {
      Ref.make(1).map { count =>
        ZStream
          .fromEffect(
            count.get.flatMap(c => putStrLn(s"Firing request $c")) *>
              fireRequest(requests).forkDaemon <*
              count.update(_ + 1)
          )
          .repeat(Schedule.fixed(500.millis))
          .mapM(fiber => count.get.map(count => (count - 1) -> fiber))
      }
    }

  }

  def fireRequest(
      requests: List[SingleTestRequest]
  ): URIO[Clock with SttpClient, List[SingleTestResult]] =
    for {
      timestamp <- clock.currentDateTime.orDie
      result <- ZIO.foreach(requests) { request =>
                  val req = basicRequest.method(request.method, request.uri)
                  SttpClient.send(req).timed
                    .fold(
                      err =>
                        SingleTestResult(
                          request,
                          timestamp,
                          HttpResult.ConnectionFailed(err)
                        ),
                      {
                        case (responseTime, res) =>
                          SingleTestResult(
                            request,
                            timestamp,
                            HttpResult.HttpResponse(
                              res.code,
                              res.body.merge,
                              responseTime
                            )
                          )
                      }
                    )
                }
    } yield result

  private def formatOne(res: SingleTestResult): String = {
    val requestInfo = s"${res.request.method} ${res.request.node.name} ${res.request.uri.path.mkString("/", "/", "")}"

    res.httpResult match {
      case HttpResult.HttpResponse(code, _, responseTime) =>
        s"$requestInfo => ${code.code} (${responseTime.toMillis}ms)"
      case HttpResult.ConnectionFailed(_) =>
        s"$requestInfo => Connection Failure"
    }
  }

  val formatResult: Sink[
    Nothing,
    Nothing,
    (Int, Fiber[Nothing, List[SingleTestResult]]),
    ArraySeq[String]
  ] =
    Sink
      .foldLeftM[
        Nothing,
        (Int, Fiber[Nothing, List[SingleTestResult]]),
        mutable.Builder[String, ArraySeq[String]]
      ](ArraySeq.newBuilder[String]) {
        case (acc, (count, fiber)) =>
          fiber.join.map { results =>
            acc.addOne(results.map(formatOne).mkString(s"Run $count\n\t", "\n\t", "\n"))
          }
      }.map(_.result)

  override def run(args: List[String]): ZIO[ZEnv, Nothing, Int] =
    getConfig(args.head)
      .flatMap {
        case RunInfo(cluster, nodeToTrigger, endpointToTrigger, endpointsToMonitor, monitorRequestCount, description) =>
          val monitorRequests = for {
            node            <- cluster.nodes
            monitorEndpoint <- endpointsToMonitor
          } yield SingleTestRequest(node, monitorEndpoint)

          val triggerRequest = SingleTestRequest(nodeToTrigger, endpointToTrigger)

          val fireTriggerRequest: RIO[
            Console with Clock with SttpClient,
            List[SingleTestResult]
          ] =
            ZIO.sleep(2.seconds) *>
              putStrLn(s"firing trigger $triggerRequest") *>
              fireRequest(List(triggerRequest))

          val fireMonitorRequests: RIO[
            Console with Clock with SttpClient,
            ArraySeq[String]
          ] =
            fireRequests(monitorRequests)
              .take(monitorRequestCount)
              .bufferUnbounded
              .run(formatResult)

          ZIO.foreach(monitorRequests)(req => putStrLn(req.toString)) *>
            (fireTriggerRequest <&> fireMonitorRequests <*> ZIO.succeed(description))
      }
      .provideSomeLayer[ZEnv](AsyncHttpClientZioBackend.layer())
      .foldM(
        err => putStrLn(err.toString + "\n" + err.getStackTrace.mkString("\n")).as(1),
        {
          case ((triggerResult, monitorResult), description) =>
            val descriptionPart   = s"Description:\n$description\n"
            val triggerResultPart = s"TriggerRequest:\n\t${formatOne(triggerResult.head)}\n"
            val monitorResultPart = s"MonitorRequests:\n${monitorResult.mkString("\n")}"

            putStrLn(s"$descriptionPart$triggerResultPart$monitorResultPart").as(0)
        }
      )
}
