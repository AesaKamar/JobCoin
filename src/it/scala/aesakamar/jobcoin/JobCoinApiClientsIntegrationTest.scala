package aesakamar.jobcoin

import aesakamar.jobcoin.models.BitcoinAddress
import aesakamar.jobcoin.clients.AddressesClientJobCoinApi
import org.scalatest.{AsyncFreeSpec, Matchers}


class JobCoinApiClientsIntegrationTest extends AsyncFreeSpec with Matchers {

  "Addresses client" - {
    val client = AddressesClientJobCoinApi()
    "get" in {
      client.get(BitcoinAddress("Alice")).runAsync.map{ res =>
        res shouldBe res

      }
    }

  }

  "Transactions client" - {}

}
