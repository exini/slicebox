package se.vgregion.app

import java.nio.file.Paths
import java.util.UUID

import spray.http.BodyPart
import spray.http.MultipartFormData
import spray.http.StatusCodes.BadRequest
import spray.http.StatusCodes.NoContent
import spray.http.StatusCodes.OK
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.httpx.marshalling.marshal

import org.scalatest.FlatSpec
import org.scalatest.Matchers

import se.vgregion.box.BoxProtocol.Box
import se.vgregion.box.BoxProtocol.BoxBaseUrl
import se.vgregion.box.BoxProtocol.BoxSendMethod
import se.vgregion.box.BoxProtocol.RemoteBox
import se.vgregion.box.BoxProtocol.RemoteBoxName
import se.vgregion.box.BoxProtocol.UpdateInbox

class BoxRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:boxroutestest;DB_CLOSE_DELAY=-1"

  "The system" should "return a success message when asked to generate a new base url" in {
    Post("/api/box/generatebaseurl", RemoteBoxName("hosp")) ~> routes ~> check {
      status should be(OK)
      responseAs[BoxBaseUrl].value.isEmpty should be(false)
    }
  }

  it should "return a success message when asked to add a remote box" in {
    Post("/api/box/addremotebox", RemoteBox("uni", "http://uni.edu/box/" + UUID.randomUUID())) ~> routes ~> check {
      status should be(OK)
      val box = responseAs[Box]
      box.sendMethod should be(BoxSendMethod.PUSH)
      box.name should be("uni")
    }
  }

  it should "return a bad request message when asked to add a remote box with a malformed base url" in {
    Post("/api/box/addremotebox", RemoteBox("uni2", "")) ~> routes ~> check {
      status should be(BadRequest)
    }
    Post("/api/box/addremotebox", RemoteBox("uni2", "malformed/url")) ~> routes ~> check {
      status should be(BadRequest)
    }
  }

  it should "return a list of two boxes when listing boxes" in {
    Get("/api/box") ~> routes ~> check {
      val boxes = responseAs[List[Box]]
      boxes.size should be(2)
    }
  }

  it should "support removing a box" in {
    Delete("/api/box/1") ~> routes ~> check {
      status should be(NoContent)
    }
    Delete("/api/box/2") ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "be able to receive a pushed image" in {

    // first, add a box on the poll (university) side
    val uniUrl =
      Post("/api/box/generatebaseurl", RemoteBoxName("hosp")) ~> routes ~> check {
        status should be(OK)
        responseAs[BoxBaseUrl]
      }

    // get the token from the url
    val token = uniUrl.value.substring(uniUrl.value.lastIndexOf("/") + 1)

    // then, push an image from the hospital to the uni box we just set up
    val fileName = "anon270.dcm"
    val dcmPath = Paths.get(getClass().getResource(fileName).toURI())
    val dcmFile = dcmPath.toFile

    val testTransactionId = 987
    val sequenceNumber = 1
    val totalImageCount = 1
    
    val update = UpdateInbox(token, testTransactionId, sequenceNumber, totalImageCount)
    
    marshal(update) match {
      case Right(entity) =>
        val mfd = MultipartFormData(Seq(BodyPart(dcmFile, "file"), BodyPart(entity, "update")))
        Post(s"/api/box/$token/image", mfd) ~> routes ~> check {
          status should be(NoContent)
        }
      case Left(e) => fail(e)
    }
    
  }

}