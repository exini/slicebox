package com.exini.sbx.user


import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import akka.util.Timeout
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}
import com.exini.sbx.user.UserProtocol._
import com.exini.sbx.util.FutureUtil.await
import com.exini.sbx.util.TestUtil

import scala.concurrent.duration.DurationInt

class UserServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("UserServiceActorTestSystem"))

  implicit val ec = system.dispatcher
  implicit val timeout = Timeout(30.seconds)

  val dbConfig = TestUtil.createTestDb("userserviceactortest")
  val dao = new UserDAO(dbConfig)

  await(dao.create())

  val userService = TestActorRef(new UserServiceActor(dao, "admin", "admin", 1000000))
  val userActor = userService.underlyingActor

  override def afterEach() = await(dao.clear())

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "A UserServiceActor" should {

    "cleanup expired sessions regularly" in {

      val user = await(dao.insert(ApiUser(-1, "user", UserRole.USER).withPassword("pass")))
      await(dao.insertSession(ApiSession(-1, user.id, "token1", "ip1", "user agent1", System.currentTimeMillis)))
      await(dao.insertSession(ApiSession(-1, user.id, "token2", "ip2", "user agent2", 0)))

      await(dao.listUsers(0, 10)) should have length 2
      await(dao.listSessions) should have length 2

      await(userActor.removeExpiredSessions())

      await(dao.listSessions) should have length 1
    }

    "refresh a non-expired session defined by a token, ip and user agent" in {
      val userAgent = "user agent"
      val userAgentHash = userActor.md5Hash(userAgent)

      val user = await(dao.insert(ApiUser(-1, "user", UserRole.USER).withPassword("pass")))
      val sessionTime = System.currentTimeMillis - 1000
      await(dao.insertSession(ApiSession(-1, user.id, "token", "ip", userAgentHash, sessionTime)))

      await(userActor.getAndRefreshUser(AuthKey(Some("token"), Some("ip"), Some(userAgent))))

      val optionalSession = await(dao.userSessionByTokenIpAndUserAgent("token", "ip", userAgentHash))
      optionalSession.isDefined shouldBe true
      optionalSession.get._2.updated shouldBe >(sessionTime)
    }

    "not refresh an expired session defined by a token, ip and user agent" in {
      val user = await(dao.insert(ApiUser(-1, "user", UserRole.USER).withPassword("pass")))
      val sessionTime = 1000
      await(dao.insertSession(ApiSession(-1, user.id, "token", "ip", "user agent", sessionTime)))

      await(userActor.getAndRefreshUser(AuthKey(Some("token"), Some("ip"), Some("user agent"))))

      val optionalSession = await(dao.userSessionByTokenIpAndUserAgent("token", "ip", "user agent"))
      optionalSession.isDefined shouldBe true
      optionalSession.get._2.updated shouldBe sessionTime
    }

    "create a session if none exists and update it if one exists" in {
      val user = await(dao.insert(ApiUser(-1, "user", UserRole.USER).withPassword("pass")))

      await(dao.listSessions) should have length 0

      val session1 = await(userActor.createOrUpdateSession(user, "ip", "userAgent"))
      await(dao.listSessions) should have length 1

      Thread.sleep(100)

      val session2 = await(userActor.createOrUpdateSession(user, "ip", "userAgent"))
      await(dao.listSessions) should have length 1
      session2.updated shouldBe >(session1.updated)
    }

    "remove a session based on user id, IP and user agent when logging out" in {
      val userAgent = "user agent"

      val user = await(dao.insert(ApiUser(-1, "user", UserRole.USER).withPassword("pass")))
      val session1 = await(userActor.createOrUpdateSession(user, "ip", userAgent))
      await(dao.listSessions) should have length 1
      await(userActor.deleteSession(user, AuthKey(Some(session1.token), Some("Other IP"), Some(userAgent))))
      await(dao.listSessions) should have length 1
      await(userActor.deleteSession(user, AuthKey(Some(session1.token), Some(session1.ip), Some(userAgent))))
      await(dao.listSessions) should have length 0
    }

    "not create more than one session when logging in twice" in {
      await(dao.insert(ApiUser(-1, "user", UserRole.USER).withPassword("pass")))

      userService ! Login(UserPass("user", "pass"), AuthKey(None, Some("ip"), Some("userAgent")))
      expectMsgType[LoggedIn]
      await(dao.listUsers(0, 10)) should have length 1
      await(dao.listSessions) should have length 1

      userService ! Login(UserPass("user", "pass"), AuthKey(None, Some("ip"), Some("userAgent")))
      expectMsgType[LoggedIn]
      await(dao.listUsers(0, 10)) should have length 1
      await(dao.listSessions) should have length 1
    }

    "not allow logging in if credentials are invalid" in {
      await(dao.insert(ApiUser(-1, "user", UserRole.USER).withPassword("pass")))
      userService ! Login(UserPass("user", "incorrect password"), AuthKey(None, Some("ip"), Some("userAgent")))
      expectMsg(LoginFailed)
    }

    "not allow logging in if information on IP address and/or user agent is missing" in {
      await(dao.insert(ApiUser(-1, "user", UserRole.USER).withPassword("pass")))

      userService ! Login(UserPass("user", "pass"), AuthKey(None, None, Some("userAgent")))
      expectMsg(LoginFailed)
      userService ! Login(UserPass("user", "pass"), AuthKey(None, Some("ip"), None))
      expectMsg(LoginFailed)
      userService ! Login(UserPass("user", "pass"), AuthKey(None, None, None))
      expectMsg(LoginFailed)
    }

  }

}