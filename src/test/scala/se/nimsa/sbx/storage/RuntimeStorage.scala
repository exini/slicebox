package se.nimsa.sbx.storage

import java.io.{ByteArrayInputStream, InputStream}

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.dicom.{DicomData, DicomUtil, ImageAttribute}
import se.nimsa.sbx.storage.StorageProtocol.ImageInformation

import scala.concurrent.{ExecutionContext, Future}

class RuntimeStorage extends StorageService {

  import scala.collection.mutable

  val storage = mutable.Map.empty[String, ByteString]

  override def storeDicomData(dicomData: DicomData, image: Image): Boolean = {
    val overwrite = storage.contains(imageName(image))
    storage.put(imageName(image), ByteString(toByteArray(dicomData)))
    overwrite
  }

  override def deleteFromStorage(image: Image): Unit =
    storage.remove(imageName(image))

  override def readDicomData(image: Image, withPixelData: Boolean): DicomData =
    loadDicomData(storage.getOrElse(imageName(image), null).toArray, withPixelData)

  override def readImageAttributes(image: Image): List[ImageAttribute] =
    DicomUtil.readImageAttributes(loadDicomData(storage.getOrElse(imageName(image), null).toArray, withPixelData = false).attributes)

  override def readImageInformation(image: Image): ImageInformation =
    readImageInformation(imageAsInputStream(image))

  override def readPngImageData(image: Image, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int)
                               (implicit system: ActorSystem, materializer: Materializer): Array[Byte] = {
    val source = Source.single(storage(imageName(image)))
    readPngImageData(source, frameNumber, windowMin, windowMax, imageHeight)
  }

  override def imageAsInputStream(image: Image): InputStream =
    new ByteArrayInputStream(storage(imageName(image)).toArray)

  def clear() =
    storage.clear()

  override def move(sourceImageName: String, targetImageName: String) = {
    storage.get(sourceImageName).map { sourceBytes =>
      storage.remove(sourceImageName)
      storage(targetImageName) = sourceBytes
      Unit
    }.getOrElse {
      throw new RuntimeException(s"Dicom data not found for key $sourceImageName")
    }
  }

  override def fileSink(name: String)(implicit actorSystem: ActorSystem, mat: Materializer, ec: ExecutionContext): Sink[ByteString, Future[Done]] =
    Sink.reduce[ByteString](_ ++ _)
      .mapMaterializedValue {
        _.map {
          bytes =>
            storage(name) = bytes
            Done
        }
      }

  override def fileSource(image: Image)(implicit actorSystem: ActorSystem, mat: Materializer): Source[ByteString, NotUsed] = Source.single(storage(imageName(image)))

}
