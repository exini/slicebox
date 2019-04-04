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

import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.{ContentDispositionTypes, `Content-Disposition`}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.stream.alpakka.csv.scaladsl.CsvFormatting
import akka.stream.scaladsl.{Sink, Source}
import com.exini.sbx.anonymization.AnonymizationProtocol._
import com.exini.sbx.anonymization.ConfidentialityOption
import com.exini.sbx.app.GeneralProtocol.{ImageAdded, ImagesDeleted}
import com.exini.sbx.app.SliceboxBase
import com.exini.sbx.user.UserProtocol.{ApiUser, UserRole}

trait AnonymizationRoutes {
  this: SliceboxBase =>

  def anonymizationRoutes(apiUser: ApiUser): Route =
    path("images" / LongNumber / "anonymize") { imageId =>
      put {
        entity(as[AnonymizationData]) { anonymizationData =>
          onSuccess(anonymizeData(imageId, anonymizationData.profile, anonymizationData.tagValues, storage)) {
            case Some(metaData) =>
              system.eventStream.publish(ImagesDeleted(Seq(imageId)))
              system.eventStream.publish(ImageAdded(metaData.image.id, metaData.source, !metaData.imageAdded))
              complete(metaData.image)
            case None =>
              complete((NotFound, s"No image meta data found for image id $imageId"))
          }
        }
      }
    } ~ path("images" / LongNumber / "anonymized") { imageId =>
      post {
        entity(as[AnonymizationData]) { anonymizationData =>
          val source = anonymizedDicomData(imageId, anonymizationData.profile, anonymizationData.tagValues, storage)
            .batchWeighted(storage.streamChunkSize, _.length, identity)(_ ++ _)
          complete(HttpEntity(ContentTypes.`application/octet-stream`, source))
        }
      }
    } ~ pathPrefix("anonymization") {
      path("anonymize") {
        post {
          entity(as[BulkAnonymizationData]) { bulkAnonymizationData =>
            complete {
              Source.fromIterator(() => bulkAnonymizationData.imageTagValuesSet.iterator)
                .mapAsyncUnordered(8) { imageTagValues =>
                  anonymizeData(imageTagValues.imageId, bulkAnonymizationData.completeProfile, imageTagValues.tagValues, storage)
                }
                .runWith(Sink.seq)
                .map { metaDataMaybes =>
                  system.eventStream.publish(ImagesDeleted(bulkAnonymizationData.imageTagValuesSet.map(_.imageId)))
                  metaDataMaybes.flatMap { metaDataMaybe =>
                    metaDataMaybe.map { metaData =>
                      system.eventStream.publish(ImageAdded(metaData.image.id, metaData.source, !metaData.imageAdded))
                      metaData.image
                    }
                  }
                }
            }
          }
        }
      } ~ pathPrefix("keys") {
        pathEndOrSingleSlash {
          get {
            parameters((
              'startindex.as[Long] ? 0,
              'count.as[Long] ? 20,
              'orderby.as[String].?,
              'orderascending.as[Boolean] ? true,
              'filter.as[String].?)) { (startIndex, count, orderBy, orderAscending, filter) =>
              onSuccess(anonymizationService.ask(GetAnonymizationKeys(startIndex, count, orderBy, orderAscending, filter))) {
                case AnonymizationKeys(anonymizationKeys) =>
                  complete(anonymizationKeys)
              }
            }
          }
        } ~ pathPrefix(LongNumber) { anonymizationKeyId =>
          pathEndOrSingleSlash {
            get {
              rejectEmptyResponse {
                complete(anonymizationService.ask(GetAnonymizationKey(anonymizationKeyId)).mapTo[Option[AnonymizationKey]])
              }
            } ~ delete {
              onSuccess(anonymizationService.ask(RemoveAnonymizationKey(anonymizationKeyId))) {
                case AnonymizationKeyRemoved(_) =>
                  complete(NoContent)
              }
            }
          } ~ path("keyvalues") {
            get {
              complete(anonymizationService.ask(GetTagValuesForAnonymizationKey(anonymizationKeyId)).mapTo[Seq[AnonymizationKeyValue]])
            }
          }
        } ~ path("query") {
          post {
            entity(as[AnonymizationKeyQuery]) { query =>
              complete(anonymizationService.ask(QueryAnonymizationKeys(query)).mapTo[Seq[AnonymizationKey]])
            }
          }
        } ~ path("export" / "csv") {
          get {
            authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
              val source =
                Source.single(List(
                  "ID", "Image ID", "Created",
                  "Patient Name", "Anonymous Patient Name",
                  "Patient ID", "Anonymous Patient ID",
                  "Study Instance UID", "Anonymous Study Instance UID",
                  "Series Instance UID", "Anonymous Series Instance UID",
                  "SOP Instance UID", "Anonymous SOP Instance UID",
                  "Tag Path", "Value", "Anonymized Value"))
                  .concat(
                    anonymizationDao.anonymizationKeyValueSource
                      .map {
                        case (key, value) =>
                          List(
                            key.id.toString, key.imageId.toString, key.created.toString,
                            key.patientName, key.anonPatientName,
                            key.patientID, key.anonPatientID,
                            key.studyInstanceUID, key.anonStudyInstanceUID,
                            key.seriesInstanceUID, key.anonSeriesInstanceUID,
                            key.sopInstanceUID, key.anonSOPInstanceUID,
                            value.tagPath.toString(), value.value, value.anonymizedValue)
                      })
                  .via(CsvFormatting.format(delimiter = CsvFormatting.SemiColon))

              respondWithHeader(`Content-Disposition`(ContentDispositionTypes.attachment, Map("filename" -> "slicebox-anonymization-info.csv"))) {
                complete(HttpResponse(entity = HttpEntity(`text/csv(UTF-8)`, source)))
              }
            }
          }
        }
      } ~ path("options") {
        complete(ConfidentialityOption.options.filter(_.supported).filterNot(_ == ConfidentialityOption.BASIC_PROFILE))
      }
    }

}
