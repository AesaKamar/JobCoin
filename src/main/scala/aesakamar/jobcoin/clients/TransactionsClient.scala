package aesakamar.jobcoin.clients

import aesakamar.jobcoin.models.Transaction
import monix.eval.Task

trait TransactionsClient {
  def get(): Task[List[Transaction]]
  def post(transaction: Transaction): Task[Option[Transaction]]
}
