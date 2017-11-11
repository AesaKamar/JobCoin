import java.time.Instant

import org.scalatest._
import org.scalatest.OptionValues._
import org.scalatest.Inspectors._
import scala.concurrent.duration._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.collection.parallel.FutureThreadPoolTasks
import scala.concurrent.Future

import Mixer._
import cats._
import cats.data._
import cats.implicits._
import monix.eval._
import monix.cats._
import monix.execution.Scheduler.Implicits.global
import pprint._

object FakeData {

  def transactions: ListBuffer[Transaction] = ListBuffer.empty
}

case class FakeAddressesClient private (
    var transactions: ListBuffer[Transaction])
    extends AddressesClient {

  def get(addr: BitcoinAddress): Task[(JobCoinValue, List[Transaction])] =
    Task {

      val filteredTransactions = transactions
        .filter(_.toAddress == addr)

      val balance = filteredTransactions.map(_.amount.value).sum

      (JobCoinValue(balance), filteredTransactions.toList)
    }
}
case class FakeTransactionsClient private (
    var transactions: ListBuffer[Transaction])(
    implicit addressesClient: AddressesClient)
    extends TransactionsClient {

  def get(): Task[List[Transaction]] =
    Task(transactions.toList)

  def post(transaction: Transaction): Task[Option[Transaction]] = {

    val maybeFromInformation = transaction.fromAddress
      .map(addressesClient.get)

    val fromBalance = maybeFromInformation match {
      case Some(t) => t.map { case (balance, history) => balance }
      case None    => Task(JobCoinValue(Double.MaxValue))
    }

    fromBalance.map { balance =>
      if (balance.value >= transaction.amount.value) {
        transactions.append(transaction)
        Some(transaction)
      } else None

    }

  }

}

object MixerTest {

  type Dependencies[A] =
    (() => BitcoinAddress, AddressesClient, TransactionsClient, MixerImpl) => A
  type AssertionOperation = (Any => Future[Assertion])

  /**
    * Provides a closure with the client contexts freshly instantuated
    *   and in a new scope
    * @param op A block which returns an [[A]]
    * @tparam A The return type of the operation
    * @return A closure with bound context to the variables
    */
  def runWithDependencies[A](
      payouts: mutable.Map[Set[BitcoinAddress], JobCoinValue] =
        mutable.Map.empty)(op: Dependencies[A]) = {

    var transactionsStore = FakeData.transactions
    val addressGenerator = () => BitcoinAddress("depositAddress")
    implicit val addressesClient: AddressesClient = FakeAddressesClient(
      transactionsStore)
    implicit val transactionsClient: TransactionsClient =
      FakeTransactionsClient(transactionsStore)

    val mixer =
      MixerImpl(payouts, mutable.Map.empty, mutable.Set.empty)(addressGenerator)

    op(addressGenerator, addressesClient, transactionsClient, mixer)
  }

  //======================
  // Reusable values
  //======================
  val mySetOfAddresses =
    Set(BitcoinAddress("a"), BitcoinAddress("b"), BitcoinAddress("c"))
  val myPromisedValue = JobCoinValue(10)

  val myFundingAccount = BitcoinAddress("bank")

}

class MixerTest extends AsyncFreeSpec with Matchers {

  import MixerTest._

  "transaction client should update" in runWithDependencies() {
    (addressGenerator, addressesClient, transactionsClient, mixer) =>
      //======================
      // Arrange
      //======================
      val transaction =
        Transaction(Instant.now,
                    BitcoinAddress("client"),
                    None,
                    JobCoinValue(5))

      val putGetTask = for {
        put <- transactionsClient.post(transaction)
        get <- transactionsClient.get()
      } yield {
        (put, get)
      }

      //======================
      // Act and Assert
      //======================
      putGetTask.runAsync.map {
        case (put, get) =>
          put.value shouldEqual transaction
          get should contain(transaction)
      }

  }

  "For our [[Mixer Impl]]" - {

    "Trading" - {

      "should generate an appropriate depoosit address" in runWithDependencies() {
        (addressGenerator, addressesClient, transactionsClient, mixer) =>
          //======================
          // Arrange
          //======================
          val res = mixer.tradeAddressesForNewDepositAddress(mySetOfAddresses)

          //======================
          // Act and Assert
          //======================
          res.runAsync.map(_ shouldBe BitcoinAddress("depositAddress"))
      }
    }

    "Watching" - {

      "with a completed deposit, watch should pass" in runWithDependencies() {
        (addressGenerator, addressesClient, transactionsClient, mixer) =>
          //======================
          // Arrange
          //======================
          val getDepositAddressTask =
            mixer.tradeAddressesForNewDepositAddress(mySetOfAddresses)

          val watchTask = for {
            depositAddress <- getDepositAddressTask
            watched <- mixer.watchForDepositFromAddresses(
              depositAddress,
              myPromisedValue,
              mySetOfAddresses)(Instant.now(), 2 seconds, .1 seconds)
          } yield watched

          val completeDepositTask = for {
            depositAddress <- getDepositAddressTask
            moneyFromThinAir <- transactionsClient.post(
              Transaction(Instant.now,
                          myFundingAccount,
                          None,
                          JobCoinValue(100)))
            myAccountBalances <- addressesClient.get(myFundingAccount)
            fulfillDeposit <- transactionsClient.post(
              Transaction(Instant.now,
                          depositAddress,
                          Some(myFundingAccount),
                          JobCoinValue(5)))
            fulfillDeposit2 <- transactionsClient.post(
              Transaction(Instant.now,
                          depositAddress,
                          Some(myFundingAccount),
                          JobCoinValue(5)))
          } yield {
            fulfillDeposit should not be 'empty
            fulfillDeposit2 should not be 'empty
          }

          //======================
          // Act and Assert
          //======================
          val watchComputation = watchTask.runAsync
          completeDepositTask.runAsync

          for {
            reportedListOfTransactions <- watchComputation
            actualListOfTransactions <- transactionsClient.get().runAsync
          } yield {
            (reportedListOfTransactions.value.toSet subsetOf actualListOfTransactions.toSet) shouldBe true
          }
      }
      "with a partially completed deposit, watch should fail" in runWithDependencies() {
        (addressGenerator, addressesClient, transactionsClient, mixer) =>
          //======================
          // Arrange
          //======================
          val getDepositAddressTask =
            mixer.tradeAddressesForNewDepositAddress(mySetOfAddresses)

          val watchTask = for {
            depositAddress <- getDepositAddressTask
            watched <- mixer.watchForDepositFromAddresses(
              depositAddress,
              myPromisedValue,
              mySetOfAddresses)(Instant.now(), .5 seconds, .1 seconds)
          } yield watched

          val completeDepositTask = for {
            moneyFromThinAir <- transactionsClient.post(
              Transaction(Instant.now,
                          myFundingAccount,
                          None,
                          JobCoinValue(100)))
            depositAddress <- getDepositAddressTask
            myAccountBalances <- addressesClient.get(myFundingAccount)
            fulfillDeposit <- transactionsClient.post(
              Transaction(Instant.now,
                          depositAddress,
                          Some(myFundingAccount),
                          JobCoinValue(5)))
          } yield fulfillDeposit

          //======================
          // Act and Assert
          //======================
          val watchComputation = watchTask.runAsync
          completeDepositTask.runAsync

          watchComputation.map { x =>
            x shouldBe 'empty
          }
      }
      "with no completed deposit, watch should fail" in runWithDependencies() {
        (addressGenerator, addressesClient, transactionsClient, mixer) =>
          //======================
          // Arrange
          //======================
          val getDepositAddressTask =
            mixer.tradeAddressesForNewDepositAddress(mySetOfAddresses)

          val watchTask = for {
            depositAddress <- getDepositAddressTask
            watched <- mixer.watchForDepositFromAddresses(
              depositAddress,
              myPromisedValue,
              mySetOfAddresses)(Instant.now(), .5 seconds, .1 seconds)
          } yield watched

          //======================
          // Act and Assert
          //======================
          val watchComputation = watchTask.runAsync

          watchComputation.map { x =>
            x shouldBe 'empty
          }
      }

    }
    "Mixing" - {
      "payout should payout" in runWithDependencies(
        payouts = mutable.Map(mySetOfAddresses -> JobCoinValue(10.0))) {
        (addressGenerator, addressesClient, transactionsClient, mixer) =>
          //======================
          // Arrange
          //======================
          val computation = for {
            houseMoneyFromThinAir <- transactionsClient.post(
              Transaction(Instant.now,
                          mixer.houseAddress,
                          None,
                          JobCoinValue(Double.MaxValue)))
            payout <- Task.defer(mixer.payoutSingle(i => 1 millisecond, x => x))
            allTransactions <- transactionsClient.get()
          } yield {
            houseMoneyFromThinAir should not be 'empty
            allTransactions.filter(t => {
              t.fromAddress.contains(mixer.houseAddress) && mySetOfAddresses
                .contains(t.toAddress)
            })
          }

          //======================
          // Act and Assert
          //======================
          computation.runAsync
            .map { transactions =>
              forAtLeast(1, transactions) { transaction =>
                mySetOfAddresses should contain(transaction.toAddress)
                transaction.amount shouldEqual JobCoinValue(10.0)
              }
            }
            .map { _ =>
              mixer.remainingPayouts
                .get(mySetOfAddresses)
                .value shouldBe JobCoinValue(0.0)
            }
      }
      """if a watch does not resolve (where there are no completed deposits), the mixer should not payout to any of the user provided addresses
      """.stripMargin in runWithDependencies() {
        (addressGenerator, addressesClient, transactionsClient, mixer) =>
          //======================
          // Arrange
          //======================
          val getDepositAddressTask =
            mixer.tradeAddressesForNewDepositAddress(mySetOfAddresses)

          val watchTask = for {
            depositAddress <- getDepositAddressTask
            watched <- mixer.watchForDepositFromAddresses(
              depositAddress,
              myPromisedValue,
              mySetOfAddresses)(Instant.now(), .5 seconds, .1 seconds)
          } yield watched

          val payoutTask = Task.defer(
            mixer
              .payoutSingle(scheduler = (i: Instant) => 10 milliseconds,
                            disburser = x => x)
              .timeout(2 seconds))

          val balancesTask = for {
            valuesAndHistories <- Task.sequence(
              mySetOfAddresses.map(addressesClient.get))

          } yield valuesAndHistories

          watchTask.runAsync
          val cancellablePayout = payoutTask.runAsync
          Task(cancellablePayout.cancel()).timeout(0.5 seconds).runAsync

          //======================
          // Act and Assert
          //======================
          balancesTask.runAsync.map { x =>
            forAll(x) {
              case (JobCoinValue(v), history) =>
                v should be <= 0.0
                history shouldBe 'empty
            }
          }
      }

      """if a watch resolves (where there are completed deposits), the mixer should make one payout to exactly one user owned receiver account
      """.stripMargin in runWithDependencies() {
        (addressGenerator, addressesClient, transactionsClient, mixer) =>
          //======================
          // Arrange
          //======================
          val getDepositAddressTask =
            mixer.tradeAddressesForNewDepositAddress(mySetOfAddresses)

          val watchTask = for {
            depositAddress <- getDepositAddressTask
            watched <- mixer.watchForDepositFromAddresses(
              depositAddress,
              myPromisedValue,
              mySetOfAddresses)(Instant.now(), .5 seconds, .1 seconds)
          } yield {
            watched should not be 'empty
            watched.value should not be 'empty
            watched
          }

          val completeDepositTask = for {
            depositAddress <- getDepositAddressTask
            houseMoneyFromThinAir <- transactionsClient.post(
              Transaction(Instant.now,
                          mixer.houseAddress,
                          None,
                          JobCoinValue(Double.MaxValue)))
            myMoneyFromThinAir <- transactionsClient.post(
              Transaction(Instant.now,
                          myFundingAccount,
                          None,
                          JobCoinValue(100)))
            myAccountBalances <- addressesClient.get(myFundingAccount)
            fulfillDeposit <- transactionsClient.post(
              Transaction(Instant.now,
                          depositAddress,
                          Some(myFundingAccount),
                          JobCoinValue(5)))
            fulfillDeposit2 <- transactionsClient.post(
              Transaction(Instant.now,
                          depositAddress,
                          Some(myFundingAccount),
                          JobCoinValue(5)))
          } yield {
            (fulfillDeposit, fulfillDeposit2)
          }

          val payoutTask = Task.defer(
            mixer
              .payoutSingle(scheduler = (i: Instant) => 1 millisecond,
                            disburser = x => x))

          val getAllTransactionsTask = for {
            allTransactions <- transactionsClient.get()
          } yield {

            val transactionsFromHouseToUserProvidedAccounts =
              allTransactions.filter(t => {
                t.fromAddress.contains(mixer.houseAddress) && mySetOfAddresses
                  .contains(t.toAddress)
              })
            transactionsFromHouseToUserProvidedAccounts

          }

          //======================
          // Act and Assert
          //======================

          val res = for {
            c <- completeDepositTask.runAsync
            w <- watchTask.runAsync
            p <- payoutTask.runAsync
            t <- getAllTransactionsTask.runAsync
          } yield {
            p.map(_.value).toSet shouldEqual t.toSet
          }
          res
      }
    }
  }
}
