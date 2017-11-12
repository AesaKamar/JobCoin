package aesakamar.jobcoin.clients

import aesakamar.jobcoin.models._
import monix.eval.Task

trait AddressesClient {
  def get(addr: BitcoinAddress): Task[AddressSummary]
}
