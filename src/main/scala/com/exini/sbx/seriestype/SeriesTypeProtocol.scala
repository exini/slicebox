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

import com.exini.sbx.dicom.DicomHierarchy.Series

object SeriesTypeProtocol {

  import com.exini.sbx.model.Entity

  case class SeriesType(id: Long, name: String) extends Entity
  
  case class SeriesTypes(seriesTypes: Seq[SeriesType])
  
  case class SeriesTypeRule(id: Long, seriesTypeId: Long) extends Entity
  
  case class SeriesSeriesType(seriesId: Long, seriesTypeId: Long)

  case class SeriesIdSeriesTypesResult(seriesIdSeriesTypes: Seq[SeriesIdSeriesType])

  case class SeriesIdSeriesType(seriesId: Long, seriesType: SeriesType)

  case class IdsQuery(ids: Seq[Long])

  case class SeriesTypeRuleAttribute(
      id: Long,
      seriesTypeRuleId: Long,
      tag: Int,
      name: String,
      tagPath: Option[String],
      namePath: Option[String],
      values: String) extends Entity
  
  case class SeriesTypeRules(seriesTypeRules: Seq[SeriesTypeRule])
  
  case class SeriesTypeRuleAttributes(seriesTypeRuleAttributes: Seq[SeriesTypeRuleAttribute])

  sealed trait SeriesTypeRequest
  
  case class GetSeriesTypes(startIndex: Long, count: Long) extends SeriesTypeRequest
  
  case class AddSeriesType(seriesType: SeriesType) extends SeriesTypeRequest
  
  case class UpdateSeriesType(seriesType: SeriesType) extends SeriesTypeRequest

  case class GetSeriesType(seriesTypeId: Long) extends SeriesTypeRequest

  case class RemoveSeriesType(seriesTypeId: Long) extends SeriesTypeRequest
  

  case class SeriesTypeAdded(seriesType: SeriesType)
  
  case object SeriesTypeUpdated
  
  case class SeriesTypeRemoved(seriesTypeId: Long)
  
  
  case class GetSeriesTypeRules(seriesTypeId: Long) extends SeriesTypeRequest
  
  case class AddSeriesTypeRule(seriesTypeRule: SeriesTypeRule) extends SeriesTypeRequest
  
  case class RemoveSeriesTypeRule(seriesTypeRuleId: Long) extends SeriesTypeRequest
  
  case class SeriesTypeRuleAdded(seriesTypeRule: SeriesTypeRule)
  
  case class SeriesTypeRuleRemoved(seriesTypeRuleId: Long)

  
  case class GetSeriesTypeRuleAttributes(seriesTypeRuleId: Long) extends SeriesTypeRequest

  case class AddSeriesTypeRuleAttribute(seriesTypeRuleAttribute: SeriesTypeRuleAttribute) extends SeriesTypeRequest
  
  case class RemoveSeriesTypeRuleAttribute(seriesTypeRuleAttributeId: Long) extends SeriesTypeRequest
  
  case class SeriesTypeRuleAttributeAdded(seriesTypeRuleAttribute: SeriesTypeRuleAttribute)
  
  case class SeriesTypeRuleAttributeRemoved(seriesTypeRuleAttributeId: Long)
  
  
  case class AddSeriesTypeToSeries(series: Series, seriesType: SeriesType) extends SeriesTypeRequest

  case class RemoveSeriesTypesFromSeries(seriesId: Long) extends SeriesTypeRequest

  case class RemoveSeriesTypeFromSeries(seriesId: Long, seriesTypeId: Long) extends SeriesTypeRequest

  case class GetSeriesTypesForSeries(seriesId: Long) extends SeriesTypeRequest

  case class GetSeriesTypesForListOfSeries(idsQuery: IdsQuery) extends SeriesTypeRequest

  case class SeriesTypeAddedToSeries(seriesSeriesType: SeriesSeriesType)
  
  case class SeriesTypesRemovedFromSeries(seriesIds: Seq[Long])

  case class SeriesTypeRemovedFromSeries(seriesId: Long, seriesTypeId: Long)




  sealed trait SeriesTypesUpdateRequest
  
  case object UpdateSeriesTypesForAllSeries extends SeriesTypesUpdateRequest
    
  case object GetUpdateSeriesTypesRunningStatus extends SeriesTypesUpdateRequest with SeriesTypeRequest
  
  case class UpdateSeriesTypesRunningStatus(running: Boolean)


  case class MarkSeriesAsProcessed(seriesId: Long)

  case object PollSeriesTypesUpdateQueue

  case class AddToUpdateQueue(seriesIds: Seq[Long])

  case class UpdateSeriesTypesForSeries(seriesId: Long)


}
