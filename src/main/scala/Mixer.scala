import java.time.Instant
import java.util.UUID

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import cats._
import cats.data._
import cats.implicits._
import monix.eval._

import pprint._

//======================
// Domain Objects
//======================

final case class JobCoinValue(value: Double) extends AnyVal

final case class BitcoinAddress(value: String) extends AnyVal

final case class Transaction(timeStamp: Instant,
                             toAddress: BitcoinAddress,
                             fromAddress: Option[BitcoinAddress],
                             amount: JobCoinValue)

sealed trait Transfer
case object SuccessfulTransfer extends Transfer
case object FailedTransfer extends Transfer
//======================
// Clients
//======================

trait AddressesClient {
  def get(addr: BitcoinAddress): Task[(JobCoinValue, List[Transaction])]
}

trait TransactionsClient {
  def get(): Task[List[Transaction]]
  def post(transaction: Transaction): Task[Option[Transaction]]
}

//======================
// Domain Objects
//======================

trait Mixer {
  def addressesClient: AddressesClient
  def transactionsClient: TransactionsClient
  def generateAddress: () => BitcoinAddress

  def houseAddress: BitcoinAddress

  def tradeAddressesForNewDeposit(
      incomingAddresses: Set[BitcoinAddress]): Task[BitcoinAddress]

  def watchForDepositFromAddresses(watchedAddress: BitcoinAddress,
                                   expectedValue: JobCoinValue,
                                   incomingAddresses: Set[BitcoinAddress])(
      startTime: Instant,
      timeout: FiniteDuration,
      interval: FiniteDuration): Task[Option[List[Transaction]]]
}

/**
  * 1) You provide  a list of new, unused addresses that you own to the mixer;
  * 2) The mixer provides you with a new deposit address that it owns;
  * 3) You transfer your bitcoins to that address;
  * 4) The mixer will detect your transfer by watching or polling the P2P Bitcoin network;
  * 5) The mixer will transfer your bitcoin from the deposit address into a big “house account”
  *   along with all the other bitcoin currently being mixed
  * 6) Then, over some time the mixer will use the house account to dole out your bitcoin
  *   in smaller increments to the withdrawal addresses that you provided,
  *   possibly after deducting a fee.
  */
case class MixerImpl(
    remainingPayouts: mutable.Map[Set[BitcoinAddress], JobCoinValue],
    unconfirmedDeposits: mutable.Map[BitcoinAddress, Set[BitcoinAddress]],
    mixerOwnedAddresses: mutable.Set[BitcoinAddress])(
    val generateAddress: () => BitcoinAddress)(
    implicit val addressesClient: AddressesClient,
    val transactionsClient: TransactionsClient)
    extends Mixer {

  lazy val houseAddress: BitcoinAddress = generateAddress()

  /**
    * 1) You provide  a list of new, unused addresses that you own to the mixer;
    * 2) The mixer provides you with a new deposit address that it owns;
    */
  def tradeAddressesForNewDeposit(
      incomingAddresses: Set[BitcoinAddress]): Task[BitcoinAddress] =
    Task {
      val accountToWatch = generateAddress()
      unconfirmedDeposits.update(accountToWatch, incomingAddresses)
      accountToWatch
    }

  /**
    * 4) The mixer will detect your transfer by watching or polling the P2P Bitcoin network;
    * 5) The mixer will transfer your bitcoin from the deposit address into a big “house account”
    *   along with all the other bitcoin currently being mixed
    */
  def watchForDepositFromAddresses(watchedAddress: BitcoinAddress,
                                   expectedValue: JobCoinValue,
                                   incomingAddresses: Set[BitcoinAddress])(
      startTime: Instant,
      timeout: FiniteDuration,
      interval: FiniteDuration): Task[Option[List[Transaction]]] = {
    if (Instant
          .now()
          .toEpochMilli >= (startTime.toEpochMilli + timeout.toMillis))
      Task(None)
    else
      transactionsClient.get().flatMap { transactions =>
        //Assume we can filter for the list of transactions by time after the watch started
        val addressesWithTransactions = transactions
          .filter(_.toAddress == watchedAddress)
          .groupBy(_.fromAddress)
          .filter {
            case (Some(k), _) => incomingAddresses.contains(k)
            case _            => false
          }

        val monitoredTransactions =
          addressesWithTransactions.values.flatten.toList

        monitoredTransactions match {
          case Nil =>
            Thread.sleep(interval.toMillis)
            watchForDepositFromAddresses(
              watchedAddress,
              expectedValue,
              incomingAddresses)(Instant.now(), timeout - interval, interval)
          case trans if trans.map(_.amount.value).sum < expectedValue.value =>
            Thread.sleep(interval.toMillis)
            watchForDepositFromAddresses(
              watchedAddress,
              expectedValue,
              incomingAddresses)(Instant.now(), timeout - interval, interval)
          case trans => Task(Some(trans))
        }
      }
  }

  def payout(): Task[Unit] = ???

}
object Mixer
