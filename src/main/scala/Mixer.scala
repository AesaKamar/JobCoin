import java.time.Instant
import java.util.UUID

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import cats._
import cats.data._
import cats.implicits._
import cats.effect._
import hammock.Status
import org.scalacheck.rng.Seed

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
  def get(addr: BitcoinAddress): IO[(JobCoinValue, List[Transaction])]
}

trait TransactionsClient {
  def get(): IO[List[Transaction]]
  def post(transaction: Transaction): IO[Option[Transaction]]
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
      incomingAddresses: Set[BitcoinAddress]): IO[BitcoinAddress]
}

/**
  * You provide  a list of new, unused addresses that you own to the mixer;
  * The mixer provides you with a new deposit address that it owns;
  * You transfer your bitcoins to that address;
  * The mixer will detect your transfer by watching or polling the P2P Bitcoin network;
  * The mixer will transfer your bitcoin from the deposit address into a big “house account” along with all the other bitcoin currently being mixed; and
  * Then, over some time the mixer will use the house account to dole out your bitcoin in smaller increments to the withdrawal addresses that you provided, possibly after deducting a fee.
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

  def doMix(incomingAddresses: Set[BitcoinAddress]): IO[Option[Unit]] = {

    val res = for {
      depositAddress <- tradeAddressesForNewDeposit(incomingAddresses)
      depositStatus <- watch(depositAddress)(Instant.now(),
                                             30 seconds,
                                             3 seconds)
      deposit <- addressesClient.get(depositAddress)
      transferToHouseAccount <- transactionsClient.post(
        Transaction(Instant.now(),
                    houseAddress,
                    Some(depositAddress),
                    deposit._1))

    } yield {
      for {
        _ <- depositStatus
        _ <- transferToHouseAccount
        _ <- unconfirmedDeposits.remove(depositAddress)
      } yield {
        mixerOwnedAddresses.add(depositAddress)
        remainingPayouts.update(incomingAddresses, deposit._1)
      }
    }
    res
  }

  def tradeAddressesForNewDeposit(
      incomingAddresses: Set[BitcoinAddress]): IO[BitcoinAddress] =
    IO.pure {
      val accountToWatch = generateAddress()
      unconfirmedDeposits.update(accountToWatch, incomingAddresses)
      accountToWatch
    }

  def watch(addr: BitcoinAddress)(
      startTime: Instant,
      timeout: FiniteDuration,
      interval: FiniteDuration): IO[Option[Transaction]] = {
    println(timeout.toMillis)
    if (Instant
          .now()
          .toEpochMilli >= (startTime.toEpochMilli + timeout.toMillis))
      IO.pure(None)
    else
      transactionsClient.get().flatMap { transactions =>
        transactions.find(_.toAddress == addr) match {
          case None =>
            Thread.sleep(interval.toMillis)
            watch(addr)(Instant.now(), timeout - interval, interval)
          case trans @ Some(_) => IO.pure(trans)
        }
      }
  }

  def payout(): IO[Unit] = ???

}
object Mixer
