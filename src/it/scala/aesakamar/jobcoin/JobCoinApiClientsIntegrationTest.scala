package aesakamar.jobcoin

import aesakamar.jobcoin.models._
import aesakamar.jobcoin.clients._
import org.scalatest.{AsyncFreeSpec, Matchers}
import monix.execution.Scheduler.Implicits.global
import org.scalatest.EitherValues._
import org.scalatest.OptionValues._
import pprint._

class JobCoinApiClientsIntegrationTest extends AsyncFreeSpec with Matchers {

  "Addresses client" - {
    val client = AddressesClientJobCoinApi()
    "get" in {
      client
        .get(BitcoinAddress("Alice"))
        .onErrorRecover {
          case t =>
            fail
        }
        .runAsync
        .map { res =>
          succeed
        }
    }

  }

  "Transactions client" - {
    val client = TransactionsClientJobCoinApi()
    "get" in {
      client
        .get()
        .onErrorRecover {
          case t =>
            fail
        }
        .runAsync
        .map { res =>
          succeed
        }
    }
    "post" in {
      val myTrans = Transaction(BitcoinAddress("ItTest"), Some(BitcoinAddress("ItTest")), JobCoinValue(10))
      client
        .post(myTrans)
        .onErrorRecover {
          case t =>
            fail
        }
        .runAsync
        .map { res =>
          res.value shouldBe myTrans
        }
    }
  }

}
