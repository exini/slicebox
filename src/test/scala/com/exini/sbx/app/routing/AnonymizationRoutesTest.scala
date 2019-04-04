package com.exini.sbx.app.routing

import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server._
import akka.util.ByteString
import org.scalatest.{FlatSpecLike, Matchers}
import com.exini.dicom.data.{Tag, TagPath}
import com.exini.sbx.anonymization.{AnonymizationProfile, ConfidentialityOption}
import com.exini.sbx.anonymization.AnonymizationProtocol._
import com.exini.sbx.dicom.DicomHierarchy._
import com.exini.sbx.dicom.DicomProperty.PatientName
import com.exini.sbx.metadata.MetaDataProtocol._
import com.exini.sbx.storage.RuntimeStorage
import com.exini.sbx.util.FutureUtil.await
import com.exini.sbx.util.TestUtil

import scala.concurrent.Future

class AnonymizationRoutesTest extends {
  val dbConfig = TestUtil.createTestDb("anonymizationroutestest")
  val storage = new RuntimeStorage()
} with FlatSpecLike with Matchers with RoutesTestBase {

  val profile = AnonymizationProfile(Seq(ConfidentialityOption.BASIC_PROFILE))

  override def afterEach() {
    await(Future.sequence(Seq(
      metaDataDao.clear(),
      seriesTypeDao.clear(),
      propertiesDao.clear(),
      anonymizationDao.clear()
    )))
    storage.asInstanceOf[RuntimeStorage].clear()
  }

  "Anonymization routes" should "return 200 OK and anonymize the data by removing the old data and inserting new anonymized data upon manual anonymization" in {
    val image =
      PostAsUser("/api/images", TestUtil.testImageFormData) ~> routes ~> check {
        status shouldBe Created
        responseAs[Image]
      }

    val flatSeries = GetAsUser(s"/api/metadata/flatseries/${image.seriesId}") ~> routes ~> check {
      status should be(OK)
      responseAs[FlatSeries]
    }
    val anonImage =
      PutAsUser(s"/api/images/${image.id}/anonymize", AnonymizationData(profile, Seq.empty)) ~> routes ~> check {
        status should be(OK)
        responseAs[Image]
      }

    image.id should not be anonImage.id

    GetAsUser("/api/metadata/patients/1") ~> Route.seal(routes) ~> check {
      status should be(NotFound)
    }
    GetAsUser("/api/metadata/patients/2") ~> routes ~> check {
      status should be(OK)
      val anonPatient = responseAs[Patient]
      anonPatient.patientName should not be flatSeries.patient.patientName
      anonPatient.patientID should not be flatSeries.patient.patientID
      anonPatient.patientSex.value shouldBe empty
    }
    GetAsUser("/api/metadata/studies/1") ~> Route.seal(routes) ~> check {
      status should be(NotFound)
    }
    GetAsUser("/api/metadata/studies/2") ~> routes ~> check {
      status should be(OK)
    }
    GetAsUser("/api/metadata/series/1") ~> Route.seal(routes) ~> check {
      status should be(NotFound)
    }
    GetAsUser("/api/metadata/series/2") ~> routes ~> check {
      status should be(OK)
    }
    GetAsUser("/api/metadata/images/1") ~> Route.seal(routes) ~> check {
      status should be(NotFound)
    }
    GetAsUser("/api/metadata/images/2") ~> routes ~> check {
      status should be(OK)
    }
  }

  it should "return 200 OK and an anonymized version of the image with the supplied ID" in {
    val image =
      PostAsUser("/api/images", TestUtil.testImageFormData) ~> routes ~> check {
        status shouldBe Created
        responseAs[Image]
      }

    val flatSeries = GetAsUser(s"/api/metadata/flatseries/${image.seriesId}") ~> routes ~> check {
      status should be(OK)
      responseAs[FlatSeries]
    }

    val anonPatientName = "Anon Pat 1"
    val tagValues = Seq(TagValue(TagPath.fromTag(PatientName.dicomTag), anonPatientName))
    val anonAttributes =
      PostAsUser(s"/api/images/${image.id}/anonymized", AnonymizationData(profile, tagValues)) ~> routes ~> check {
        status should be(OK)
        TestUtil.loadDicomData(responseAs[ByteString], withPixelData = true)
      }

    anonAttributes.getString(Tag.PatientName).get shouldBe anonPatientName
    anonAttributes.getString(Tag.PatientID).get should not be flatSeries.patient.patientName.value
  }

  it should "return 200 OK and the image IDs of the new anonymized images when bulk anonymizing a sequence of images" in {
    val dd1 = TestUtil.testImageDicomData()
    val dd2 = TestUtil.testImageDicomData().setString(Tag.PatientName, "John^Doe")

    val image1 =
      PostAsUser("/api/images", HttpEntity(TestUtil.toBytes(dd1))) ~> routes ~> check {
        status should be(Created)
        responseAs[Image]
      }

    val image2 =
      PostAsUser("/api/images", HttpEntity(TestUtil.toBytes(dd2))) ~> routes ~> check {
        status should be(Created)
        responseAs[Image]
      }

    GetAsUser(s"/api/metadata/flatseries/${image1.seriesId}") ~> routes ~> check {
      status should be(OK)
      responseAs[FlatSeries]
    }

    GetAsUser(s"/api/metadata/flatseries/${image2.seriesId}") ~> routes ~> check {
      status should be(OK)
      responseAs[FlatSeries]
    }

    val anonImages =
      PostAsUser("/api/anonymization/anonymize", BulkAnonymizationData(profile, Seq(ImageTagValues(image1.id, Seq.empty), ImageTagValues(image2.id, Seq.empty)))) ~> routes ~> check {
        status should be(OK)
        responseAs[Seq[Image]]
      }

    anonImages should have length 2

    val anonFlatSeries1 =
      GetAsUser(s"/api/metadata/flatseries/${anonImages.head.seriesId}") ~> routes ~> check {
        status should be(OK)
        responseAs[FlatSeries]
      }

    val anonFlatSeries2 =
      GetAsUser(s"/api/metadata/flatseries/${anonImages(1).seriesId}") ~> routes ~> check {
        status should be(OK)
        responseAs[FlatSeries]
      }

    anonFlatSeries1.patient.patientName.value should not be "anon270"
    anonFlatSeries2.patient.patientName.value should not be "John^Doe"
  }

  it should "return 404 NotFound when manually anonymizing an image that does not exist" in {
    PutAsUser("/api/images/666/anonymize", AnonymizationData(profile, Seq.empty[TagValue])) ~> routes ~> check {
      status should be(NotFound)
    }
  }

  it should "provide a list of anonymization keys" in {
    val dicomData = TestUtil.createElements()
    val key1 = TestUtil.createAnonymizationKey(dicomData)
    val key2 = key1.copy(patientName = "pat name 2", anonPatientName = "anon pat name 2")
    val insertedKey1 = await(anonymizationDao.insertAnonymizationKey(key1))
    val insertedKey2 = await(anonymizationDao.insertAnonymizationKey(key2))
    GetAsUser("/api/anonymization/keys") ~> routes ~> check {
      status should be(OK)
      responseAs[List[AnonymizationKey]] should be(List(insertedKey1, insertedKey2))
    }
  }

  it should "return 200 OK and the requested anonymization key" in {
    val dicomData = TestUtil.createElements()
    val key1 = TestUtil.createAnonymizationKey(dicomData)
    val insertedKey1 = await(anonymizationDao.insertAnonymizationKey(key1))
    GetAsUser(s"/api/anonymization/keys/${insertedKey1.id}") ~> routes ~> check {
      status shouldBe OK
      responseAs[AnonymizationKey] shouldBe insertedKey1
    }
  }

  it should "return 404 NotFound when the requested anonymization key does not exist" in {
    GetAsUser("/api/anonymization/keys/666") ~> Route.seal(routes) ~> check {
      status shouldBe NotFound
    }
  }

  it should "provide a list of sorted anonymization keys supporting startindex and count" in {
    val dicomData = TestUtil.createElements(patientName = "B")
    val key1 = TestUtil.createAnonymizationKey(dicomData, anonPatientName = "anon B")
    val key2 = key1.copy(patientName = "A", anonPatientName = "anon A")
    await(anonymizationDao.insertAnonymizationKey(key1))
    val insertedKey2 = await(anonymizationDao.insertAnonymizationKey(key2))
    GetAsUser("/api/anonymization/keys?startindex=0&count=1&orderby=patientName&orderascending=true") ~> routes ~> check {
      status should be(OK)
      val keys = responseAs[List[AnonymizationKey]]
      keys.length should be(1)
      keys.head should be(insertedKey2)
    }
  }

  it should "return 400 Bad Request when sorting anonymization keys by a non-existing property" in {
    GetAsUser("/api/anonymization/keys?orderby=xyz") ~> routes ~> check {
      status should be(BadRequest)
    }
  }

  it should "return 200 OK and a list of stored attributes corresponding to the anonymization key with the supplied ID" in {
    val (dbPatient1, (_, _), (_, _, _, _), (dbImage1, _, _, _, _, _, _, _)) = await(TestUtil.insertMetaData(metaDataDao))
    val key1 = AnonymizationKey(-1, dbImage1.id, 1234, dbPatient1.patientName.value, "anonPN", dbPatient1.patientID.value, "anonPID", "", "", "", "", "", "")
    val insertedKey1 = await(anonymizationDao.insertAnonymizationKey(key1))
    val akv1 = AnonymizationKeyValue(-1, insertedKey1.id, TagPath.fromTag(Tag.FrameOfReferenceUID), "1.2.3.4", "5.2.9.0")
    val akv2 = AnonymizationKeyValue(-1, insertedKey1.id, TagPath.fromTag(Tag.PatientBirthDate), "20000101", "anon birth date")
    await(anonymizationDao.insertAnonymizationKeyValues(Seq(akv1, akv2)))
    val akValues =
      GetAsUser(s"/api/anonymization/keys/${insertedKey1.id}/keyvalues") ~> routes ~> check {
        status shouldBe OK
        responseAs[Seq[AnonymizationKeyValue]]
      }
    akValues should have length 2
    akValues.map(_.tagPath) shouldBe Seq(akv1, akv2).map(_.tagPath)
  }

  it should "return 200 OK and a list of anonymization keys when querying" in {
    val (dbPatient1, (_, _), (_, _, _, _), (dbImage1, _, _, _, _, _, _, _)) = await(TestUtil.insertMetaData(metaDataDao))
    val key1 = AnonymizationKey(-1, dbImage1.id, 1234, dbPatient1.patientName.value, "anonPN", dbPatient1.patientID.value, "anonPID", "", "", "", "", "", "")
    val insertedKey1 = await(anonymizationDao.insertAnonymizationKey(key1))

    val query = AnonymizationKeyQuery(0, 10, None, Seq(QueryProperty("anonPatientName", QueryOperator.EQUALS, insertedKey1.anonPatientName)))
    val keys =
      PostAsUser("/api/anonymization/keys/query", query) ~> routes ~> check {
        status shouldBe OK
        responseAs[List[AnonymizationKey]]
      }

    keys should have length 1
    keys.head shouldBe insertedKey1
  }

}