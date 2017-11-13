package aesakamar.jobcoin

import aesakamar.jobcoin.models._
import io.circe.Json._
import org.scalatest.{FreeSpec, Matchers}
import org.scalatest.EitherValues._
import io.circe._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import pprint._

class JsonDecoding extends FreeSpec with Matchers {
  "Transaction" in {

    val value = Transaction(BitcoinAddress("to"), None, JobCoinValue(100))
    decode[Transaction](value.asJson.toString).right.value shouldEqual value

  }

  "Address Summary" in {
    val value = AddressSummary(
      JobCoinValue(100),
      List(Transaction(BitcoinAddress("to"), None, JobCoinValue(100)),
           Transaction(BitcoinAddress("to2"), None, JobCoinValue(120))))


    decode[AddressSummary](value.asJson.toString).right.value shouldEqual value
  }
}
