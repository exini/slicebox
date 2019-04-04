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

package com.exini.sbx.seriestype

import akka.actor.{Actor, Props}
import akka.event.{Logging, LoggingReceive}
import akka.stream.Materializer
import akka.util.Timeout
import com.exini.sbx.dicom.DicomUtil
import com.exini.sbx.metadata.MetaDataProtocol._
import com.exini.sbx.seriestype.SeriesTypeProtocol._
import com.exini.sbx.storage.StorageService
import com.exini.sbx.util.ExceptionCatching
import com.exini.sbx.util.FutureUtil.await

class SeriesTypeServiceActor(seriesTypeDao: SeriesTypeDAO, storage: StorageService)(implicit materializer: Materializer, timeout: Timeout) extends Actor with ExceptionCatching {

  val log = Logging(context.system, this)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val seriesTypeUpdateService = context.actorOf(SeriesTypeUpdateActor.props(storage), name = "SeriesTypeUpdate")

  override def preStart {
    system.eventStream.subscribe(context.self, classOf[MetaDataDeleted])
  }

  log.info("Series type service started")

  def receive = LoggingReceive {

    case MetaDataDeleted(_, _, seriesIds, _) =>
        removeSeriesTypesFromSeries(seriesIds)
        sender ! SeriesTypesRemovedFromSeries(seriesIds)

    case msg: SeriesTypeRequest =>

      catchAndReport {

        msg match {

          case GetSeriesTypes(startIndex, count) =>
            val seriesTypes = getSeriesTypesFromDb(startIndex, count)
            sender ! SeriesTypes(seriesTypes)

          case AddSeriesType(seriesType) =>
            val dbSeriesType = addSeriesTypeToDb(seriesType)
            sender ! SeriesTypeAdded(dbSeriesType)

          case UpdateSeriesType(seriesType) =>
            updateSeriesTypeInDb(seriesType)
            sender ! SeriesTypeUpdated

          case GetSeriesType(seriesTypeId) =>
            sender ! getSeriesTypeForId(seriesTypeId)

          case RemoveSeriesType(seriesTypeId) =>
            removeSeriesTypeFromDb(seriesTypeId)
            updateSeriesTypesForAllSeries()
            sender ! SeriesTypeRemoved(seriesTypeId)

          case GetSeriesTypeRules(seriesTypeId) =>
            val seriesTypeRules = getSeriesTypeRulesFromDb(seriesTypeId)
            sender ! SeriesTypeRules(seriesTypeRules)

          case AddSeriesTypeRule(seriesTypeRule) =>
            val dbSeriesTypeRule = addSeriesTypeRuleToDb(seriesTypeRule)
            sender ! SeriesTypeRuleAdded(dbSeriesTypeRule)

          case RemoveSeriesTypeRule(seriesTypeRuleId) =>
            removeSeriesTypeRuleFromDb(seriesTypeRuleId)
            updateSeriesTypesForAllSeries()
            sender ! SeriesTypeRuleRemoved(seriesTypeRuleId)

          case GetSeriesTypeRuleAttributes(seriesTypeRuleId) =>
            val seriesTypeRuleAttributes = getSeriesTypeRuleAttributesFromDb(seriesTypeRuleId)
            sender ! SeriesTypeRuleAttributes(seriesTypeRuleAttributes)

          case AddSeriesTypeRuleAttribute(seriesTypeRuleAttribute) =>
            val dbSeriesTypeRuleAttribute = addSeriesTypeRuleAttributeToDb(seriesTypeRuleAttribute)
            updateSeriesTypesForAllSeries()
            sender ! SeriesTypeRuleAttributeAdded(dbSeriesTypeRuleAttribute)

          case RemoveSeriesTypeRuleAttribute(seriesTypeRuleAttributeId) =>
            removeSeriesTypeRuleAttributeFromDb(seriesTypeRuleAttributeId)
            updateSeriesTypesForAllSeries()
            sender ! SeriesTypeRuleAttributeRemoved(seriesTypeRuleAttributeId)

          case AddSeriesTypeToSeries(series, seriesType) =>
            val seriesSeriesType = addSeriesTypeToSeries(SeriesSeriesType(series.id, seriesType.id))
            sender ! SeriesTypeAddedToSeries(seriesSeriesType)

          case RemoveSeriesTypesFromSeries(seriesId) =>
            removeSeriesTypesFromSeries(Seq(seriesId))
            sender ! SeriesTypesRemovedFromSeries(Seq(seriesId))

          case RemoveSeriesTypeFromSeries(seriesId, seriesTypeId) =>
            removeSeriesTypeFromSeries(seriesId, seriesTypeId)
            sender ! SeriesTypeRemovedFromSeries(seriesId, seriesTypeId)

          case GetSeriesTypesForSeries(seriesId) =>
            val seriesTypes = getSeriesTypesForSeries(seriesId)
            sender ! SeriesTypes(seriesTypes)

          case GetSeriesTypesForListOfSeries(seriesIds) =>
            val seriesIdSeriesTypes = getSeriesTypesForListOfSeries(seriesIds)
            sender ! SeriesIdSeriesTypesResult(seriesIdSeriesTypes)

          case GetUpdateSeriesTypesRunningStatus =>
            seriesTypeUpdateService.forward(GetUpdateSeriesTypesRunningStatus)
        }
      }
  }

  def getSeriesTypesFromDb(startIndex: Long, count: Long): Seq[SeriesType] =
    await(seriesTypeDao.listSeriesTypes(startIndex, count))

  def addSeriesTypeToDb(seriesType: SeriesType): SeriesType = {
    await(seriesTypeDao.seriesTypeForName(seriesType.name)).foreach(_ =>
      throw new IllegalArgumentException(s"A series type with name ${seriesType.name} already exists"))
    await(seriesTypeDao.insertSeriesType(seriesType))
  }

  def updateSeriesTypeInDb(seriesType: SeriesType): Unit =
    await(seriesTypeDao.updateSeriesType(seriesType))

  def removeSeriesTypeFromDb(seriesTypeId: Long): Unit =
    await(seriesTypeDao.removeSeriesType(seriesTypeId))

  def getSeriesTypeRulesFromDb(seriesTypeId: Long): Seq[SeriesTypeRule] =
    await(seriesTypeDao.listSeriesTypeRulesForSeriesTypeId(seriesTypeId))

  def getSeriesTypeRuleAttributesFromDb(seriesTypeRuleId: Long): Seq[SeriesTypeRuleAttribute] =
    await(seriesTypeDao.listSeriesTypeRuleAttributesForSeriesTypeRuleId(seriesTypeRuleId))

  def addSeriesTypeRuleToDb(seriesTypeRule: SeriesTypeRule): SeriesTypeRule =
    await(seriesTypeDao.insertSeriesTypeRule(seriesTypeRule))

  def removeSeriesTypeRuleFromDb(seriesTypeRuleId: Long): Unit =
    await(seriesTypeDao.removeSeriesTypeRule(seriesTypeRuleId))

  def addSeriesTypeRuleAttributeToDb(seriesTypeRuleAttribute: SeriesTypeRuleAttribute): SeriesTypeRuleAttribute = {
    val updatedAttribute = if (seriesTypeRuleAttribute.name == null || seriesTypeRuleAttribute.name.isEmpty)
      seriesTypeRuleAttribute.copy(name = DicomUtil.nameForTag(seriesTypeRuleAttribute.tag))
    else
      seriesTypeRuleAttribute
    await(seriesTypeDao.insertSeriesTypeRuleAttribute(updatedAttribute))
  }

  def removeSeriesTypeRuleAttributeFromDb(seriesTypeRuleAttributeId: Long): Unit =
    await(seriesTypeDao.removeSeriesTypeRuleAttribute(seriesTypeRuleAttributeId))

  def getSeriesTypeForId(seriesTypeId: Long): Option[SeriesType] =
    await(seriesTypeDao.seriesTypeForId(seriesTypeId))

  def getSeriesTypesForSeries(seriesId: Long) =
    await(seriesTypeDao.seriesTypesForSeries(seriesId))

  def getSeriesTypesForListOfSeries(idsQuery: IdsQuery) =
    await(seriesTypeDao.seriesTypesForListOfSeries(idsQuery.ids))

  def addSeriesTypeToSeries(seriesSeriesType: SeriesSeriesType) =
    await(seriesTypeDao.upsertSeriesSeriesType(seriesSeriesType))

  def removeSeriesTypesFromSeries(seriesIds: Seq[Long]) =
    await(seriesTypeDao.removeSeriesTypesForSeriesIds(seriesIds))

  def removeSeriesTypeFromSeries(seriesId: Long, seriesTypeId: Long) =
    await(seriesTypeDao.removeSeriesTypeForSeriesId(seriesId, seriesTypeId))

  def updateSeriesTypesForAllSeries() =
    seriesTypeUpdateService ! UpdateSeriesTypesForAllSeries

}

object SeriesTypeServiceActor {
  def props(seriesTypeDao: SeriesTypeDAO, storage: StorageService)(implicit materializer: Materializer, timeout: Timeout): Props = Props(new SeriesTypeServiceActor(seriesTypeDao, storage))
}
