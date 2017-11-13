package aesakamar.jobcoin.clients

import aesakamar.jobcoin.models.Transaction
import com.softwaremill.sttp._
import com.softwaremill.sttp.circe._
import monix.eval.Task

case class TransactionsClientJobCoinApi() extends TransactionsClient {
  import JobCoinApiClients._

  val url = Uri("jobcoin.gemini.com", 80, List("shank","api","transactions"))

  override def get(): Task[List[Transaction]] = {

    val response = sttp
      .get(url)
      .response(asJson[List[Transaction]])
      .send()

    resolve(response)

  }

  override def post(transaction: Transaction): Task[Option[Transaction]] = {

    val response = sttp
      .post(url)
      .body(transaction)
      .response(asString)
      .send()

    response.flatMap{x =>
      if(x.isSuccess){
        x.body match {
          case Right(_) => Task.now(Some(transaction))
          case Left(_) => Task.now(None)
        }
      }
      else clientError
    }

  }
}
