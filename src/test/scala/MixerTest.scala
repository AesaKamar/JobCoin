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
import cats.effect.IO
import hammock.Status.{OK, UnprocessableEntity}

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
                JobCoinValue(1))
  )
}

case class FakeAddressesClient() extends AddressesClient {
  override def get(
      addr: BitcoinAddress): IO[(JobCoinValue, List[Transaction])] = IO.pure {
    val transs = FakeData.transactions.filter(_.toAddress != addr)
    (JobCoinValue(transs.map(_.amount.value).sum), transs.toList)
  }
}
case class FakeTransactionsClient()(implicit addressesClient: AddressesClient)
    extends TransactionsClient {
  override def get(): IO[List[Transaction]] =
    IO.pure(FakeData.transactions.toList)

  override def post(transaction: Transaction): IO[Option[Transaction]] = {
    val moneyThatFromMightHave = transaction.fromAddress
      .map(addr => addressesClient.get(addr).map(_._1))
      .sequence: IO[Option[JobCoinValue]]

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

      res.unsafeRunSync() shouldBe BitcoinAddress("fake")
    }
    "watching" in {
      val mySetOfAddresses =
        Set(BitcoinAddress("a"), BitcoinAddress("b"), BitcoinAddress("c"))

      val getDepositAddress =
        mixer.tradeAddressesForNewDeposit(mySetOfAddresses)
      val watch = for {
        depositAddress <- getDepositAddress
        watched <- mixer.watch(depositAddress)(Instant.now(),
                                               10 seconds,
                                               .5 seconds)
      } yield {
        watched
      }
      val completeDeposit = for {
        depositAddress <- getDepositAddress
        myAccountBalances <- addressesClient.get(mySetOfAddresses.head)
        fulfillDeposit <- transactionsClient.post(
          Transaction(Instant.now(),
                      depositAddress,
                      Some(mySetOfAddresses.head),
                      myAccountBalances._1))
      } yield fulfillDeposit

      val d = watch.unsafeToFuture().map(println)
      completeDeposit.unsafeToFuture().map(println)

      d.map(_ => true shouldBe true)

    }
    "mixing" - {}
  }
}
