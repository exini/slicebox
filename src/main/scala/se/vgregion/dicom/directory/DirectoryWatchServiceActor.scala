package se.vgregion.dicom.directory

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.language.postfixOps

import akka.actor.Actor
import akka.actor.PoisonPill
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive

import se.vgregion.app.DbProps
import se.vgregion.dicom.DicomDispatchActor
import se.vgregion.dicom.DicomProtocol._
import se.vgregion.util.PerEventCreator

class DirectoryWatchServiceActor(dbProps: DbProps, storage: Path) extends Actor with PerEventCreator {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new DirectoryWatchDAO(dbProps.driver)

  setupDb()
  setupWatches()

  def receive = LoggingReceive {

    case msg: DirectoryRequest => msg match {

      case WatchDirectory(pathString) =>
        val path = Paths.get(pathString)
        val id = pathToId(path)
        context.child(id) match {
          case Some(actor) =>
            sender ! DirectoryWatched(path)
          case None =>
            if (!Files.isDirectory(path))
              throw new IllegalArgumentException("Could not create directory watch: Not a directory: " + id)

            addDirectory(path)

            context.actorOf(DirectoryWatchActor.props(path), id)

            sender ! DirectoryWatched(path)
        }

      case UnWatchDirectory(pathString) =>
        val path = Paths.get(pathString)
        val id = pathToId(path)
        context.child(id) match {
          case Some(ref) =>

            removeDirectory(path)

            ref ! PoisonPill

            sender ! DirectoryUnwatched(path)

          case None =>
            sender ! DirectoryUnwatched(path)
        }

      case GetWatchedDirectories =>
        val directories = getDirectories()
        sender ! WatchedDirectories(directories)

    }

    case msg: FileAddedToWatchedDirectory =>
      perEvent(DicomDispatchActor.props(self, null, storage, dbProps), msg)

  }

  def pathToId(path: Path) =
    path.toAbsolutePath().toString
      .replace("-", "--") // Replace '-' in directory names to avoid conflict with directory separator in next replace below
      .replace(FileSystems.getDefault.getSeparator, "-")

  def setupDb() =
    db.withSession { implicit session =>
      dao.create
    }

  def setupWatches() =
    db.withTransaction { implicit session =>
      val watchPaths = dao.list
      watchPaths foreach (path => context.actorOf(DirectoryWatchActor.props(path), pathToId(path)))
    }

  def addDirectory(path: Path) =
    db.withSession { implicit session =>
      dao.insert(path)
    }

  def removeDirectory(path: Path) =
    db.withSession { implicit session =>
      dao.remove(path)
    }

  def getDirectories() =
    db.withSession { implicit session =>
      dao.list
    }

}

object DirectoryWatchServiceActor {
  def props(dbProps: DbProps, storage: Path): Props = Props(new DirectoryWatchServiceActor(dbProps, storage))
}