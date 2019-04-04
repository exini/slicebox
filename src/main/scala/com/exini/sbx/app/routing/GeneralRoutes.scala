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

package com.exini.sbx.app.routing

import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import akka.actor.PoisonPill
import akka.pattern.ask
import akka.pattern.gracefulStop
import com.exini.sbx.app.GeneralProtocol._
import com.exini.sbx.app.SliceboxBase
import com.exini.sbx.box.BoxProtocol.Boxes
import com.exini.sbx.box.BoxProtocol.GetBoxes
import com.exini.sbx.directory.DirectoryWatchProtocol.GetWatchedDirectories
import com.exini.sbx.directory.DirectoryWatchProtocol.WatchedDirectories
import com.exini.sbx.importing.ImportProtocol.{GetImportSessions, ImportSessions}
import com.exini.sbx.scp.ScpProtocol._
import com.exini.sbx.scu.ScuProtocol._
import com.exini.sbx.user.UserProtocol._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.exini.dicom.data.{Dictionary, VR}

trait GeneralRoutes {
  this: SliceboxBase =>

  def generalRoutes(apiUser: ApiUser): Route =
    pathPrefix("system") {
      path("stop") {
        post {
          authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
            complete {
              val stop =
                gracefulStop(forwardingService, 5.seconds, PoisonPill) andThen { case _ => gracefulStop(importService, 5.seconds, PoisonPill) } andThen { case _ => gracefulStop(directoryService, 5.seconds, PoisonPill) } andThen { case _ => gracefulStop(scpService, 5.seconds, PoisonPill) } andThen { case _ => gracefulStop(scuService, 5.seconds, PoisonPill) } andThen { case _ => gracefulStop(seriesTypeService, 5.seconds, PoisonPill) } andThen { case _ => gracefulStop(logService, 5.seconds, PoisonPill) } andThen { case _ => gracefulStop(storageService, 5.seconds, PoisonPill) } andThen { case _ => gracefulStop(metaDataService, 5.seconds, PoisonPill) } andThen { case _ => gracefulStop(boxService, 5.seconds, PoisonPill) } andThen { case _ => gracefulStop(anonymizationService, 5.seconds, PoisonPill) } andThen { case _ => gracefulStop(userService, 5.seconds, PoisonPill) }
              Await.ready(stop, 5.seconds)

              blockingIoContext.shutdown()
              if (!blockingIoContext.awaitTermination(5, TimeUnit.SECONDS)) {
                blockingIoContext.shutdownNow //try to drop tasks
              }

              system.scheduler.scheduleOnce(1.second)(system.terminate())
              "Shutting down in 1 second..."
            }
          }
        }
      }
    } ~ path("sources") {
      get {
        def futureSources =
          for {
            users <- userService.ask(GetUsers(0, 1000000)).mapTo[Users]
            boxes <- boxService.ask(GetBoxes(0, 1000000)).mapTo[Boxes]
            scps <- scpService.ask(GetScps(0, 1000000)).mapTo[Scps]
            dirs <- directoryService.ask(GetWatchedDirectories(0, 1000000)).mapTo[WatchedDirectories]
            imports <- importService.ask(GetImportSessions(0, 1000000)).mapTo[ImportSessions]
          } yield {
            users.users.map(user => Source(SourceType.USER, user.user, user.id)) ++
              boxes.boxes.map(box => Source(SourceType.BOX, box.name, box.id)) ++
              scps.scps.map(scp => Source(SourceType.SCP, scp.name, scp.id)) ++
              dirs.directories.map(dir => Source(SourceType.DIRECTORY, dir.name, dir.id)) ++
              imports.importSessions.map(importSession => Source(SourceType.IMPORT, importSession.name, importSession.id))
          }

        onSuccess(futureSources) {
          complete(_)
        }
      }
    } ~ path("destinations") {
      get {
        def futureDestinations =
          for {
            boxes <- boxService.ask(GetBoxes(0, 1000000)).mapTo[Boxes]
            scus <- scuService.ask(GetScus(0, 1000000)).mapTo[Scus]
          } yield {
            boxes.boxes.map(box => Destination(DestinationType.BOX, box.name, box.id)) ++
              scus.scus.map(scu => Destination(DestinationType.SCU, scu.name, scu.id))
          }

        onSuccess(futureDestinations) {
          complete(_)
        }
      }
    }

  private[this] lazy val keywords = Dictionary.keywords()
  private[this] lazy val (sequenceKeywords, nonSequenceKeywords) =
    Dictionary.keywords().partition(w => Dictionary.vrOf(Dictionary.tagOf(w)) == VR.SQ)

  private[this] def filteredKeywords(filterMaybe: Option[String], keywords: List[String]): DicomDictionaryKeywords =
    filterMaybe
      .map(filter => DicomDictionaryKeywords(keywords.filter(_.toLowerCase.contains(filter.toLowerCase))))
      .getOrElse(DicomDictionaryKeywords(keywords))

  def publicSystemRoutes: Route =
    pathPrefix("system") {
      path("health") {
        complete(OK)
      } ~ path("information") {
        complete(systemInformation)
      }
    } ~ pathPrefix("dicom" / "dictionary") {
      get {
        pathPrefix("keywords") {
          parameter('filter.?) { filterMaybe =>
            pathEndOrSingleSlash {
              complete(filteredKeywords(filterMaybe, keywords))
            } ~ path("sequence") {
              complete(filteredKeywords(filterMaybe, sequenceKeywords))
            } ~ path("nonsequence") {
              complete(filteredKeywords(filterMaybe, nonSequenceKeywords))
            }
          }
        } ~ parameter('tag.as[Int]) { tag =>
          path("vr") {
            val vr = Dictionary.vrOf(tag)
            complete(DicomValueRepresentation(vr.toString, vr.code))
          } ~ path("vm") {
            complete(Dictionary.vmOf(tag))
          } ~ path("keyword") {
            val keyword = Dictionary.keywordOf(tag)
            if (keyword.isEmpty)
              complete(NotFound)
            else
              complete(DicomDictionaryKeyword(keyword))
          }
        } ~ parameter('keyword) { keyword =>
          path("tag") {
            try {
              val tag = Dictionary.tagOf(keyword)
              complete(DicomDictionaryTag(tag))
            } catch {
              case _: IllegalArgumentException =>
                complete(NotFound)
            }
          }
        }
      }
    }
}
