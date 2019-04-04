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

package com.exini.sbx.user

import java.security.MessageDigest
import java.util.UUID

import akka.actor.{Actor, Props, actorRef2Scala}
import akka.event.{Logging, LoggingReceive}
import akka.pattern.pipe
import akka.util.Timeout
import com.exini.sbx.user.UserProtocol._
import com.exini.sbx.user.UserServiceActor._
import com.exini.sbx.util.FutureUtil.await
import com.exini.sbx.util.SbxExtensions._

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class UserServiceActor(userDao: UserDAO, superUser: String, superPassword: String, sessionTimeout: Long)(implicit timeout: Timeout) extends Actor {
  val log = Logging(context.system, this)

  addSuperUser()

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val expiredSessionCleaner = system.scheduler.schedule(1.hour, 1.hour) {
    self ! RemoveExpiredSessions
  }

  log.info("User service started")

  def receive = LoggingReceive {

    case msg: UserRequest =>
      msg match {

        case Login(userPass, authKey) =>
          pipe(
            userByName(userPass.user).flatMap {
              case Some(user) if user.passwordMatches(userPass.pass) =>
                authKey.ip.flatMap(ip =>
                  authKey.userAgent.map { userAgent =>
                    createOrUpdateSession(user, ip, userAgent)
                      .map(session => LoggedIn(user, session))
                  }).getOrElse(Future.successful(LoginFailed))
              case _ =>
                Future.successful(LoginFailed)
            }
          ).to(sender)

        case Logout(apiUser, authKey) =>
          pipe(deleteSession(apiUser, authKey).map(_ => LoggedOut)).to(sender)

        case AddUser(apiUser) =>
          pipe(
            if (apiUser.role == UserRole.SUPERUSER)
              Future.failed(new IllegalArgumentException("Superusers may not be added"))
            else
              userDao
                .insert(apiUser)
                .recoverWith {
                  case e: Exception =>
                    userDao.userByName(apiUser.user).map {
                      case Some(user) => user
                      case None => throw e
                    }
                }
                .map(UserAdded)
          ).to(sender)

        case GetUserByName(user) =>
          pipe(userByName(user)).to(sender)

        case GetAndRefreshUserByAuthKey(authKey) =>
          pipe(getAndRefreshUser(authKey)).to(sender)

        case GetUsers(startIndex, count) =>
          pipe(listUsers(startIndex, count).map(Users)).to(sender)

        case DeleteUser(userId) =>
          pipe(deleteUser(userId).map(_ => UserDeleted(userId))).to(sender)

      }

    case RemoveExpiredSessions => removeExpiredSessions()

  }

  def addSuperUser(): Unit = {
    val superUsers = await(userDao.listUsers(0, 1000000)).filter(_.role == UserRole.SUPERUSER)
    if (superUsers.isEmpty || superUsers.head.user != superUser || !superUsers.head.passwordMatches(superPassword)) {
      superUsers.foreach(superUser => userDao.deleteUserByUserId(superUser.id))
      await(userDao.insert(ApiUser(-1, superUser, UserRole.SUPERUSER).withPassword(superPassword)))
    }
  }

  def userByName(name: String): Future[Option[ApiUser]] = userDao.userByName(name)

  def createOrUpdateSession(user: ApiUser, ip: String, userAgent: String): Future[ApiSession] = {
    val userAgentHash = md5Hash(userAgent)

    userDao.userSessionByUserIdIpAndUserAgent(user.id, ip, userAgentHash)
      .flatMap(_.map(apiSession => {
        val updatedSession = apiSession.copy(updated = currentTime)
        userDao.updateSession(updatedSession).map(_ => updatedSession)
      }).getOrElse(
        userDao.insertSession(ApiSession(-1, user.id, newSessionToken, ip, userAgentHash, currentTime))))
  }

  def deleteSession(user: ApiUser, authKey: AuthKey): Future[Option[Unit]] =
    authKey.ip.flatMap { ip =>
      authKey.userAgent.map { userAgent =>
        val userAgentHash = md5Hash(userAgent)

        userDao.deleteSessionByUserIdIpAndUserAgent(user.id, ip, userAgentHash)
          .map(n => if (n == 0) None else Some({}))
      }
    }.unwrap

  def getAndRefreshUser(authKey: AuthKey): Future[Option[ApiUser]] = {
    authKey.token.flatMap(token =>
      authKey.ip.flatMap(ip =>
        authKey.userAgent.map { userAgent =>
          val userAgentHash = md5Hash(userAgent)
          userDao.userSessionByTokenIpAndUserAgent(token, ip, userAgentHash)
        }))
      .unwrap
      .map(_.filter {
        case (_, apiSession) =>
          apiSession.updated > (currentTime - sessionTimeout)
      })
      .map(_.map {
        case (apiUser, apiSession) =>
          userDao.updateSession(apiSession.copy(updated = currentTime)).map(_ => apiUser)
      })
      .unwrap
  }

  def deleteUser(userId: Long): Future[Unit] = {
    userDao.userById(userId)
      .map {
        case Some(user) if user.role == UserRole.SUPERUSER =>
          throw new IllegalArgumentException("Superuser may not be deleted")
        case _ =>
      }
      .flatMap(_ => userDao.deleteUserByUserId(userId))
      .map(_ => {})
  }

  def removeExpiredSessions(): Future[Seq[Int]] =
    userDao.listSessions.flatMap(sessions => Future.sequence(
      sessions
        .filter(_.updated < currentTime - sessionTimeout)
        .map(expiredSession => userDao.deleteSessionById(expiredSession.id))
    ))

  def listUsers(startIndex: Long, count: Long): Future[Seq[ApiUser]] = userDao.listUsers(startIndex, count)

  def md5Hash(text: String): String = MessageDigest.getInstance("MD5")
    .digest(text.getBytes("utf-8"))
    .map(0xFF & _)
    .map("%02x".format(_))
    .foldLeft("")(_ + _)
}

object UserServiceActor {
  def props(dao: UserDAO, superUser: String, superPassword: String, sessionTimeout: Long)(implicit timeout: Timeout): Props = Props(new UserServiceActor(dao, superUser, superPassword, sessionTimeout))

  def newSessionToken = UUID.randomUUID.toString

  def currentTime = System.currentTimeMillis
}
