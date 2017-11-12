package aesakamar.jobcoin.models

import io.circe.generic.JsonCodec

@JsonCodec final case class Transaction(toAddress: BitcoinAddress,
                                        fromAddress: Option[BitcoinAddress],
                                        amount: JobCoinValue)
