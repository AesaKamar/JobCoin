package aesakamar.jobcoin

import java.time.Instant

import scala.concurrent.duration.FiniteDuration

import aesakamar.jobcoin.models.{BitcoinAddress, JobCoinValue, Transaction}
import aesakamar.jobcoin.clients.{AddressesClient, TransactionsClient}
import monix.eval.Task

trait Mixer {
  def addressesClient: AddressesClient
  def transactionsClient: TransactionsClient
  def generateAddress: () => BitcoinAddress

  def houseAddress: BitcoinAddress

  def tradeAddressesForNewDepositAddress(
      incomingAddresses: Set[BitcoinAddress]): Task[BitcoinAddress]

  def watchForDepositFromAddresses(
      mixerOwnedDepositAddress: BitcoinAddress,
      expectedValue: JobCoinValue,
      userProvidedWithdrawalAddresses: Set[BitcoinAddress])(
      startTime: Instant,
      timeout: FiniteDuration,
      interval: FiniteDuration): Task[Option[List[Transaction]]]

  def payoutSingle(
      scheduler: (Instant) => FiniteDuration,
      disburser: (JobCoinValue) => JobCoinValue): Task[Seq[Option[Transaction]]]

}

object Mixer {
  def random[T](s: Set[T]): T = {
    val n = util.Random.nextInt(s.size)
    s.iterator.drop(n).next
  }
}