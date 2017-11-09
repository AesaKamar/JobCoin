import java.time.Instant

import org.scalatest._
import org.scalatest.OptionValues._
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

  def transactions: ListBuffer[Transaction] = ListBuffer(
    Transaction(Instant.MIN, BitcoinAddress("from"), None, JobCoinValue(5)),
    Transaction(Instant.MIN,
                BitcoinAddress("to"),
                Some(BitcoinAddress("from")),
                JobCoinValue(1)),
    Transaction(Instant.MIN,
                BitcoinAddress("to"),
                Some(BitcoinAddress("from")),
                JobCoinValue(1)),
    Transaction(Instant.MIN,
                BitcoinAddress("to"),
                Some(BitcoinAddress("from")),
                JobCoinValue(1)),
    Transaction(Instant.MIN,
                BitcoinAddress("to"),
                Some(BitcoinAddress("from")),
                JobCoinValue(1)),
    Transaction(Instant.now, BitcoinAddress("a"), None, JobCoinValue(100))
  )
}

case class FakeAddressesClient() extends AddressesClient {

  override def get(
      addr: BitcoinAddress): Task[(JobCoinValue, List[Transaction])] = Task {

    val transactions = FakeData.transactions
      .filter(_.toAddress == addr)

    val balance = transactions.map(_.amount.value).sum

    (JobCoinValue(balance), transactions.toList)
  }
}
case class FakeTransactionsClient private (
    var transactions: ListBuffer[Transaction])(
    implicit addressesClient: AddressesClient)
    extends TransactionsClient {

  override def get(): Task[List[Transaction]] =
    Task(transactions.toList)

  override def post(transaction: Transaction): Task[Option[Transaction]] = {

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

class MixerTest extends AsyncFreeSpec with Matchers {

  type Dependencies[A] =
    (() => BitcoinAddress, AddressesClient, TransactionsClient, MixerImpl) => A
  type AssertionOperation = (Any => Future[Assertion])

  /**
    *
    * @param op A block which returns an [[A]]
    * @tparam A The return type of the operation
    * @return A closure with bound context to the variables
    */
  def runWithDependencies[A](op: Dependencies[A]) = {
    val addressGenerator = () => BitcoinAddress("fake")
    implicit val addressesClient: AddressesClient = FakeAddressesClient()
    implicit val transactionsClient: TransactionsClient =
      FakeTransactionsClient(FakeData.transactions)

    val mixer = MixerImpl(mutable.Map.empty,
                          mutable.Map.empty,
                          mutable.Set.empty)(addressGenerator)

    op(addressGenerator, addressesClient, transactionsClient, mixer)
  }

  "transaction client should update" in runWithDependencies {
    (addressGenerator, addressesClient, transactionsClient, mixer) =>
      val transaction =
        Transaction(Instant.now,
                    BitcoinAddress("client"),
                    None,
                    JobCoinValue(5))

      val putGetTask = for {
        put <- transactionsClient.post(transaction)
        get <- transactionsClient.get()
      } yield {
        put.value shouldEqual transaction
        get should contain(transaction)
      }

      putGetTask.runAsync
  }

  "For our [[Mixer Impl]]" - {

    val mySetOfAddresses =
      Set(BitcoinAddress("a"), BitcoinAddress("b"), BitcoinAddress("c"))
    val myPromisedValue = JobCoinValue(10)

    "Trading" - {
      val mySetOfAddresses =
        Set(BitcoinAddress("a"), BitcoinAddress("b"), BitcoinAddress("c"))

      "should generate an appropriate depoosit address" in runWithDependencies {
        (addressGenerator, addressesClient, transactionsClient, mixer) =>

          val res = mixer.tradeAddressesForNewDeposit(mySetOfAddresses)

          res.runAsync.map(_ shouldBe BitcoinAddress("fake"))
      }
    }

    "Watching" - {

      "with a completed deposit, watch should pass" in runWithDependencies {
        (addressGenerator, addressesClient, transactionsClient, mixer) =>
          val getDepositAddressTask =
            mixer.tradeAddressesForNewDeposit(mySetOfAddresses)

          val watchTask = for {
            depositAddress <- getDepositAddressTask
            watched <- mixer.watchForDepositFromAddresses(
              depositAddress,
              myPromisedValue,
              mySetOfAddresses)(Instant.now(), 2 seconds, .1 seconds)
          } yield watched

          val completeDepositTask = for {
            depositAddress <- getDepositAddressTask
            myAccountBalances <- addressesClient.get(mySetOfAddresses.head)
            fulfillDeposit <- transactionsClient.post(
              Transaction(Instant.now,
                          depositAddress,
                          Some(mySetOfAddresses.head),
                          JobCoinValue(5)))
            fulfillDeposit2 <- transactionsClient.post(
              Transaction(Instant.now,
                          depositAddress,
                          Some(mySetOfAddresses.head),
                          JobCoinValue(5)))
          } yield fulfillDeposit

          val watchComputation = watchTask.runAsync
          completeDepositTask.runAsync

          watchComputation.map { x =>
            x should not be 'empty
          }
      }
      "with a partially completed deposit, watch should fail" in runWithDependencies {
        (addressGenerator, addressesClient, transactionsClient, mixer) =>
          val getDepositAddressTask =
            mixer.tradeAddressesForNewDeposit(mySetOfAddresses)

          val watchTask = for {
            depositAddress <- getDepositAddressTask
            watched <- mixer.watchForDepositFromAddresses(
              depositAddress,
              myPromisedValue,
              mySetOfAddresses)(Instant.now(), .5 seconds, .1 seconds)
          } yield watched

          val completeDepositTask = for {
            depositAddress <- getDepositAddressTask
            myAccountBalances <- addressesClient.get(mySetOfAddresses.head)
            fulfillDeposit <- transactionsClient.post(
              Transaction(Instant.now,
                          depositAddress,
                          Some(mySetOfAddresses.head),
                          JobCoinValue(5)))
          } yield fulfillDeposit

          val watchComputation = watchTask.runAsync
          completeDepositTask.runAsync

          watchComputation.map { x =>
            x shouldBe 'empty
          }
      }
      "with no completed deposit, watch should fail" in runWithDependencies {
        (addressGenerator, addressesClient, transactionsClient, mixer) =>
          val getDepositAddressTask =
            mixer.tradeAddressesForNewDeposit(mySetOfAddresses)

          val watchTask = for {
            depositAddress <- getDepositAddressTask
            watched <- mixer.watchForDepositFromAddresses(
              depositAddress,
              myPromisedValue,
              mySetOfAddresses)(Instant.now(), .5 seconds, .1 seconds)
          } yield watched

          val watchComputation = watchTask.runAsync

          watchComputation.map { x =>
            x shouldBe 'empty
          }
      }

    }
    "Mixing" - {}
  }
}
