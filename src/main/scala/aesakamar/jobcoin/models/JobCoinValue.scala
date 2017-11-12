package aesakamar.jobcoin.models

import io.circe.generic.JsonCodec

@JsonCodec final case class JobCoinValue(value: Double)
