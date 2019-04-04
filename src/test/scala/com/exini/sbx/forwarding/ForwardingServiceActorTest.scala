package com.exini.sbx.forwarding

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}
import com.exini.sbx.anonymization.{AnonymizationProfile, ConfidentialityOption}
import com.exini.sbx.app.GeneralProtocol._
import com.exini.sbx.box.BoxProtocol._
import com.exini.sbx.dicom.DicomHierarchy.Image
import com.exini.sbx.dicom.DicomPropertyValue._
import com.exini.sbx.forwarding.ForwardingProtocol._
import com.exini.sbx.metadata.MetaDataProtocol._
import com.exini.sbx.storage.RuntimeStorage
import com.exini.sbx.util.FutureUtil.await
import com.exini.sbx.util.TestUtil

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.DurationInt

class ForwardingServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("ForwardingServiceActorTestSystem"))

  implicit val ec: ExecutionContextExecutor = system.dispatcher
  implicit val timeout: Timeout = Timeout(30.seconds)

  val dbConfig = TestUtil.createTestDb("forwardingserviceactortest")
  val db = dbConfig.db

  val forwardingDao = new ForwardingDAO(dbConfig)

  await(forwardingDao.create())

  var deletedImages = Seq.empty[Long]
  val storage: RuntimeStorage = new RuntimeStorage() {
    override def deleteFromStorage(imageIds: Seq[Long]): Unit = {
      deletedImages = deletedImages ++ imageIds
      super.deleteFromStorage(imageIds)
    }
  }

  case class SetSource(source: Source)

  val setSourceReply = "Source set"
  val metaDataService: ActorRef = system.actorOf(Props(new Actor {
    var source: Option[Source] = None
    def receive: Receive = {
      case GetImage(imageId) =>
        sender ! (imageId match {
          case 10 => Some(image1)
          case 23 => Some(image2)
          case 38 => Some(image3)
          case _ => None
        })
      case GetSourceForSeries(_) =>
        sender ! source.map(SeriesSource(-1, _))
      case DeleteMetaData(_) =>
        sender ! MetaDataDeleted(Seq.empty, Seq.empty, Seq.empty, Seq.empty)
      case SetSource(newSource) =>
        source = Option(newSource)
        sender ! setSourceReply
    }
  }), name = "MetaDataService")

  case object ResetSentImages

  case object GetSentImages

  val resetSentImagesReply = "Send images reset"
  val boxService: ActorRef = system.actorOf(Props(new Actor {
    var sentImages = Seq.empty[Long]

    def receive: Receive = {
      case GetBoxById(id) =>
        sender ! Some(Box(id, "box", "1.2.3.4", "http://example.com", BoxSendMethod.PUSH, AnonymizationProfile(Seq(ConfidentialityOption.BASIC_PROFILE)), online = false))
      case SendToRemoteBox(box, bulkAnonymizationData) =>
        sentImages = sentImages ++ bulkAnonymizationData.imageTagValuesSet.map(_.imageId)
        sender ! ImagesAddedToOutgoing(box.id, bulkAnonymizationData.imageTagValuesSet.map(_.imageId))
      case ResetSentImages =>
        sentImages = Seq.empty[Long]
        sender ! resetSentImagesReply
      case GetSentImages =>
        sender ! sentImages
    }
  }), name = "BoxService")

  val forwardingService: ActorRef = system.actorOf(Props(new ForwardingServiceActor(forwardingDao, storage, 1000.hours)(Timeout(30.seconds))), name = "ForwardingService")

  override def beforeEach(): Unit = {
    deletedImages = Seq.empty[Long]
  }

  override def afterEach(): Unit = {
    await(forwardingDao.clear())
    metaDataService ! SetSource(null)
    expectMsg(setSourceReply)
    boxService ! ResetSentImages
    expectMsg(resetSentImagesReply)
  }

  override def afterAll: Unit = TestKit.shutdownActorSystem(system)

  "A ForwardingServiceActor" should {

    "support adding and listing forwarding rules" in {

      forwardingService ! GetForwardingRules(0, 1)
      expectMsg(ForwardingRules(List.empty))

      val rule1 = scpToBoxRule
      val rule2 = userToBoxRule

      forwardingService ! AddForwardingRule(rule1)
      forwardingService ! AddForwardingRule(rule2)

      val dbRule1 = expectMsgType[ForwardingRuleAdded].forwardingRule
      val dbRule2 = expectMsgType[ForwardingRuleAdded].forwardingRule

      forwardingService ! GetForwardingRules(0, 10)
      expectMsg(ForwardingRules(List(dbRule1, dbRule2)))
    }

    "support deleting forwarding rules" in {
      val rule1 = scpToBoxRule

      forwardingService ! AddForwardingRule(rule1)
      val dbRule1 = expectMsgType[ForwardingRuleAdded].forwardingRule

      forwardingService ! GetForwardingRules(0, 10)
      expectMsg(ForwardingRules(List(dbRule1)))

      forwardingService ! RemoveForwardingRule(dbRule1.id)
      expectMsg(ForwardingRuleRemoved(dbRule1.id))

      forwardingService ! GetForwardingRules(0, 1)
      expectMsg(ForwardingRules(List.empty))
    }
  }

  "not forward an added image if there are no forwarding rules" in {
    forwardingService ! ImageAdded(image1.id, scpSource, overwrite = false)
    expectMsgPF() {
      case ImageRegisteredForForwarding(imageId, applicableRules) =>
        imageId shouldBe image1.id
        applicableRules shouldBe empty
    }
    expectNoMessage(3.seconds)
    await(forwardingDao.listForwardingRules(0, 1)) should be(empty)
    await(forwardingDao.listForwardingTransactions) should be(empty)
    await(forwardingDao.listForwardingTransactionImages) should be(empty)
  }

  "not forward an added image if there are no matching forwarding rules" in {
    val rule = scpToBoxRule

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    forwardingService ! ImageAdded(image1.id, userSource, overwrite = false)
    expectMsgPF() {
      case ImageRegisteredForForwarding(imageId, applicableRules) =>
        imageId shouldBe image1.id
        applicableRules shouldBe empty
    }

    await(forwardingDao.listForwardingTransactions) should be(empty)
    await(forwardingDao.listForwardingTransactionImages) should be(empty)
  }

  "forward an added image if there are matching forwarding rules" in {
    val rule = userToBoxRule
    metaDataService ! SetSource(userSource)
    expectMsg(setSourceReply)

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    forwardingService ! ImageAdded(image1.id, userSource, overwrite = false)
    expectMsgPF() {
      case ImageRegisteredForForwarding(imageId, applicableRules) =>
        imageId shouldBe image1.id
        applicableRules should have length 1
    }

    await(forwardingDao.listForwardingTransactions).length should be(1)
    await(forwardingDao.listForwardingTransactionImages).length should be(1)
  }

  "create multiple transactions when there are multiple rules with the same source and an image with that source is received" in {
    val rule1 = userToBoxRule
    val rule2 = userToAnotherBoxRule

    metaDataService ! SetSource(userSource)
    expectMsg(setSourceReply)

    forwardingService ! AddForwardingRule(rule1)
    forwardingService ! AddForwardingRule(rule2)
    expectMsgType[ForwardingRuleAdded]
    expectMsgType[ForwardingRuleAdded]

    forwardingService ! ImageAdded(image1.id, userSource, overwrite = false)
    expectMsgPF() {
      case ImageRegisteredForForwarding(imageId, applicableRules) =>
        imageId shouldBe image1.id
        applicableRules should have length 2
    }

    await(forwardingDao.listForwardingTransactions).length should be(2)
    await(forwardingDao.listForwardingTransactionImages).length should be(2)
  }

  "not send queued images if the corresponding transaction was recently updated" in {
    val rule = userToBoxRule

    metaDataService ! SetSource(userSource)
    expectMsg(setSourceReply)

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    forwardingService ! ImageAdded(image1.id, userSource, overwrite = false)
    expectMsgType[ImageRegisteredForForwarding]

    forwardingService ! PollForwardingQueue
    expectMsg(TransactionsEnroute(List.empty))

    await(forwardingDao.listForwardingTransactions).length should be(1)
    await(forwardingDao.listForwardingTransactionImages).length should be(1)
  }

  "send queued images if the corresponding transaction has expired (i.e. has not been updated in a while)" in {
    val rule = userToBoxRule

    metaDataService ! SetSource(userSource)
    expectMsg(setSourceReply)

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    forwardingService ! ImageAdded(image1.id, userSource, overwrite = false)
    expectMsgType[ImageRegisteredForForwarding]

    expireTransaction(0)

    forwardingService ! PollForwardingQueue
    expectMsgPF() {
      case TransactionsEnroute(transactions) => transactions.length should be(1)
    }

    await(forwardingDao.listForwardingTransactions).length should be(1)
    await(forwardingDao.listForwardingTransactionImages).length should be(1)
    val transaction = await(forwardingDao.listForwardingTransactions).head
    transaction.enroute should be(true)
    transaction.delivered should be(false)
  }

  "mark forwarding transaction as delivered after images have been sent" in {
    val rule = userToBoxRule

    metaDataService ! SetSource(userSource)
    expectMsg(setSourceReply)

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    val image = image1
    forwardingService ! ImageAdded(image.id, userSource, overwrite = false)
    expectMsgType[ImageRegisteredForForwarding]

    expireTransaction(0)

    forwardingService ! PollForwardingQueue
    expectMsgPF() {
      case TransactionsEnroute(transactions) => transactions.length should be(1)
    }

    forwardingService ! ImagesSent(rule.destination, Seq(image.id))
    expectMsgPF() {
      case TransactionMarkedAsDelivered(transactionMaybe) => transactionMaybe should be(defined)
    }

    await(forwardingDao.listForwardingTransactions).length should be(1)
    await(forwardingDao.listForwardingTransactionImages).length should be(1)
    val transaction = await(forwardingDao.listForwardingTransactions).head
    transaction.enroute should be(false)
    transaction.delivered should be(true)
  }

  "remove transaction, transaction images and stored images when a forwarding transaction is finalized" in {
    val rule = userToBoxRule

    metaDataService ! SetSource(userSource)
    expectMsg(setSourceReply)

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    val image = image1
    forwardingService ! ImageAdded(image.id, userSource, overwrite = false)
    expectMsgType[ImageRegisteredForForwarding]

    expireTransaction(0)

    forwardingService ! PollForwardingQueue
    expectMsgPF() {
      case TransactionsEnroute(transactions) => transactions.length should be(1)
    }

    forwardingService ! ImagesSent(rule.destination, Seq(image.id))
    expectMsgPF() {
      case TransactionMarkedAsDelivered(transactionMaybe) => transactionMaybe should be(defined)
    }

    forwardingService ! FinalizeSentTransactions
    expectMsgPF() {
      case TransactionsFinalized(removedTransactions) =>
        removedTransactions.length should be(1)
    }

    await(forwardingDao.listForwardingTransactions) should be(empty)
    await(forwardingDao.listForwardingTransactionImages) should be(empty)

    // wait for deletion of images to finish
    expectNoMessage(3.seconds)

    deletedImages shouldBe Seq(image.id)
  }

  "remove transaction, transaction images but not stored images when a forwarding transaction is finalized for a rule with keepImages set to true" in {
    val rule = userToBoxRuleKeepImages

    metaDataService ! SetSource(userSource)
    expectMsg(setSourceReply)

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    val image = image1
    forwardingService ! ImageAdded(image.id, userSource, overwrite = false)
    expectMsgType[ImageRegisteredForForwarding]

    expireTransaction(0)

    forwardingService ! PollForwardingQueue
    expectMsgPF() {
      case TransactionsEnroute(transactions) => transactions.length should be(1)
    }

    forwardingService ! ImagesSent(rule.destination, Seq(image.id))
    expectMsgPF() {
      case TransactionMarkedAsDelivered(transactionMaybe) => transactionMaybe should be(defined)
    }

    forwardingService ! FinalizeSentTransactions
    expectMsgPF() {
      case TransactionsFinalized(removedTransactions) =>
        removedTransactions.length should be(1)
    }

    await(forwardingDao.listForwardingTransactions) should be(empty)
    await(forwardingDao.listForwardingTransactionImages) should be(empty)
    deletedImages shouldBe Seq.empty
  }

  "create a new transaction for a newly added image as soon as a transaction has been marked as enroute" in {
    val rule = userToBoxRule

    metaDataService ! SetSource(userSource)
    expectMsg(setSourceReply)

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    forwardingService ! ImageAdded(image1.id, userSource, overwrite = false)
    expectMsgType[ImageRegisteredForForwarding]

    expireTransaction(0)

    forwardingService ! PollForwardingQueue
    expectMsgPF() {
      case TransactionsEnroute(trans) => trans.length should be(1)
    }

    forwardingService ! ImageAdded(image2.id, userSource, overwrite = false)
    expectMsgType[ImageRegisteredForForwarding]

    val transactions = await(forwardingDao.listForwardingTransactions)
    transactions.length should be(2)
    val transaction1 = transactions.head
    val transaction2 = transactions(1)
    transaction1.enroute should be(true)
    transaction1.delivered should be(false)
    transaction2.enroute should be(false)
    transaction2.delivered should be(false)
  }

  "create a single transaction when forwarding multiple transactions in succession with a box source" in {
    val rule = boxToBoxRule

    metaDataService ! SetSource(boxSource)
    expectMsg(setSourceReply)

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    forwardingService ! ImageAdded(image3.id, boxSource, overwrite = false)
    expectMsgType[ImageRegisteredForForwarding]

    forwardingService ! ImageAdded(image1.id, boxSource, overwrite = false)
    expectMsgType[ImageRegisteredForForwarding]

      val transactions = await(forwardingDao.listForwardingTransactions)
      transactions.length should be(1)
  }

  "forward the correct list of images" in {
    val rule = userToBoxRule
    metaDataService ! SetSource(userSource)
    expectMsg(setSourceReply)

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    forwardingService ! ImageAdded(image1.id, userSource, overwrite = false)
    forwardingService ! ImageAdded(image3.id, userSource, overwrite = false)
    forwardingService ! ImageAdded(image2.id, userSource, overwrite = false)
    expectMsgType[ImageRegisteredForForwarding]
    expectMsgType[ImageRegisteredForForwarding]
    expectMsgType[ImageRegisteredForForwarding]

    await(forwardingDao.listForwardingTransactions) should have length 1

    expireTransaction(0)

    forwardingService ! PollForwardingQueue
    expectMsgPF() {
      case TransactionsEnroute(transactions) => transactions should have length 1
    }

    // wait for box transfer to complete
    Thread.sleep(3000)

    boxService ! GetSentImages
    expectMsg(Seq(image1.id, image3.id, image2.id))
  }

  def scpSource = Source(SourceType.SCP, "My SCP", 1)
  def userSource = Source(SourceType.USER, "Admin", 35)
  def boxSource = Source(SourceType.BOX, "Source box", 11)
  def boxDestination = Destination(DestinationType.BOX, "Remote box", 1)
  def boxDestination2 = Destination(DestinationType.BOX, "Another remote box", 2)

  def scpToBoxRule = ForwardingRule(-1, scpSource, boxDestination, keepImages = false)
  def userToBoxRule = ForwardingRule(-1, userSource, boxDestination, keepImages = false)
  def userToAnotherBoxRule = ForwardingRule(-1, userSource, boxDestination2, keepImages = false)
  def boxToBoxRule = ForwardingRule(-1, boxSource, boxDestination, keepImages = false)
  def userToBoxRuleKeepImages = ForwardingRule(-1, userSource, boxDestination, keepImages = true)

  def image1 = Image(10, 22, SOPInstanceUID("sopuid1"), ImageType("it"), InstanceNumber("in1"))
  def image2 = Image(23, 22, SOPInstanceUID("sopuid2"), ImageType("it"), InstanceNumber("in2"))
  def image3 = Image(38, 22, SOPInstanceUID("sopuid3"), ImageType("it"), InstanceNumber("in3"))

  def expireTransaction(index: Int): Int = {
    val transactions = await(forwardingDao.listForwardingTransactions)
    await(forwardingDao.updateForwardingTransaction(transactions(index).copy(updated = 0)))
  }
}