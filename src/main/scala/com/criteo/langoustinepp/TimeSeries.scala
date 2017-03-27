package com.criteo.langoustinepp

import scala.annotation.tailrec
import scala.language.implicitConversions
import scala.concurrent.ExecutionContext
import scala.concurrent._
import scala.concurrent.duration.{Duration=> ScalaDuration}
import scala.concurrent.stm._
import scala.concurrent.stm.TMap._
import java.time._
import java.time.temporal.ChronoUnit._
import java.time.temporal._
import codes.reactive.scalatime._
import continuum.{Interval, IntervalSet}
import continuum.bound._
import scala.math.Ordering.Implicits._

sealed trait TimeSeriesConfig {
  def unit: ChronoUnit
}
case object Hourly extends TimeSeriesConfig {
  val unit = HOURS
}
case class Daily(tz: ZoneId) extends TimeSeriesConfig {
  val unit = WEEKS
}

case class TimeSeriesScheduling(config: TimeSeriesConfig) extends Scheduling {
  type Config = TimeSeriesConfig
  type Context = (LocalDateTime, LocalDateTime)
  type DependencyDescriptor = Duration
}

class TimeSeriesScheduler(val graph: Graph[TimeSeriesScheduling]) {
  implicit val dateTimeOrdering =
    Ordering.fromLessThan((t1: LocalDateTime, t2: LocalDateTime) => t1.isBefore(t2))

  type TimeSeriesJob = Job[TimeSeriesScheduling]

  type State = Map[Job[TimeSeriesScheduling], IntervalSet[LocalDateTime]]

  val state = TMap.empty[Job[TimeSeriesScheduling], IntervalSet[LocalDateTime]]

  val empty = IntervalSet.empty[LocalDateTime]
  val full = IntervalSet(Interval.full[LocalDateTime])

  def intervalFromContext(context: (LocalDateTime, LocalDateTime))
  : Interval[LocalDateTime] =
    Interval.closedOpen(context._1, context._2)

  def run()(implicit executor: ExecutionContext) = {
    val launched = LocalDateTime.now().minus(1, SECONDS)

    def addRuns(runs: Set[(TimeSeriesJob, TimeSeriesScheduling#Context, Future[Unit])])
    (implicit txn: InTxn) =
      runs.foreach { case (job, context, _) =>
        state.update(job, state.get(job).getOrElse(empty) + intervalFromContext(context))
      }

    @tailrec def go(running: Set[(TimeSeriesJob, (LocalDateTime, LocalDateTime) , Future[Unit])]): Unit = {
      val (done, stillRunning) = running.partition (_._3.isCompleted)
      val doneAndRunning = atomic { implicit txn =>
        addRuns(done)
        val runningIntervals = stillRunning.groupBy(_._1)
          .map { case (job, runs) => job -> runs.map(x => intervalFromContext(x._2)) }
        state.snapshot.map { case (job, is) =>
          job -> runningIntervals.getOrElse(job, List.empty).foldLeft(is)(_ + _)
        }
      }
      val rootAsATimer = (graph.roots.head ->
        IntervalSet(Interval.closedOpen(launched, LocalDateTime.now())))
      val toRun = next(doneAndRunning + rootAsATimer) // + runningIntervals
      val newRunning = stillRunning ++ toRun.map { case (job, context) =>
        (job, context, Future(Thread.sleep(3000))) }
      //FIXME: wait for the next hour
      try {
        val timeout = ScalaDuration.create(100, "ms")
        Await.ready(Future.firstCompletedOf(newRunning.map(_._3)), timeout)
      } catch {
        case e: TimeoutException => ()
      }
      go(newRunning)
    }
    go(Set.empty)
  }

  def split(start: LocalDateTime, end: LocalDateTime, tz: ZoneId, unit: ChronoUnit) = {
    val utc = ZoneId.of("UTC")
    val List(zonedStart, zonedEnd) = List(start, end).map { t =>
      t.atZone(utc).withZoneSameInstant(tz) }
    val truncatedStart = zonedStart.truncatedTo(unit)
    val alignedStart =
      if (truncatedStart == zonedStart) zonedStart
      else truncatedStart.plus(1, unit)
    val alignedEnd = zonedEnd.truncatedTo(unit)
    val periods = alignedStart.until(alignedEnd, unit)
    (1L to periods).map { i =>
      def alignedNth(k: Long) = alignedStart.plus(k, unit)
        .withZoneSameInstant(utc).toLocalDateTime
      (alignedNth(i-1), alignedNth(i))
    }
  }

  def next(state: State): List[(TimeSeriesJob, TimeSeriesScheduling#Context)] = {
    val init = state.map { case (k, intervalSet) => (k, intervalSet.complement) }
    val ready = graph.edges.foldLeft(init)
    { case (partial, (jobLeft, jobRight, offset)) =>
      partial + (jobLeft ->
        partial.getOrElse(jobLeft, full)
          .intersect(state.getOrElse(jobRight, empty).map(i => i.map(_.plus(offset)))))
    }
    val res = ready.filterNot { case (job, _) => graph.roots.contains(job) }
      .map { case (job, intervalSet) =>
        val contexts = intervalSet.toList.flatMap { interval =>
          val (unit, tz) = job.scheduling.config match {
            case Hourly => (Hourly.unit, ZoneId.of("UTC"))
            case Daily(tz) => (Daily(tz).unit, tz)
          }
          val Closed(start) = interval.lower.bound
          val Open(end) = interval.upper.bound
          split(start, end, tz, unit)
        }
        job -> contexts
      }
    res.toList.flatMap { case (job, l) => l.map (i => (job, i)) }
  }
}

object TimeSeriesScheduler extends Scheduler[TimeSeriesScheduling] {
  def run(graph: Graph[TimeSeriesScheduling])(implicit executor: ExecutionContext) =
    new TimeSeriesScheduler(graph).run()
}
