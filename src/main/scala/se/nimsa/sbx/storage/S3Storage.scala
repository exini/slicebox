package se.nimsa.sbx.storage

import java.io.{ByteArrayOutputStream, InputStream}
import java.nio.file.{Files, Path}
import javax.imageio.ImageIO

import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.dicom.{DicomData, DicomUtil, ImageAttribute}
import se.nimsa.sbx.storage.StorageProtocol.ImageInformation

import scala.util.control.NonFatal

/**
  * Service that stores DICOM files on AWS S3.
  * @param s3Prefix prefix for keys
  * @param bucket S3 bucket
  */
class S3Storage(val bucket: String, val s3Prefix: String) extends StorageService {

  val s3Client = new S3Facade(bucket)

  def s3Id(image: Image) =
    s3Prefix + "/" + imageName(image)


  def storeDicomData(dicomData: DicomData, image: Image): Boolean = {
    val storedId = s3Id(image)
    val overwrite = s3Client.exists(storedId)
    try saveDicomDataToS3(dicomData, storedId) catch {
      case NonFatal(e) =>
        throw new IllegalArgumentException("Dicom data could not be stored", e)
    }
    overwrite
  }

  def saveDicomDataToS3(dicomData: DicomData, s3Key: String): Unit = {
    val os = new ByteArrayOutputStream()
    try saveDicomData(dicomData, os) catch {
      case NonFatal(e) =>
        throw new IllegalArgumentException("Dicom data could not be stored", e)
    }
    val buffer = os.toByteArray
    s3Client.upload(s3Key, buffer)
  }

  def storeEncapsulated(image: Image, dcmTempPath: Path): Unit = {
    s3Client.upload(s3Id(image),Files.readAllBytes(dcmTempPath))
    Files.delete(dcmTempPath)
  }

  def deleteFromStorage(image: Image): Unit = s3Client.delete(s3Id(image))

  def readDicomData(image: Image, withPixelData: Boolean, useBulkDataURI: Boolean): Option[DicomData] = {
    val s3InputStream = s3Client.get(s3Id(image))
    Some(loadDicomData(s3InputStream, withPixelData, useBulkDataURI))
  }

  def readImageAttributes(image: Image): Option[List[ImageAttribute]] = {
    val s3InputStream = s3Client.get(s3Id(image))
    Some(DicomUtil.readImageAttributes(loadDicomData(s3InputStream, withPixelData = false, useBulkDataURI = false).attributes))
  }

  def readImageInformation(image: Image): Option[ImageInformation] = {
    val s3InputStream = s3Client.get(s3Id(image))
    Some(super.readImageInformation(s3InputStream))
  }

  def readPngImageData(image: Image, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int): Option[Array[Byte]] = {
    val s3InputStream = s3Client.get(s3Id(image))
    val iis = ImageIO.createImageInputStream(s3InputStream)
    Some(super.readPngImageData(iis, frameNumber, windowMin, windowMax, imageHeight))
  }

  def readSecondaryCaptureJpeg(image: Image, imageHeight: Int): Option[Array[Byte]] = {
    val s3InputStream = s3Client.get(s3Id(image))
    Some(super.readSecondaryCaptureJpeg(s3InputStream, imageHeight))
  }

  def imageAsInputStream(image: Image): Option[InputStream] = {
    val s3InputStream = s3Client.get(s3Id(image))
    Some(s3InputStream)
  }

}
