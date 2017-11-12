package aesakamar.jobcoin.models

import io.circe.generic.JsonCodec

@JsonCodec final case class AddressSummary(balance: JobCoinValue,
                                           transactions: List[Transaction])
