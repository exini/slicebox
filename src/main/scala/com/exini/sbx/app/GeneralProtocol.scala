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

package com.exini.sbx.app

object GeneralProtocol {

  case class SystemInformation(version: String)

  sealed trait SourceType {
    override def toString: String = this match {
      //SourceType string used as db key of type VARCHAR(64) so don't do over 64 chars...
      case SourceType.SCP => "scp"
      case SourceType.DIRECTORY => "directory"
      case SourceType.BOX => "box"
      case SourceType.USER => "user"
      case SourceType.IMPORT => "import"
      case _ => "unknown"
    }
  }

  object SourceType {
    case object SCP extends SourceType
    case object DIRECTORY extends SourceType
    case object BOX extends SourceType
    case object USER extends SourceType
    case object UNKNOWN extends SourceType
    case object IMPORT extends SourceType

    def withName(string: String): SourceType = string match {
      case "scp" => SCP
      case "directory" => DIRECTORY
      case "box" => BOX
      case "user" => USER
      case "import" => IMPORT
      case _ => UNKNOWN
    }    
  }
      
  sealed trait DestinationType {
    override def toString: String = this match {
      case DestinationType.SCU => "scu"
      case DestinationType.BOX => "box"
      case _ => "unknown"
    }
  }

  object DestinationType {
    case object SCU extends DestinationType
    case object BOX extends DestinationType
    case object UNKNOWN extends DestinationType

    def withName(string: String): DestinationType = string match {
      case "scu" => SCU
      case "box" => BOX
      case _ => UNKNOWN
    }    
  }
      
  case class Source(sourceType: SourceType, sourceName: String, sourceId: Long) {
    def toSourceRef: SourceRef = SourceRef(sourceType, sourceId)
  }
  
  case class SourceRef(sourceType: SourceType, sourceId: Long)

  case class SourceAdded(sourceRef: SourceRef)

  case class SourceDeleted(sourceRef: SourceRef)

  case class Destination(destinationType: DestinationType, destinationName: String, destinationId: Long)

  case class ImageAdded(imageId: Long, source: Source, overwrite: Boolean)

  case class ImagesDeleted(imageIds: Seq[Long])

  case class ImagesSent(destination: Destination, imageIds: Seq[Long])

  case class DicomDictionaryKeyword(keyword: String)

  case class DicomDictionaryTag(tag: Int)

  case class DicomDictionaryKeywords(keywords: List[String])

  case class DicomValueRepresentation(name: String, code: Int)
}
