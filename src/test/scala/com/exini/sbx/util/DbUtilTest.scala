package com.exini.sbx.util

import org.scalatest.{AsyncFlatSpec, Matchers}

class DbUtilTest extends AsyncFlatSpec with Matchers {


  "The generic method for creating tables" should "create the specified tables" in {
    val dbConfig = TestUtil.createTestDb("dbutiltest")
    val db = dbConfig.db

    import dbConfig.profile.api._

    class TestTable1(tag: Tag) extends Table[(Int, String)](tag, TestTable1.name) {
      def i = column[Int]("i")
      def s = column[String]("s")
      def * = (i, s)
    }
    object TestTable1 {
      val name = "TestTable1"
    }
    val testQuery1 = TableQuery[TestTable1]

    class TestTable2(tag: Tag) extends Table[(Long, String)](tag, TestTable2.name) {
      def i = column[Long]("i", O.PrimaryKey, O.AutoInc)
      def s = column[String]("s")
      def fk = foreignKey("fk", s, testQuery1)(_.s, onDelete = ForeignKeyAction.Cascade)
      def * = (i, s)
    }
    object TestTable2 {
      val name = "TestTable2"
    }
    val testQuery2 = TableQuery[TestTable2]

    for {
      _ <- DbUtil.createTables(dbConfig, (TestTable1.name, testQuery1), (TestTable2.name, testQuery2))
      _ <- db.run(testQuery1 += ((99, "string")))
      r1 <- db.run(testQuery1.result)
      r2 <- db.run(testQuery2.result)
    } yield {
      r1 should have length 1
      r2 shouldBe empty
    }

  }
}
