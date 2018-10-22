/*
 * Copyright 2014 Lars Edenbrandt
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

package se.nimsa.sbx.dicom.streams

import akka.NotUsed
import akka.stream.FlowShape
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge}
import se.nimsa.dicom.data.DicomParts.{DicomPart, MetaPart}
import se.nimsa.dicom.data._
import se.nimsa.sbx.anonymization.AnonymizationProtocol.{AnonymizationKeyOpResult, TagValue}

object DicomStreamUtil {

  case class AnonymizationKeyOpResultPart(result: AnonymizationKeyOpResult) extends MetaPart

  val identityFlow: Flow[DicomPart, DicomPart, NotUsed] = Flow[DicomPart]

  def isAnonymous(elements: Elements): Boolean = elements.getString(Tag.PatientIdentityRemoved).exists(_.toUpperCase == "YES")

  def elementsContainTagValues(elements: Elements, tagValues: Seq[TagValue]): Boolean = tagValues
    .forall(tv => tv.value.isEmpty || elements.getString(tv.tagPath).forall(_ == tv.value))

  def conditionalFlow(goA: PartialFunction[DicomPart, Boolean], flowA: Flow[DicomPart, DicomPart, _], flowB: Flow[DicomPart, DicomPart, _], routeADefault: Boolean = true): Flow[DicomPart, DicomPart, NotUsed] =
    Flow.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      var routeA = routeADefault // determines which path is taken in the graph

      // if any part indicates that alternate route should be taken, remember this
      val gateFlow = builder.add {
        identityFlow
          .map { part =>
            if (goA.isDefinedAt(part)) routeA = goA(part)
            part
          }
      }

      // split the flow
      val bcast = builder.add(Broadcast[DicomPart](2))

      // define gates for each path, only one path is used
      val gateA = identityFlow.filter(_ => routeA)
      val gateB = identityFlow.filterNot(_ => routeA)

      // merge the two paths
      val merge = builder.add(Merge[DicomPart](2))

      // surround each flow by gates, remember that flows may produce items without input
      gateFlow ~> bcast ~> gateA ~> flowA ~> gateA ~> merge
      bcast ~> gateB ~> flowB ~> gateB ~> merge

      FlowShape(gateFlow.in, merge.out)
    })

}
