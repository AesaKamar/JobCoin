package aesakamar.jobcoin.models

import io.circe.{Decoder, Encoder, HCursor, Json}

final case class AddressSummary(balance: JobCoinValue,
                                transactions: List[Transaction])

object AddressSummary {

  implicit val encodeFoo: Encoder[AddressSummary] =
    new Encoder[AddressSummary] {
      final def apply(a: AddressSummary): Json = Json.obj(
        "balance" -> Json.fromDoubleOrString(a.balance.value),
        "transactions" -> Json.arr(
          a.transactions.map(Encoder[Transaction].apply): _*)
      )
    }

  implicit val decodeFoo: Decoder[AddressSummary] =
    new Decoder[AddressSummary] {
      final def apply(c: HCursor): Decoder.Result[AddressSummary] =
        for {
          balance <- c.downField("balance").as[Double]
          transactions <- c.downField("transactions").as[List[Transaction]]
        } yield {
          new AddressSummary(JobCoinValue(balance), transactions)
        }
    }
}
