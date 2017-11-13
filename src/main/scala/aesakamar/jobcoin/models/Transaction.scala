package aesakamar.jobcoin.models

import io.circe.{Decoder, Encoder, HCursor, Json}

final case class Transaction(toAddress: BitcoinAddress,
                             fromAddress: Option[BitcoinAddress],
                             amount: JobCoinValue)

object Transaction {

  implicit val encodeFoo: Encoder[Transaction] = new Encoder[Transaction] {
    final def apply(a: Transaction): Json =
      Json.obj(
        Seq(
          Some("toAddress" -> Json.fromString(a.toAddress.value)),
          a.fromAddress.map(f => "fromAddress" -> Json.fromString(f.value)),
          Some("amount" -> Json.fromDoubleOrString(a.amount.value))
        ).flatten: _*)
  }

  implicit val decodeFoo: Decoder[Transaction] = new Decoder[Transaction] {
    final def apply(c: HCursor): Decoder.Result[Transaction] =
      for {
        toAddress <- c.downField("toAddress").as[String]
        fromAddress <- c.downField("fromAddress").as[Option[String]]
        amount <- c.downField("amount").as[Double]
      } yield {
        new Transaction(BitcoinAddress(toAddress),
                        fromAddress.map(BitcoinAddress),
                        JobCoinValue(amount))
      }
  }
}
