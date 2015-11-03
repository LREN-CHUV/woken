package core.clients

import akka.actor.Status.Failure
import akka.actor._
import akka.util.Timeout
import core.Error
import core.clients.DatabaseService.{DatabaseWork, DatabaseResult}

import scala.collection.immutable.Queue
import scala.concurrent.duration.Duration
import scala.concurrent.{Promise, Future}
import scala.concurrent.duration._

// Implementation inspired by http://derekwyatt.org/2015/06/23/cancelling-work-in-flight/

/**
  * Wait for the database to reach a desired state, then return the result
  */
class ConditionalDatabaseResult[Work <: DatabaseWork, Result <: DatabaseResult](databaseService: ActorRef, concurrentLimit: Int = 5)(implicit factory: ActorRefFactory) {

  val workScheduler = factory.actorOf(WorkScheduler.props(concurrentLimit, databaseService))

  /**
    *
    * @param work The work to execute
    * @param condition The condition to fulfill in order to stop the work
    * @param timeout The timeout value. It will stop the work even if the condition has not being reached.
    * @return a Future holding the result or a failure if the timeout has been reached or the query to the database failed.
    */
  def waitForCondition(work: Work, condition: Result => Boolean)(implicit timeout: Timeout): Future[Result] = {
    val promise = Promise[Result]()
    val ref = factory.actorOf(WorkWaiter.props(work, workScheduler, promise, condition, timeout))
    promise.future
  }
}

object WorkScheduler {
  case class QueueWork[Work](work: Work, recipient: ActorRef)

  def props[Work <: DatabaseWork, Result <: DatabaseResult](concurrentLimit: Int, databaseService: ActorRef): Props =
    Props(classOf[WorkScheduler[Work, Result]], concurrentLimit, databaseService)
}

class WorkScheduler[Work <: DatabaseWork, Result <: DatabaseResult](concurrentLimit: Int, databaseService: ActorRef) extends Actor {
  import WorkScheduler._

  // No big deal, just start the worker
  def spawnWork(job: Job[Work]): ActorRef = context.watch(context.actorOf(Worker.props(job, databaseService)))

  // Rather than have internal `var`s, I've opted for closures
  def working(queue: Queue[Job[Work]], runningCount: Int): Receive = {
    // We haven't reached our maximum concurrency limit yet so we don't need to queue the work
    case QueueWork(work: Work @unchecked, recipient) if runningCount < concurrentLimit =>
      spawnWork(Job(work, recipient))
      context.become(working(queue, runningCount + 1))

    // We're already running at our maximum so we queue the work instead
    case QueueWork(work: Work @unchecked, recipient) =>
      context.become(working(queue.enqueue(Job(work, recipient)), runningCount))

    // One of our workers has completed, so we can (potentially) get something else running
    case Terminated(ref) =>
      queue.dequeueOption match {
        case Some((job, q)) =>
          spawnWork(job)
          context.become(working(q, runningCount))
        case None =>
          context.become(working(queue, runningCount - 1))
      }
  }

  def receive = working(Queue.empty, 0)
}

case class Job[Work](work: Work, recipient: ActorRef)

object Worker {
  case object Proceed
  case object ForgetIt

  def props[Work <: DatabaseWork, Result <: DatabaseResult](job: Job[Work], databaseService: ActorRef): Props =
    Props(classOf[Worker[Work, Result]], job, databaseService)
}

class Worker[Work <: DatabaseWork, Result <: DatabaseResult](job: Job[Work], databaseService: ActorRef) extends Actor with ActorLogging {
  import context.dispatcher // implicit EC
  import Worker._
  import WorkWaiter._
  import akka.pattern.{ask, pipe}

  implicit val _timeout = Timeout(5.seconds)

  // First we need to understand whether or not we should even do this.
  // In every case, we're going to get an answer, which will ensure that this
  // Actor terminates, assuming the thunk terminates.
  job.recipient.ask(AreYouStillInterested).map {
    case IAmStillInterested =>
      Proceed
    case _ =>
      ForgetIt
  } recover {
    case t: Throwable =>
      ForgetIt
  } pipeTo self

  final def receive = {
    // The recipient is still interested, so let's do it
    case Proceed => databaseService ! job.work

    // For whatever reason, we're going to be doing this work for no reason,
    // so let's not do it.
    case ForgetIt =>
      context.stop(self)

    case result: Result =>
      job.recipient ! WorkComplete(result)
      context.stop(self)

    case Failure(t) =>
      log.error(t, "Database error")
      job.recipient ! Error(t.toString)
      context.stop(self)

    case e: Timeout =>
      job.recipient ! Error("Timeout while connecting to Chronos")
      context.stop(self)

    case e => log.error(s"Unhandled message: $e")

  }
}

object WorkWaiter {

  // Requests
  case class WorkTimeoutException[Work](timeout: Timeout, work: Work) extends Exception
  case class WorkComplete[Result](result: Result)
  case object AreYouStillInterested

  // Results
  case object IAmStillInterested
  case object IAmNoLongerInterested

  def props[Work, Result](work: Work, workScheduler: ActorRef, workResult: Promise[Result], condition: Result => Boolean, timeout: Timeout): Props =
    Props(classOf[WorkWaiter[Work, Result]], work, workScheduler, workResult, condition, timeout)
}

class WorkWaiter[Work, Result](work: Work, workScheduler: ActorRef, workResult: Promise[Result], condition: Result => Boolean, timeout: Timeout) extends Actor {

  import WorkWaiter._
  import WorkScheduler.QueueWork

  var timeoutSchedule: Cancellable = _
  var retrySchedule: Cancellable = _

  // Set the timeout to ensure that we can (potentially) fail the promise appropriately
  // Schedule the work with the work scheduler
  override def preStart(): Unit = {
    super.preStart()
    context.setReceiveTimeout(timeout.duration)
    // As we keep sending refresh messages, we need to send the ReceiveTimeout message even if there was some activity on this actor
    timeoutSchedule = context.system.scheduler.scheduleOnce(timeout.duration, self, ReceiveTimeout)
    retrySchedule = context.system.scheduler.schedule(100.milliseconds, 200.milliseconds, workScheduler, QueueWork(work, self))
  }

  override def postStop(): Unit = {
    timeoutSchedule.cancel()
    retrySchedule.cancel()
    super.postStop()
  }

  def interested: Receive = {
    // We were interested, but didn't get the answer in time, so we're not
    // interested any more.
    case ReceiveTimeout =>
      // We don't need another ReceiveTimeout
      context.setReceiveTimeout(Duration.Undefined)
      // Fail the result, cuz it's just too late
      workResult.failure(WorkTimeoutException(timeout, work))
      context.become(notInterested)

    // Great! We got the result in time and the condition fulfilled so we can produce the happy ending
    case WorkComplete(result: Result @unchecked) =>
      if (condition(result)) {
        workResult.success(result)
        context.stop(self)
      }
      // otherwise wait for new work complete messages initiated by the retrySchedule

    // Since we're in the `interested` state, we're definitely interested
    case AreYouStillInterested =>
      sender ! IAmStillInterested
  }

  def notInterested: Receive = {
    // Too bad. We were interested, but the work didn't finish in time so
    // the only reasonable thing we can do is discard this
    case _: WorkComplete[_] =>

    // Nope, we're not interested so don't bother starting
    case AreYouStillInterested =>
      sender ! IAmNoLongerInterested
      context.stop(self)
  }

  def receive = interested
}
