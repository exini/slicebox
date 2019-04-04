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

package com.exini.sbx.scp

import com.exini.sbx.scp.ScpProtocol.ScpData
import com.exini.sbx.util.DbUtil._
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

class ScpDAO(val dbConf: DatabaseConfig[JdbcProfile])(implicit ec: ExecutionContext) {

  import dbConf.profile.api._

  val db = dbConf.db

  class ScpDataTable(tag: Tag) extends Table[ScpData](tag, ScpDataTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def aeTitle = column[String]("aeTitle")
    def port = column[Int]("port")
    def * = (id, name, aeTitle, port) <> (ScpData.tupled, ScpData.unapply)
  }

  object ScpDataTable {
    val name = "ScpData"
  }

  val scpDatas = TableQuery[ScpDataTable]

  def create() = createTables(dbConf, (ScpDataTable.name, scpDatas))

  def drop() = db.run {
    scpDatas.schema.drop
  }

  def clear() = db.run {
    scpDatas.delete
  }


  def insert(scpData: ScpData): Future[ScpData] = db.run {
    scpDatas returning scpDatas.map(_.id) += scpData
  }.map(generatedId => scpData.copy(id = generatedId))

  def deleteScpDataWithId(scpDataId: Long): Future[Int] = db.run {
    scpDatas
      .filter(_.id === scpDataId)
      .delete
  }

  def scpDataForId(id: Long): Future[Option[ScpData]] = db.run {
    scpDatas.filter(_.id === id).result.headOption
  }

  def scpDataForName(name: String): Future[Option[ScpData]] = db.run {
    scpDatas.filter(_.name === name).result.headOption
  }

  def scpDataForPort(port: Int): Future[Option[ScpData]] = db.run {
    scpDatas.filter(_.port === port).result.headOption
  }

  def listScpDatas(startIndex: Long, count: Long): Future[Seq[ScpData]] = db.run {
    scpDatas
      .drop(startIndex)
      .take(count)
      .result
  }
}
