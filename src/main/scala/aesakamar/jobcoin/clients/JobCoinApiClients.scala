package aesakamar.jobcoin.clients

import com.softwaremill.sttp._
import com.softwaremill.sttp.okhttp.monix.OkHttpMonixBackend
import monix.eval.Task
import pprint._

object JobCoinApiClients {
  implicit val backend = OkHttpMonixBackend()
  val clientError = Task.raiseError(
    new Exception("I am lazy and did not model good client error handling"))

  def resolve[A](
      response: Task[Response[Either[io.circe.Error, A]]]): Task[A] = {
    response
      .map(_.body.toOption.flatMap(_.toOption))
      .flatMap {
        case Some(a) => Task.now(a)
        case None    => clientError
      }
  }

}
