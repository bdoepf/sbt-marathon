package sbtmarathon

import java.net.{URL, URLEncoder, InetSocketAddress}
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import com.twitter.finagle.{Http, Name, Address}
import com.twitter.finagle.http.{RequestBuilder, Request, Response}
import com.twitter.io.Buf
import com.twitter.util.Base64StringEncoder
import org.json4sbt._
import org.json4sbt.jackson.JsonMethods._
import org.scalactic.{Or, Good, Bad}

class MarathonService(url: URL) {

  import MarathonService._

  val port = if (url.getPort < 0) url.getDefaultPort else url.getPort

  val apiUrl = UrlUtil.copy(url, port = port, path = RestApiPath)

  def start(jsonString: String): Result Or Throwable = {
    val request = RequestBuilder()
      .url(apiUrl)
      .setHeader("Content-type", JsonContentType)
      .buildPost(jsonString)
    executeRequest(request)
  }

  def destroy(applicationId: String): Result Or Throwable = {
    val url = instanceServiceUrl(applicationId)
    val request = RequestBuilder()
      .url(url)
      .buildDelete()
    executeRequest(request, url = url)
  }

  def update(applicationId: String, jsonString: String): Result Or Throwable = {
    val url = instanceServiceUrl(applicationId)
    val request = RequestBuilder()
      .url(url)
      .setHeader("Content-type", JsonContentType)
      .buildPut(jsonString)
    executeRequest(request, url)
  }

  def restart(applicationId: String): Result Or Throwable = {
    val instanceUrl = instanceServiceUrl(applicationId)
    val url = UrlUtil.copy(instanceUrl, path = instanceUrl.getPath + "/restart")
    val request = RequestBuilder()
      .url(url)
      .setHeader("Content-type", JsonContentType)
      .buildPost(Buf.Empty)
    executeRequest(request, url)
  }

  def scale(applicationId: String, numInstances: Int): Result Or Throwable = {
    val url = instanceServiceUrl(applicationId)
    val jsonString = s"""{"instances":$numInstances}"""
    val request = RequestBuilder()
      .url(url)
      .setHeader("Content-type", JsonContentType)
      .buildPut(jsonString)
    executeRequest(request, url)
  }

  def executeRequest(request: Request, url: URL = this.url): Result Or Throwable = {
    val host = url.getHost
    val port = if (url.getPort < 0) url.getDefaultPort else url.getPort
    val addr = Address(new InetSocketAddress(host, port))
    val client = if (url.getProtocol == "https") Http.client.withTlsWithoutValidation else Http.client
    Option(url.getUserInfo).foreach { credentials =>
      val encodedCredentials = Base64StringEncoder.encode(credentials.getBytes("UTF-8"))
      request.authorization = s"Basic $encodedCredentials"
    }
    val service = client.newService(Name.bound(addr), "")
    val response = service(request).ensure { service.close() }
    val promise = Promise[Response]
    response.onSuccess(promise.success _)
    response.onFailure(promise.failure _)
    val future = promise.future.map { response =>
      val responseString = response.contentString
      val result = response.statusCode match {
        case n if n >= 200 && n < 400 => Success(responseString)
        case n if n >= 400 && n < 500 => UserError(responseString)
        case n if n >= 500            => SystemError(responseString)
      }
      Good(result)
    }
    try {
      Await.result(future, Duration.Inf)
    } catch {
      case e: Exception => Bad(e)
    }
  }

  def instanceServiceUrl(applicationId: String): URL = {
    UrlUtil.copy(url, port = port, path = RestApiPath + s"/$applicationId")
  }

  def instanceGuiUrl(applicationId: String): URL = {
    val fragment = "/apps/" + URLEncoder.encode(s"/$applicationId", "UTF-8")
    UrlUtil.copy(url, port = port, path = GuiPath, fragment = fragment)
  }
}

object MarathonService {

  sealed trait Result {
    implicit val formats = DefaultFormats
    def responseString: String
    lazy val responseJson: JValue = parse(responseString)
    lazy val message: Option[String] = (responseJson \ "message").extractOpt[String]
  }
  case class Success(responseString: String) extends Result
  case class UserError(responseString: String) extends Result
  case class SystemError(responseString: String) extends Result

  val RestApiPath = "/v2/apps"

  val GuiPath = "/ui/"

  val JsonContentType = "application/json"

  implicit def jsonStringToBuf(jsonString: String): Buf = {
    val jsonBytes = jsonString.getBytes("UTF-8")
    Buf.ByteArray(jsonBytes: _*)
  }
}
