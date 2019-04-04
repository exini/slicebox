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

package com.exini.sbx.dicom.streams

import akka.NotUsed
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import com.exini.dicom.data.DicomParts._
import com.exini.dicom.data.padToEvenLength
import com.exini.dicom.streams.ModifyFlow.{TagInsertion, TagModificationsPart, modifyFlow}
import com.exini.sbx.dicom.SliceboxTags._
import com.exini.sbx.dicom.streams.DicomStreamUtil._

/**
  * A flow which performs reverse anonymization as soon as it has received an AnonymizationKeyOpResultPart
  */
object ReverseAnonymizationFlow {

  def reverseAnonFlow: Flow[DicomPart, DicomPart, NotUsed] = identityFlow
    .map {
      case rp: AnonymizationKeyOpResultPart =>
        val r = rp.result
        val active = valueTags
          .filterNot(_.level > r.matchLevel)
          .map(_.tagPath)
          .flatMap(tp => r.values.find(_.tagPath == tp))
        val insertions = active.map(tv => TagInsertion(tv.tagPath, _ => padToEvenLength(ByteString(tv.value), tv.tagPath.tag)))
        TagModificationsPart(Seq.empty, insertions.toList)
      case p => p
    }
    .via(modifyFlow())
}







