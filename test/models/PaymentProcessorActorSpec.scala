package models

import java.time.ZonedDateTime

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.typesafe.config.{Config, ConfigFactory}
import models.Generators._
import models.PaymentProcessor._
import models.repo.PaymentRepo
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.{ArgumentMatchers, Mockito}
import org.mockito.Mockito._
import org.scalacheck.Gen
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import org.scalatest.time.SpanSugar
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import stellar.sdk.model.response.AccountResponse
import stellar.sdk.model.result.{CreateAccountSuccess, OperationResult, PaymentSuccess}
import stellar.sdk.model.{Account, Thresholds}
import stellar.sdk.{KeyPair, Network}

class PaymentProcessorActorSpec extends TestKit(ActorSystem("payment-processor-spec", TestKitConfig.conf)) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar with Eventually with SpanSugar {

  override def afterAll: Unit = TestKit.shutdownActorSystem(system)

  private val accountA = KeyPair.fromPassphrase("account a").asPublicKey
  private val accountB = KeyPair.fromPassphrase("account b").asPublicKey

  "a payment processor" must {

    "not attempt to process payments if there is no next payment time" in {
      val (_, conf, repo, _) = setup
      val cache = mock[AccountCache]
      val actor = system.actorOf(Props(new PaymentProcessorActor(repo, cache, conf)))
      val account = sampleOf(genAccount)

      actor ! ProcessPayments

      // this is just a synthetic wait to ensure that the first message has been dealt with
      actor ! UpdateAccount(account)
      eventually(timeout(5 seconds)) {
        verify(cache).returnAccount(account)
      }

      // the assertion we actually care about
      verify(cache, never()).readyCount
    }

    "not attempt to process payments if the payments are not yet due" in {
      val (_, conf, repo, cache) = setup
      val actor = system.actorOf(Props(new PaymentProcessorActor(repo, cache, conf)))
      val account = sampleOf(genAccount)
      cache.returnAccount(account)
      val date = ZonedDateTime.now().plusHours(1)

      when(repo.earliestTimeDue).thenReturn(Some(date))
      actor ! UpdateNextPaymentTime

      actor ! ProcessPayments

      // this is just a synthetic wait to ensure that the first messages have been dealt with
      actor ! UpdateAccount(account)
      eventually(timeout(5 seconds)) {
        assert(cache.borrowAccount.nonEmpty)
      }

      verify(repo).earliestTimeDue
      // ensures that we haven't entered the main payment processing block
      verify(repo, never()).due(anyInt)
    }

    "not attempt to process payments if there payments are due, but there are no free accounts" in {
      val (_, conf, repo, cache) = setup
      val actor = system.actorOf(Props(new PaymentProcessorActor(repo, cache, conf)))
      val account = sampleOf(genAccount)
      val date = ZonedDateTime.now()

      when(repo.earliestTimeDue).thenReturn(Some(date))
      actor ! UpdateNextPaymentTime

      actor ! ProcessPayments

      // this is just a synthetic wait to ensure that the first messages have been dealt with
      actor ! UpdateAccount(account)
      eventually(timeout(5 seconds)) {
        assert(cache.borrowAccount.nonEmpty)
      }

      // ensures that we haven't entered the main payment processing block
      verify(repo, never()).due(anyInt)
    }

    "not attempt to process payments if, somehow, there are no payments due" in {
      val (_, conf, repo, cache) = setup
      val actor = system.actorOf(Props(new PaymentProcessorActor(repo, cache, conf)))
      val account = sampleOf(genAccount)
      val date = ZonedDateTime.now().minusSeconds(1)
      cache.returnAccount(account)

      when(repo.earliestTimeDue).thenReturn(Some(date))
      actor ! UpdateNextPaymentTime

      when(repo.due(100)).thenReturn(Seq.empty)
      actor ! ProcessPayments

      eventually(timeout(5 seconds)) {
        // it should be called a second time, when zero payments are found
        verify(repo, times(2)).earliestTimeDue
      }
    }

    "update the next payment time" in {
      val (_, conf, repo, _) = setup
      val cache = mock[AccountCache]
      val actor = system.actorOf(Props(new PaymentProcessorActor(repo, cache, conf)))
      val date = ZonedDateTime.now()

      when(repo.earliestTimeDue).thenReturn(Some(date))
      when(cache.readyCount).thenReturn(0)

      actor ! UpdateNextPaymentTime
      actor ! ProcessPayments

      eventually(timeout(5 seconds)) {
        verify(repo).earliestTimeDue // shows that the db was queried
        verify(cache).readyCount     // shows that the cache was inspected on payment attempt, because date was found in context
      }
    }

    "confirm successful payments and return account to pool" in {
      val (_, conf, repo, _) = setup
      val cache = mock[AccountCache]
      val actor = system.actorOf(Props(new PaymentProcessorActor(repo, cache, conf)))
      val account = sampleOf(genAccount)
      val payments = sampleOf(Gen.listOfN(3, genPayment))

      actor ! Confirm(payments, account)

      eventually(timeout(5 seconds)) {
        verify(repo).confirm(payments.flatMap(_.id))
        verify(cache).returnAccount(account.withIncSeq)
      }
    }

    "mark payments as failed and retry payments, updating sequence number" in {
      val (_, conf, repo, _) = setup
      val cache = mock[AccountCache]
      val probe = TestProbe()
      val actor = system.actorOf(Props(new PaymentProcessorActor(repo, cache, conf){
        override def processPayments(nextKnownPaymentDate: Option[ZonedDateTime]): PartialFunction[Any, Unit] = {
          case ProcessPayments => probe.ref ! ProcessPayments
        }
      }))
      val account = sampleOf(genAccount)
      val payments = sampleOf(Gen.listOfN(3, genPayment))
      val results = sampleOf(Gen.listOfN(3, genPaymentOpResultFailure))

      actor ! RejectPayments(payments, results, account, updatedSeqNo = true)

      probe.expectMsg(ProcessPayments)
      verify(repo).rejectWithOpResult(payments.flatMap(_.id).zip(results.map(resultToString)))
      verify(cache).returnAccount(account.withIncSeq)
    }

    "mark payments as failed and retry payments, without updating sequence number" in {
      val (_, conf, repo, _) = setup
      val cache = mock[AccountCache]
      val probe = TestProbe()
      val actor = system.actorOf(Props(new PaymentProcessorActor(repo, cache, conf){
        override def processPayments(nextKnownPaymentDate: Option[ZonedDateTime]): PartialFunction[Any, Unit] = {
          case ProcessPayments => probe.ref ! ProcessPayments
        }
      }))
      val account = sampleOf(genAccount)
      val payments = sampleOf(Gen.listOfN(3, genPayment))
      val results = sampleOf(Gen.listOfN(3, genPaymentOpResultFailure))

      actor ! RejectPayments(payments, results, account, updatedSeqNo = false)

      probe.expectMsg(ProcessPayments)
      verify(repo).rejectWithOpResult(payments.flatMap(_.id).zip(results.map(resultToString)))
      verify(cache).returnAccount(account)
    }

    "mark payments as failed and update status of the successful operations to 'batch failure'" in {
      val (_, conf, repo, _) = setup
      val cache = mock[AccountCache]
      val probe = TestProbe()
      val actor = system.actorOf(Props(new PaymentProcessorActor(repo, cache, conf){
        override def processPayments(nextKnownPaymentDate: Option[ZonedDateTime]): PartialFunction[Any, Unit] = {
          case ProcessPayments => probe.ref ! ProcessPayments
        }
      }))
      val account = sampleOf(genAccount)
      val payments = sampleOf(Gen.listOfN(3, genPayment))
      val results = Seq(sampleOf(genPaymentOpResultFailure), PaymentSuccess, CreateAccountSuccess)

      actor ! RejectPayments(payments, results, account, updatedSeqNo = false)

      probe.expectMsg(ProcessPayments)
      val expectedOpResultStrings = resultToString(results.head) +: "Batch Failure" +: "Batch Failure" +: Nil
      verify(repo).rejectWithOpResult(payments.flatMap(_.id).zip(expectedOpResultStrings))
      verify(cache).returnAccount(account)
    }

    "mark transaction as failed and retry payments, updating sequence number" in {
      val (_, conf, repo, _) = setup
      val cache = mock[AccountCache]
      val probe = TestProbe()
      val actor = system.actorOf(Props(new PaymentProcessorActor(repo, cache, conf){
        override def processPayments(nextKnownPaymentDate: Option[ZonedDateTime]): PartialFunction[Any, Unit] = {
          case ProcessPayments => probe.ref ! ProcessPayments
        }
      }))
      val account = sampleOf(genAccount)
      val payments = sampleOf(Gen.listOfN(3, genPayment))

      actor ! RejectTransaction(payments, account, updatedSeqNo = true)

      probe.expectMsg(ProcessPayments)
      verify(repo).reject(payments.flatMap(_.id))
      verify(cache).returnAccount(account.withIncSeq)
    }

    "mark transaction as failed and retry payments, without updating sequence number" in {
      val (_, conf, repo, _) = setup
      val cache = mock[AccountCache]
      val probe = TestProbe()
      val actor = system.actorOf(Props(new PaymentProcessorActor(repo, cache, conf){
        override def processPayments(nextKnownPaymentDate: Option[ZonedDateTime]): PartialFunction[Any, Unit] = {
          case ProcessPayments => probe.ref ! ProcessPayments
        }
      }))
      val account = sampleOf(genAccount)
      val payments = sampleOf(Gen.listOfN(3, genPayment))

      actor ! RejectTransaction(payments, account, updatedSeqNo = false)

      probe.expectMsg(ProcessPayments)
      verify(repo).reject(payments.flatMap(_.id))
      verify(cache).returnAccount(account)
    }

    "mark payments as requiring a retry and immediately retry them" in {
      val (_, conf, repo, _) = setup
      val cache = mock[AccountCache]
      val probe = TestProbe()
      val actor = system.actorOf(Props(new PaymentProcessorActor(repo, cache, conf){
        override def processPayments(nextKnownPaymentDate: Option[ZonedDateTime]): PartialFunction[Any, Unit] = {
          case ProcessPayments => probe.ref ! ProcessPayments
        }
      }))
      val account = sampleOf(genAccount)
      val payments = sampleOf(Gen.listOfN(3, genPayment))

      actor ! RetryPayments(payments, account)

      probe.expectMsg(ProcessPayments)
      verify(repo).retry(payments.flatMap(_.id))
      verify(cache).retireAccount(account)
    }

    "update an account" in {
      val (_, conf, repo, cache) = setup
      val account = Account(accountA, 100)
      val actor = system.actorOf(Props(new PaymentProcessorActor(repo, cache, conf)))

      actor ! UpdateAccount(account)

      eventually(timeout(5 seconds)) {
        assert(cache.borrowAccount.contains(account))
      }
    }

    "fetch an account from the configured network, updating it on success" in {
      val (network, conf, repo, cache) = setup
      val account = Account(accountA, 101)

      network.expectAccount(accountA,
        AccountResponse(accountA, 101, 0, Thresholds(1, 2, 3), authRequired = false, authRevocable = true, Nil, Nil)
      )

      val actor = system.actorOf(Props(new PaymentProcessorActor(repo, cache, conf)))

      actor ! RegisterAccount(accountA)

      eventually(timeout(5 seconds)) {
        assert(cache.borrowAccount.contains(account.withIncSeq))
      }
    }

    "fetch an account from the configured network, ignoring failures" in {
      val (network, conf, repo, cache) = setup
      val account = Account(accountA, 101)

      network.expectAccount(accountA,
        AccountResponse(accountA, 101, 0, Thresholds(1, 2, 3), authRequired = false, authRevocable = true, Nil, Nil)
      )

      val actor = system.actorOf(Props(new PaymentProcessorActor(repo, cache, conf)))

      actor ! RegisterAccount(accountB)
      actor ! RegisterAccount(accountA)

      // we only expect account A to be found. The failed lookup for account B should no-op.
      eventually(timeout(5 seconds)) {
        assert(cache.borrowAccount.contains(account.withIncSeq))
      }
    }
  }

  private def setup: (StubNetwork, AppConfig, PaymentRepo, AccountCache) = {
    val n = StubNetwork()
    val conf = new AppConfig {
      val network: Network = n
      val accounts: Map[String, KeyPair] = Map.empty
    }
    val repo = mock[PaymentRepo]
    (n, conf, repo, new AccountCache)
  }

  private def resultToString(res: OperationResult): String = {
    res.getClass.getSimpleName.replaceAll("([a-z])([A-Z])", "$1 $2").replaceFirst("\\$$", "")
  }
}


object TestKitConfig {
  val conf: Config = ConfigFactory.parseString(
    """
      |akka.loglevel = "OFF"
      |0rora.loglevel = "OFF"
    """.stripMargin)
}