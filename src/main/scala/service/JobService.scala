package service

import models.{Process => CWLProcess}
import router.JobDto

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


trait JobService {

  def add(job: JobDto): Future[Option[CWLProcess]]

}

object JobService extends JobService {

  override def add(job: JobDto): Future[Option[CWLProcess]] = {
    println("Add job")
    Future(Some(CWLProcess(
      id = Some("123"),
      label = job.dockerImage,
      description = Some("Chronos job"),
      inputs = List(),
      outputs = List())))
    /*db.run { for {
      passwordId <- passwordDao.add(UserPassword newWithPassword user.password)
      userId <- userDao.add(populateUser(user).copy(passwordId = Some(passwordId)))
      // "This DBMS allows only a single AutoInc"
      // H2 doesn't allow return the whole user once created so we have to do this instead of returning the object from
      // the dao on inserting
      user <- UserDao.get(userId)
    } yield user */
  }

}
