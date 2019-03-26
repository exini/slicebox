/*
 * Copyright 2019 EXINI Diagnostics
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.exini.sbx.box

import akka.actor.{Actor, ActorSelection, ActorSystem, Cancellable, Props}
import akka.event.{Logging, LoggingReceive}
import akka.pattern.ask
import akka.stream._
import akka.stream.scaladsl.Compression
import akka.util.{ByteString, Timeout}
import com.exini.sbx.app.GeneralProtocol._
import com.exini.sbx.box.BoxProtocol._
import com.exini.sbx.dicom.Contexts
import com.exini.sbx.dicom.streams.DicomStreamOps
import com.exini.sbx.metadata.MetaDataProtocol
import com.exini.sbx.storage.StorageService

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

class BoxPollActor(override val box: Box,
                   val storage: StorageService,
                   override val retryInterval: FiniteDuration = 15.seconds,
                   override val batchSize: Int = 200,
                   override val parallelism: Int = 8,
                   boxServicePath: String = "../../BoxService",
                   metaDataServicePath: String = "../../MetaDataService",
                   anonymizationServicePath: String = "../../AnonymizationService",
                   filteringServicePath: String = "../../FilteringService")
                  (implicit val materializer: Materializer, timeout: Timeout) extends Actor with DicomStreamOps with BoxPollOps {

  val log = Logging(context.system, this)

  val metaDataService: ActorSelection = context.actorSelection(metaDataServicePath)
  val anonymizationService: ActorSelection = context.actorSelection(anonymizationServicePath)
  val boxService: ActorSelection = context.actorSelection(boxServicePath)
  val filteringService: ActorSelection = context.actorSelection(filteringServicePath)

  override implicit val system: ActorSystem = context.system
  override implicit val ec: ExecutionContext = context.dispatcher

  val switch: KillSwitch = pollAndTransfer(() => pullBatch()).run()

  override def postStop(): Unit =
    switch.shutdown()

  override def callAnonymizationService[R: ClassTag](message: Any): Future[R] = anonymizationService.ask(message).mapTo[R]
  override def callMetaDataService[R: ClassTag](message: Any): Future[R] = metaDataService.ask(message).mapTo[R]
  override def callFilteringService[R: ClassTag](message: Any): Future[R] = filteringService.ask(message).mapTo[R]
  override def scheduleTask(delay: FiniteDuration)(task: => Unit): Cancellable = system.scheduler.scheduleOnce(delay)(task)

  override def storeDicomData(bytesSource: scaladsl.Source[ByteString, _], source: Source): Future[MetaDataProtocol.MetaDataAdded] =
    storeDicomData(bytesSource.via(Compression.inflate()), source, storage, Contexts.extendedContexts, reverseAnonymization = true)

  override def updateIncoming(transactionImage: OutgoingTransactionImage, imageIdMaybe: Option[Long], added: Boolean): Future[IncomingUpdated] =
    boxService.ask(UpdateIncoming(box, transactionImage.transaction.id, transactionImage.image.sequenceNumber, transactionImage.transaction.totalImageCount, imageIdMaybe, added)).mapTo[IncomingUpdated]

  override def updateBoxOnlineStatus(online: Boolean): Future[Unit] = boxService.ask(UpdateBoxOnlineStatus(box.id, online)).mapTo[Unit]

  def receive = LoggingReceive {
    case _ =>
  }
}

object BoxPollActor {
  def props(box: Box, storage: StorageService)(implicit materializer: Materializer, timeout: Timeout): Props = Props(new BoxPollActor(box, storage))

}
