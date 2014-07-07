package com.timetrade.queueservice.server.web

import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.util.DynamicVariable
import spray.http.ContentType
import spray.http.HttpCharset
import spray.http.HttpEntity
import spray.http.HttpHeader
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.http.MediaType
import spray.http.StatusCode
import spray.httpx.unmarshalling._
import org.scalatest.concurrent.Futures
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.fixture.FunSpecLike

/** Provides convenient syntax for verifying HttpRespones.
  * Based on ScalatestRouteTest.
  */
trait HttpResponseChecking {
  this: FunSpecLike with Futures with ScalaFutures =>

  def identityFunc[T]: T => T = _identityFunc.asInstanceOf[T => T]
  private val _identityFunc: Any => Any = x => x

  private val dynHttpResponse = new DynamicVariable[HttpResponse](null)

  def check[T](body: => T): HttpResponse => T = dynHttpResponse.withValue(_)(body)

  def checkWhenReady[T](body: => T)
                       (implicit config: PatienceConfig)
  : Future[HttpResponse] => T
  = { fut =>
    whenReady(fut) { resp =>
      dynHttpResponse.withValue(resp)(body)
    }(config)
  }

  // Use type class pattern to add this composition method, '~>'
  // in the right places.
  implicit class PimpedHttpRequest(req: HttpRequest) {
    def ~>[T](f: HttpRequest => T) = f(req)
  }
  implicit class PimpedHttpResponse(resp: HttpResponse) {
    def ~>[T](f: HttpResponse => T) = f(resp)
  }
  implicit class PimpedFutureHttpResponse(resp: Future[HttpResponse]) {
    def ~>[T](f: Future[HttpResponse] => T) = f(resp)
  }

  private def assertInCheck() {
    if (dynHttpResponse.value == null)
      sys.error("This value is only available inside of a `check` construct!")
  }

  def response: HttpResponse = { assertInCheck(); dynHttpResponse.value }
  def entityAs[T :Unmarshaller] = response.entity.as[T].fold(error => fail(error.toString), identityFunc)
  def body: HttpEntity.NonEmpty = response.entity.toOption.getOrElse(fail("Response has no entity"))
  def contentType: ContentType = body.contentType
  def mediaType: MediaType = contentType.mediaType
  def charset: HttpCharset = contentType.charset
  def definedCharset: Option[HttpCharset] = contentType.definedCharset
  def headers: List[HttpHeader] = response.headers
  def header[T <: HttpHeader : ClassTag]: Option[T] = response.header[T]
  def header(name: String): Option[HttpHeader] = response.headers.find(_.name == name)
  def status: StatusCode = response.status
}
