package com.exini.sbx.storage

import java.util.concurrent.ConcurrentHashMap

import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.exini.sbx.lang.NotFoundException

import scala.concurrent.{ExecutionContext, Future}

class RuntimeStorage extends StorageService {

  val storage = new ConcurrentHashMap[String, ByteString]()

  override def deleteByName(names: Seq[String]): Unit = names.foreach(name => storage.remove(name))

  def clear(): Unit = storage.clear()

  override def move(sourceImageName: String, targetImageName: String): Unit =
    Option(storage.get(sourceImageName)).map { sourceBytes =>
      storage.remove(sourceImageName)
      storage.put(targetImageName, sourceBytes)
      Unit
    }.getOrElse {
      throw new RuntimeException(s"Dicom data not found for key $sourceImageName")
    }

  override def fileSink(name: String)(implicit executionContext: ExecutionContext): Sink[ByteString, Future[Done]] =
    Sink.reduce[ByteString](_ ++ _)
      .mapMaterializedValue {
        _.map {
          bytes =>
            storage.put(name, bytes)
            Done
        }
      }

  override def fileSource(name: String): Source[ByteString, NotUsed] =
    Source.single(Option(storage.get(name)).getOrElse(throw new NotFoundException(s"No data for name $name")))

}
