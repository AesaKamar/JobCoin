package aesakamar.jobcoin.clients

import aesakamar.jobcoin.models._
import com.softwaremill.sttp._
import com.softwaremill.sttp.circe._
import monix.eval.Task

case class AddressesClientJobCoinApi() extends AddressesClient {
  import aesakamar.jobcoin.clients.JobCoinApiClients._
  override def get(addr: BitcoinAddress): Task[AddressSummary] = {

    val url = s"http://jobcoin.gemini.com/shank/api/addresses/${addr.value}"

    val response = sttp
      .get(Uri(url))
      .response(asJson[AddressSummary])
      .send()

    resolve(response)
  }
}
