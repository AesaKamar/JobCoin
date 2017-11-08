import java.time.Instant

import org.scalatest.{AsyncFreeSpec, FreeSpec, Matchers}
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

  val transactions: ListBuffer[Transaction] = ListBuffer(
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
    val transs = FakeData.transactions.filter(_.toAddress == addr)
    (JobCoinValue(transs.map(_.amount.value).sum), transs.toList)
  }
}
case class FakeTransactionsClient()(implicit addressesClient: AddressesClient)
    extends TransactionsClient {
  override def get(): Task[List[Transaction]] =
    Task(FakeData.transactions.toList)

  override def post(transaction: Transaction): Task[Option[Transaction]] = {
    val moneyFA = transaction.fromAddress
      .map(addr => addressesClient.get(addr).map(_._1))

    val moneyThatFromMightHave = Applicative[Task].sequence(moneyFA)

    val fromBalance = moneyThatFromMightHave.map(_.getOrElse(JobCoinValue(0)))

    fromBalance.map { bal =>
      if (bal.value - transaction.amount.value >= 0) {
        FakeData.transactions.append(transaction)
        Some(transaction)
      } else None

    }

  }

}

class MixerTest extends AsyncFreeSpec with Matchers {

  "With a Mixer Impl" - {

    val addressGenerator = () => BitcoinAddress("fake")
    implicit val addressesClient: AddressesClient = FakeAddressesClient()
    implicit val transactionsClient: TransactionsClient =
      FakeTransactionsClient()

    val mixer = MixerImpl(mutable.Map.empty,
                          mutable.Map.empty,
                          mutable.Set.empty)(addressGenerator)

    "trading" in {
      val mySetOfAddresses =
        Set(BitcoinAddress("a"), BitcoinAddress("b"), BitcoinAddress("c"))
      val res = mixer.tradeAddressesForNewDeposit(mySetOfAddresses)

      res.runAsync.map(_ shouldBe BitcoinAddress("fake"))
    }
    "watching" in {
      val mySetOfAddresses =
        Set(BitcoinAddress("a"), BitcoinAddress("b"), BitcoinAddress("c"))
      val myPromisedValue = JobCoinValue(10)

      val getDepositAddress =
        mixer.tradeAddressesForNewDeposit(mySetOfAddresses)
      val watch = for {
        depositAddress <- getDepositAddress
        watched <- mixer.watch(depositAddress, myPromisedValue, mySetOfAddresses)(Instant.now(),
                                                                10 seconds,
                                                                .5 seconds)
      } yield {
        watched
      }
      val completeDeposit = for {
        depositAddress <- getDepositAddress
        myAccountBalances <- addressesClient.get(BitcoinAddress("a"))
        fulfillDeposit <- transactionsClient.post(
          Transaction(Instant.now,
                      depositAddress,
                      Some(mySetOfAddresses.head),
                      JobCoinValue(5 )))
        fulfillDeposit2 <- transactionsClient.post(
          Transaction(Instant.now,
            depositAddress,
            Some(mySetOfAddresses.head),
            JobCoinValue(5 )))
      } yield {
        fulfillDeposit
      }

      val watchComputation = watch.runAsync
      completeDeposit.runAsync

      watchComputation.map{x =>
        pprintln(x)
        x should not be 'empty}

    }
    "mixing" - {}
  }
}
