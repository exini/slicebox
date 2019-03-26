package com.exini.sbx.box

import java.util.concurrent.atomic.AtomicInteger

import akka.NotUsed
import akka.actor.{ActorSystem, PoisonPill, Props}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Source}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.{ByteString, Timeout}
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}
import com.exini.sbx.anonymization.{AnonymizationProfile, ConfidentialityOption}
import com.exini.sbx.anonymization.AnonymizationProtocol.TagValue
import com.exini.sbx.app.JsonFormats
import com.exini.sbx.box.BoxProtocol._
import com.exini.sbx.storage.{RuntimeStorage, StorageService}

import scala.collection.immutable.Seq
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Success, Try}

class BoxPollActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach with JsonFormats with PlayJsonSupport {

  def this() = this(ActorSystem("BoxPollActorTestSystem"))

  implicit val ec: ExecutionContextExecutor = system.dispatcher
  implicit val timeout: Timeout = Timeout(30.seconds)
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val profile = AnonymizationProfile(Seq(ConfidentialityOption.BASIC_PROFILE))
  val box = Box(1, "Test Box", "abc123", "testbox.com", BoxSendMethod.PUSH, profile, online = true)
  val transaction: OutgoingTransaction = OutgoingTransaction(1, box.id, box.name, profile, 0, 1, 1000, 1000, TransactionStatus.WAITING)
  val storage = new RuntimeStorage()

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  "A BoxPollActor" should {

    "update incoming transaction" in {
      val n = 200
      val nUpdated = new AtomicInteger()
      var firstBatch = true
      val pollActorRef = system.actorOf(Props(
        new BoxPollActor(box, storage, 200.milliseconds, n, 8, "../BoxService", "../MetaService", "../AnonService") {
          override protected def anonymizedDicomData(imageId: Long, profile: AnonymizationProfile, tagValues: scala.collection.Seq[TagValue], storage: StorageService)(implicit ec: ExecutionContext): Source[ByteString, NotUsed] =
            Source.single(ByteString(1, 2, 3, 4))
          override def poll(n: Int): Future[Seq[OutgoingTransactionImage]] =
            if (firstBatch) {
              firstBatch = false
              Future((1 to n)
                .map(id => OutgoingImage(id, transaction.id, 1000 + id, id, sent = false))
                .map(image => OutgoingTransactionImage(transaction, image)))(ec)
            } else
              Future(Seq.empty)(ec)
          override def pool[T]: Flow[(HttpRequest, T), (Try[HttpResponse], T), _] =
            Flow.fromFunction((rt: (HttpRequest, T)) => (Success(HttpResponse()), rt._2))
          override def updateIncoming(transactionImage: OutgoingTransactionImage, imageIdMaybe: Option[Long], added: Boolean): Future[IncomingUpdated] = {
            nUpdated.getAndIncrement()
            Future(IncomingUpdated(IncomingTransaction(
              -1, box.id, box.name, transactionImage.transaction.id,
              transactionImage.transaction.sentImageCount,
              transactionImage.transaction.sentImageCount,
              transactionImage.transaction.totalImageCount,
              1000, 1000, TransactionStatus.PROCESSING)))(ec)
          }
        }), name = "PushBox")

      akka.pattern.after(2.seconds, system.scheduler) {
        Future {
          pollActorRef ! PoisonPill
          nUpdated.intValue() shouldBe n
        }
      }
    }
  }
}