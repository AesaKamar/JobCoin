package aesakamar.jobcoin

import java.time.Instant

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

import aesakamar.jobcoin.models.{BitcoinAddress, JobCoinValue, Transaction}
import aesakamar.jobcoin.clients.{AddressesClient, TransactionsClient}
import monix.eval.Task

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
                      var remainingPayouts: mutable.Map[Set[BitcoinAddress], JobCoinValue],
                      var unconfirmedDeposits: mutable.Map[BitcoinAddress, Set[BitcoinAddress]],
                      var mixerOwnedAddresses: mutable.Set[BitcoinAddress])(
    val generateAddress: () => BitcoinAddress)(
    implicit val addressesClient: AddressesClient,
    val transactionsClient: TransactionsClient)
    extends Mixer {

  lazy val houseAddress: BitcoinAddress = BitcoinAddress("houseAddress")

  /**
    * 1) You provide  a list of new, unused addresses that you own to the mixer;
    * 2) The mixer provides you with a new deposit address that it owns;
    */
  def tradeAddressesForNewDepositAddress(
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
  def watchForDepositFromAddresses(
      mixerOwnedDepositAddress: BitcoinAddress,
      expectedValue: JobCoinValue,
      userProvidedWithdrawalAddresses: Set[BitcoinAddress])(
      startTime: Instant,
      timeout: FiniteDuration,
      interval: FiniteDuration): Task[Option[List[Transaction]]] = {
    if (Instant
          .now()
          .toEpochMilli >= (startTime.toEpochMilli + timeout.toMillis))
      Task(None)
    else
      transactionsClient.get().flatMap { allTransactions =>
        val addressesWithTransactions = allTransactions
          .filter(_.toAddress == mixerOwnedDepositAddress)
          .groupBy(_.fromAddress)


        val monitoredTransactions =
          addressesWithTransactions.values.flatten.toList

        monitoredTransactions match {
          case Nil =>
            Thread.sleep(interval.toMillis)
            watchForDepositFromAddresses(mixerOwnedDepositAddress,
                                         expectedValue,
                                         userProvidedWithdrawalAddresses)(
              Instant.now(),
              timeout - interval,
              interval)

          case transactions
              if transactions.map(_.amount.value).sum < expectedValue.value =>
            Thread.sleep(interval.toMillis)
            watchForDepositFromAddresses(mixerOwnedDepositAddress,
                                         expectedValue,
                                         userProvidedWithdrawalAddresses)(
              Instant.now(),
              timeout - interval,
              interval)

          case transactionsSummingToExpectedValue => {
            unconfirmedDeposits.remove(mixerOwnedDepositAddress)
            mixerOwnedAddresses.add(mixerOwnedDepositAddress)
            remainingPayouts.update(
              userProvidedWithdrawalAddresses,
              models.JobCoinValue(
                transactionsSummingToExpectedValue.map(_.amount.value).sum))
            Task(Some(transactionsSummingToExpectedValue))
          }
        }
      }
  }

  /**
    * 6) Then, over some time the mixer will use the house account to dole out your bitcoin
    *   in smaller increments to the withdrawal addresses that you provided,
    *   possibly after deducting a fee.
    */
  final def payoutSingle(scheduler: (Instant) => FiniteDuration,
                         disburser: (JobCoinValue) => JobCoinValue)
    : Task[Seq[Option[Transaction]]] = {
    Task.defer(
      Task.gather(remainingPayouts.map {
        case (addresses, remainingPayout) if remainingPayout.value <= 0 =>
          Task(None)
        case (addresses, remainingPayout) =>
          val amountToDisburse = disburser(remainingPayout)
          val addressToDisburseTo = Mixer.random(addresses)

          val transaction = Transaction(addressToDisburseTo,
                                        Some(houseAddress),
                                        amountToDisburse)

          for {
            transactionResult <- transactionsClient.post(transaction)
          } yield {
            transactionResult match {
              case s @ Some(tr) =>
                remainingPayouts.update(
                  addresses,
                  JobCoinValue(remainingPayout.value - tr.amount.value))
                s
              case None =>
                None
            }
          }
      }.toSeq)
    )
  }

}
