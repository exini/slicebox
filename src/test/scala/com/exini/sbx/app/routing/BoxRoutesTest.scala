package com.exini.sbx.app.routing

import java.util.UUID

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server._
import com.exini.sbx.anonymization.AnonymizationProtocol._
import com.exini.sbx.anonymization.{AnonymizationProfile, ConfidentialityOption}
import com.exini.sbx.box.BoxProtocol._
import com.exini.sbx.dicom.DicomHierarchy.Image
import com.exini.sbx.storage.RuntimeStorage
import com.exini.sbx.util.FutureUtil.await
import com.exini.sbx.util.TestUtil
import org.scalatest.{FlatSpecLike, Matchers}

import scala.concurrent.Future

class BoxRoutesTest extends {
  val dbConfig = TestUtil.createTestDb("boxroutestest")
  val storage = new RuntimeStorage
} with FlatSpecLike with Matchers with RoutesTestBase {

  val profile: AnonymizationProfile = AnonymizationProfile(Seq(ConfidentialityOption.BASIC_PROFILE))
  val emptyProfile: AnonymizationProfile = AnonymizationProfile(Seq.empty)

  override def afterEach(): Unit =
    await(Future.sequence(Seq(
      metaDataDao.clear(),
      seriesTypeDao.clear(),
      propertiesDao.clear(),
      boxDao.clear()
    )))

  def addPollBox(name: String, profile: AnonymizationProfile = profile): Box =
    PostAsAdmin("/api/boxes/createconnection", RemoteBoxConnectionData(name, profile)) ~> routes ~> check {
      status should be(Created)
      val response = responseAs[Box]
      response
    }

  def addPushBox(name: String, profile: AnonymizationProfile = profile): Unit = addPushBox(name, "http://some.url/api/box/" + UUID.randomUUID(), profile)

  def addPushBox(name: String, url: String, profile: AnonymizationProfile): Unit =
    PostAsAdmin("/api/boxes/connect", RemoteBox(name, url, profile)) ~> routes ~> check {
      status should be(Created)
      val box = responseAs[Box]
      box.sendMethod should be(BoxSendMethod.PUSH)
      box.name should be(name)
      box
    }

  "Box routes" should "return a success message when asked to generate a new base url" in {
    addPollBox("hosp")
  }

  it should "return a bad request message when asking to generate a new base url with a malformed request body" in {
    val malformedEntity = Seq.empty[Box]
    PostAsAdmin("/api/boxes/createconnection", malformedEntity) ~> Route.seal(routes) ~> check {
      status should be(BadRequest)
    }
  }

  it should "return 201 Created when adding two poll boxes with the same name" in {
    addPollBox("hosp")
    PostAsAdmin("/api/boxes/createconnection", RemoteBoxConnectionData("hosp", profile)) ~> Route.seal(routes) ~> check {
      status shouldBe Created
    }
    GetAsUser("/api/boxes") ~> routes ~> check {
      responseAs[List[Box]] should have length 1
    }
  }

  it should "return 400 bad request message when adding two boxes, one push and one poll, with the same name" in {
    addPushBox("mybox")
    PostAsAdmin("/api/boxes/createconnection", RemoteBoxConnectionData("mybox", profile)) ~> Route.seal(routes) ~> check {
      status should be(BadRequest)
    }
  }

  it should "return a success message when asked to add a remote box" in {
    addPushBox("uni")
  }

  it should "return 201 Created when adding two push boxes with the same name and url" in {
    val url = "http://some.url/api/box/" + UUID.randomUUID()
    addPushBox("mybox", url, profile)
    addPushBox("mybox", url, profile)
    GetAsUser("/api/boxes") ~> routes ~> check {
      responseAs[List[Box]] should have length 1
    }
  }

  it should "return 400 bad request when adding two push boxes with the same name different urls" in {
    addPushBox("mybox")
    PostAsAdmin("/api/boxes/connect", RemoteBox("mybox", "http://some.url/api/box/" + UUID.randomUUID(), profile)) ~> routes ~> check {
      status shouldBe BadRequest
    }
  }

  it should "return 201 Created when adding two push boxes with different names but the same urls" in {
    val url = "http://some.url/api/box/" + UUID.randomUUID()
    addPushBox("mybox1", url, profile)
    addPushBox("mybox2", url, profile)
    GetAsUser("/api/boxes") ~> routes ~> check {
      responseAs[List[Box]] should have length 2
    }
  }

  it should "return a bad request message when asked to add a remote box with a malformed base url" in {
    PostAsAdmin("/api/boxes/connect", RemoteBox("uni2", "", profile)) ~> Route.seal(routes) ~> check {
      status should be(BadRequest)
    }
    PostAsAdmin("/api/boxes/connect", RemoteBox("uni2", "malformed/url", profile)) ~> Route.seal(routes) ~> check {
      status should be(BadRequest)
    }
  }

  it should "return a list of two boxes when listing boxes" in {
    addPollBox("hosp")
    addPushBox("uni")
    GetAsUser("/api/boxes") ~> routes ~> check {
      val boxes = responseAs[List[Box]]
      boxes.size should be(2)
    }
  }

  it should "return a list of one boxes when listing boxes with page size set to one" in {
    addPollBox("hosp")
    addPushBox("uni")
    GetAsUser("/api/boxes?startindex=0&count=1") ~> routes ~> check {
      val boxes = responseAs[List[Box]]
      boxes.size should be(1)
    }
  }

  it should "allow listing boxes with no anonymization options" in {
    addPollBox("hosp", emptyProfile)
    addPushBox("uni", emptyProfile)
    GetAsUser("/api/boxes") ~> routes ~> check {
      val boxes = responseAs[List[Box]]
      boxes.size should be(2)
    }
  }

  it should "return a no content message when asked to send images" in {
    val box1 = addPollBox("hosp")
    PostAsAdmin(s"/api/boxes/${box1.id}/send", BulkAnonymizationData(profile, Seq(ImageTagValues(1, Seq.empty)))) ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return a no content message when asked to send images with empty images list" in {
    val box1 = addPollBox("hosp")
    PostAsAdmin(s"/api/boxes/${box1.id}/send", BulkAnonymizationData(profile, Seq.empty[ImageTagValues])) ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return a not found message when asked to send images with unknown box id" in {
    PostAsAdmin("/api/boxes/999/send",  BulkAnonymizationData(profile, Seq(ImageTagValues(1, Seq.empty)))) ~> Route.seal(routes) ~> check {
      status should be(NotFound)
    }
  }

  it should "support removing a box" in {
    val box1 = addPollBox("hosp1")
    val box2 = addPollBox("hosp2")
    DeleteAsAdmin("/api/boxes/" + box1.id) ~> routes ~> check {
      status should be(NoContent)
    }
    DeleteAsAdmin("/api/boxes/" + box2.id) ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return a no content message when asked to remove a box that does not exist" in {
    DeleteAsAdmin("/api/boxes/999") ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return a non-empty result when listing outgoing entries" in {
    val box1 = addPollBox("hosp")
    PostAsAdmin(s"/api/boxes/${box1.id}/send",  BulkAnonymizationData(profile, Seq(ImageTagValues(1, Seq.empty)))) ~> routes ~> check {
      status should be(NoContent)
    }
    GetAsUser("/api/boxes/outgoing") ~> routes ~> check {
      status should be(OK)
      responseAs[List[OutgoingTransaction]].length should be > 0
    }
  }

  it should "support listing incoming entries" in {
    await(boxDao.insertIncomingTransaction(IncomingTransaction(-1, 1, "some box", 1, 3, 3, 4, System.currentTimeMillis(), System.currentTimeMillis(), TransactionStatus.WAITING)))
    await(boxDao.insertIncomingTransaction(IncomingTransaction(-1, 1, "some box", 2, 3, 3, 5, System.currentTimeMillis(), System.currentTimeMillis(), TransactionStatus.WAITING)))

    GetAsUser("/api/boxes/incoming") ~> routes ~> check {
      responseAs[List[IncomingTransaction]].size should be(2)
    }
  }

  it should "support removing incoming entries" in {
    val entry = await(boxDao.insertIncomingTransaction(IncomingTransaction(-1, 1, "some box", 2, 3, 3, 4, System.currentTimeMillis(), System.currentTimeMillis(), TransactionStatus.WAITING)))

    DeleteAsUser(s"/api/boxes/incoming/${entry.id}") ~> routes ~> check {
      status should be(NoContent)
    }

    GetAsUser("/api/boxes/incoming") ~> routes ~> check {
      responseAs[List[IncomingTransaction]].size should be(0)
    }
  }

  it should "support removing outgoing entries" in {
    val entry = await(boxDao.insertOutgoingTransaction(OutgoingTransaction(1, 1, "some box", profile, 0, 1, 1000, 1000, TransactionStatus.WAITING)))
    await(boxDao.insertOutgoingImage(OutgoingImage(-1, entry.id, 1, 1, sent = false)))

    DeleteAsUser(s"/api/boxes/outgoing/${entry.id}") ~> routes ~> check {
      status should be(NoContent)
    }

    GetAsUser("/api/boxes/outgoing") ~> routes ~> check {
      responseAs[List[OutgoingTransaction]].size should be(0)
    }

    await(boxDao.listOutgoingImages) shouldBe empty
  }

  it should "support listing images corresponding to an incoming entry" in {
    val entry = {
      val (_, (_, _), (_, _, _, _), (dbImage1, dbImage2, _, _, _, _, _, _)) = await(TestUtil.insertMetaData(metaDataDao))
      val entry = await(boxDao.insertIncomingTransaction(IncomingTransaction(-1, 1, "some box", 2, 3, 3, 4, System.currentTimeMillis(), System.currentTimeMillis(), TransactionStatus.WAITING)))
      await(boxDao.insertIncomingImage(IncomingImage(-1, entry.id, dbImage1.id, 1, overwrite = false)))
      await(boxDao.insertIncomingImage(IncomingImage(-1, entry.id, dbImage2.id, 2, overwrite = false)))
      entry
    }

    GetAsUser(s"/api/boxes/incoming/${entry.id}/images") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Image]].length should be(2)
    }
  }

  it should "only list images corresponding to an incoming entry that exists" in {
    val entry = {
      val (_, (_, _), (_, _, _, _), (dbImage1, dbImage2, _, _, _, _, _, _)) = await(TestUtil.insertMetaData(metaDataDao))
      val entry = await(boxDao.insertIncomingTransaction(IncomingTransaction(-1, 1, "some box", 2, 3, 3, 4, System.currentTimeMillis(), System.currentTimeMillis(), TransactionStatus.WAITING)))
      await(boxDao.insertIncomingImage(IncomingImage(-1, entry.id, dbImage1.id, 1, overwrite = false)))
      await(boxDao.insertIncomingImage(IncomingImage(-1, entry.id, dbImage2.id, 2, overwrite = false)))
      await(boxDao.insertIncomingImage(IncomingImage(-1, entry.id, 666, 3, overwrite = false)))
      entry
    }

    GetAsUser(s"/api/boxes/incoming/${entry.id}/images") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Image]].length should be(2)
    }
  }

  it should "support listing images corresponding to an outgoing entry" in {
    val entry = {
      val (_, (_, _), (_, _, _, _), (dbImage1, dbImage2, _, _, _, _, _, _)) = await(TestUtil.insertMetaData(metaDataDao))
      val entry = await(boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, 1, "some box", profile, 3, 4, System.currentTimeMillis(), System.currentTimeMillis(), TransactionStatus.WAITING)))
      await(boxDao.insertOutgoingImage(OutgoingImage(-1, entry.id, dbImage1.id, 1, sent = false)))
      await(boxDao.insertOutgoingImage(OutgoingImage(-1, entry.id, dbImage2.id, 2, sent = false)))
      entry
    }

    GetAsUser(s"/api/boxes/outgoing/${entry.id}/images") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Image]].length should be(2)
    }
  }

  it should "only list images corresponding to an outgoing entry that exists" in {
    val entry = {
      val (_, (_, _), (_, _, _, _), (dbImage1, dbImage2, _, _, _, _, _, _)) = await(TestUtil.insertMetaData(metaDataDao))
      val entry = await(boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, 1, "some box", profile, 3, 4, System.currentTimeMillis(), System.currentTimeMillis(), TransactionStatus.WAITING)))
      await(boxDao.insertOutgoingImage(OutgoingImage(-1, entry.id, dbImage1.id, 1, sent = false)))
      await(boxDao.insertOutgoingImage(OutgoingImage(-1, entry.id, dbImage2.id, 2, sent = false)))
      await(boxDao.insertOutgoingImage(OutgoingImage(-1, entry.id, 666, 3, sent = false)))
      entry
    }

    GetAsUser(s"/api/boxes/outgoing/${entry.id}/images") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Image]].length should be(2)
    }
  }

  it should "remove related image record in incoming when an image is deleted" in {
    val image =
      PostAsUser("/api/images", TestUtil.testImageFormData) ~> routes ~> check {
        status shouldBe Created
        responseAs[Image]
      }

    val entry = await(boxDao.insertIncomingTransaction(IncomingTransaction(-1, 1, "some box", 2, 3, 3, 4, System.currentTimeMillis(), System.currentTimeMillis(), TransactionStatus.WAITING)))
    val imageTransaction = await(boxDao.insertIncomingImage(IncomingImage(-1, entry.id, image.id, 1, overwrite = false)))

    GetAsUser(s"/api/boxes/incoming/${entry.id}/images") ~> routes ~> check {
      status shouldBe OK
      responseAs[List[Image]] should have length 1
    }

    DeleteAsUser(s"/api/images/${imageTransaction.imageId}") ~> routes ~> check {
      status shouldBe NoContent
    }

    Thread.sleep(1000) // wait for ImageDeleted event to reach BoxServiceActor

    GetAsUser(s"/api/boxes/incoming/${entry.id}/images") ~> routes ~> check {
      status shouldBe OK
      responseAs[List[Image]] shouldBe empty
    }
  }

  it should "remove related image record in outgoing when an image is deleted" in {
    val image =
      PostAsUser("/api/images", TestUtil.testImageFormData) ~> routes ~> check {
        status shouldBe Created
        responseAs[Image]
      }

    val entry = await(boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, 1, "some box", profile, 3, 4, System.currentTimeMillis(), System.currentTimeMillis(), TransactionStatus.WAITING)))
    val imageTransaction = await(boxDao.insertOutgoingImage(OutgoingImage(-1, entry.id, image.id, 1, sent = false)))

    GetAsUser(s"/api/boxes/outgoing/${entry.id}/images") ~> routes ~> check {
      status shouldBe OK
      responseAs[List[Image]] should have length 1
    }

    DeleteAsUser(s"/api/images/${imageTransaction.imageId}") ~> routes ~> check {
      status shouldBe NoContent
    }

    Thread.sleep(1000) // wait for ImageDeleted event to reach BoxServiceActor

    GetAsUser(s"/api/boxes/outgoing/${entry.id}/images") ~> routes ~> check {
      status shouldBe OK
      responseAs[List[Image]] shouldBe empty
    }
  }

}
