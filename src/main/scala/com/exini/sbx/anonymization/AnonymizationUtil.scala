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

package com.exini.sbx.anonymization

import akka.util.ByteString
import com.exini.dicom.data.{VR, _}
import com.exini.sbx.dicom.DicomUtil.toAsciiBytes

import scala.util.Random

object AnonymizationUtil {

  def createAnonymousPatientName(sex: Option[String], age: Option[String]): String = {
    val sexString = sex.filter(_.nonEmpty).getOrElse("<unknown sex>")
    val ageString = age.filter(_.nonEmpty).getOrElse("<unknown age>")
    s"Anonymous $sexString $ageString"
  }

  def createAccessionNumber(): ByteString = {
    val rand = new Random()
    val newNumber = (1 to 16).foldLeft("")((s, _) => s + rand.nextInt(10).toString)
    toAsciiBytes(newNumber, VR.SH)
  }

  def createUid(): ByteString = toAsciiBytes(createUID(), VR.UI)
}
