/*
 * Copyright 2016 Combined Conditional Access Development, LLC.
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
package com.ccadllc.cedi.circuitbreaker

import fs2.{ Scheduler, Strategy, Task }
import fs2.util.Async
import fs2.util.syntax._

import java.util.concurrent.Executors

import scala.collection.immutable.Vector
import scala.concurrent.duration._
import scala.util.Random

import org.scalatest.{ Matchers, Suite, WordSpecLike }

import CircuitBreaker._

import statistics._

trait TestSupport extends WordSpecLike with Matchers {
  self: Suite =>

  private val GroupTasksBy = 10

  private val testExecutor = Executors.newCachedThreadPool

  implicit val testStrategy: Strategy = Strategy.fromExecutor(testExecutor)

  implicit val scheduler: Scheduler = Scheduler.fromScheduledExecutorService(Executors.newSingleThreadScheduledExecutor)

  val testRegistryConfig: RegistrySettings = RegistrySettings(RegistrySettings.GarbageCollection(5.minutes, 60.minutes))

  val testRequestSamples: Int = 200
  val testDegradationThreshold: Percentage = Percentage(50.0)
  val testTestingConfig: FailureSettings.Test = FailureSettings.Test(2.seconds, 10)
  val testFailureConfig: FailureSettings = FailureSettings(SampleWindow(50.milliseconds), testDegradationThreshold, testTestingConfig)
  val testFlowControlConfig: FlowControlSettings = FlowControlSettings(
    testFailureConfig, SampleWindow(4.seconds), Percentage.minimum, Percentage.minimum, 500
  )

  class TestStreamedEventObserver {
    private case class State(
      openedEvents: Map[Identifier, Vector[OpenedEvent]] = Map.empty,
      closedEvents: Map[Identifier, Vector[ClosedEvent]] = Map.empty,
      throttledUpEvents: Map[Identifier, Vector[ThrottledUpEvent]] = Map.empty,
      throttledDownEvents: Map[Identifier, Vector[ThrottledDownEvent]] = Map.empty
    )
    private val state = StateRef.create[Task, State](State()).unsafeRun

    def openedEvents(id: Identifier): Vector[OpenedEvent] =
      state.get.map { _.openedEvents.getOrElse(id, Vector.empty) }.unsafeRun
    def openedCount(id: Identifier): Int = openedEvents(id).size
    def closedEvents(id: Identifier): Vector[ClosedEvent] =
      state.get.map { _.closedEvents.getOrElse(id, Vector.empty) }.unsafeRun
    def closedCount(id: Identifier): Int = closedEvents(id).size
    def throttledUpEvents(id: Identifier): Vector[ThrottledUpEvent] =
      state.get.map { _.throttledUpEvents.getOrElse(id, Vector.empty) }.unsafeRun
    def throttledUpCount(id: Identifier): Int = throttledUpEvents(id).size
    def throttledDownEvents(id: Identifier): Vector[ThrottledDownEvent] =
      state.get.map { _.throttledDownEvents.getOrElse(id, Vector.empty) }.unsafeRun
    def throttledDownCount(id: Identifier): Int = throttledDownEvents(id).size

    def opened(id: Identifier, stats: FailureStatistics): Task[Unit] = state.modify { s =>
      s.copy(openedEvents = addEventToMap(id, OpenedEvent(id, stats), s.openedEvents))
    } map { _ => () }
    def closed(id: Identifier, stats: FailureStatistics): Task[Unit] = state.modify { s =>
      s.copy(closedEvents = addEventToMap(id, ClosedEvent(id, stats), s.closedEvents))
    } map { _ => () }
    def throttledUp(id: Identifier, stats: FlowControlStatistics): Task[Unit] = state.modify { s =>
      s.copy(throttledUpEvents = addEventToMap(id, ThrottledUpEvent(id, stats), s.throttledUpEvents))
    } map { _ => () }
    def throttledDown(id: Identifier, stats: FlowControlStatistics): Task[Unit] = state.modify { s =>
      s.copy(throttledDownEvents = addEventToMap(id, ThrottledDownEvent(id, stats), s.throttledDownEvents))
    } map { _ => () }

    private def addEventToMap[A](
      id: Identifier, event: A, eventMap: Map[Identifier, Vector[A]]
    ): Map[Identifier, Vector[A]] =
      eventMap + (id -> (eventMap.getOrElse(id, Vector.empty) :+ event))
  }

  object TestStreamedEventObserver {
    private val EventsQueuedLimit = 56
    def create(registry: CircuitBreakerRegistry[Task]): TestStreamedEventObserver = {
      val tseo = new TestStreamedEventObserver
      registry.events(EventsQueuedLimit).evalMap {
        case OpenedEvent(id, stats) => tseo.opened(id, stats)
        case ClosedEvent(id, stats) => tseo.closed(id, stats)
        case ThrottledUpEvent(id, stats) => tseo.throttledUp(id, stats)
        case ThrottledDownEvent(id, stats) => tseo.throttledDown(id, stats)
      }.drain.run.unsafeRunAsync { _ => () }
      tseo
    }
  }

  class TestStreamedStatisticsObserver {
    private case class State(
      failureStatistics: Map[Identifier, Vector[FailureStatistics]] = Map.empty,
      flowControlStatistics: Map[Identifier, Vector[FlowControlStatistics]] = Map.empty
    )
    private val state = StateRef.create[Task, State](State()).unsafeRun

    def failureStatistics(id: Identifier): Vector[FailureStatistics] =
      state.get.map { _.failureStatistics.getOrElse(id, Vector.empty) }.unsafeRun

    def failureStatisticsCount(id: Identifier): Int = failureStatistics(id).size

    def flowControlStatistics(id: Identifier): Vector[FlowControlStatistics] =
      state.get.map { _.flowControlStatistics.getOrElse(id, Vector.empty) }.unsafeRun

    def flowControlStatisticsCount(id: Identifier): Int = flowControlStatistics(id).size

    def add(stats: FailureStatistics): Task[Unit] = state.modify { s =>
      s.copy(failureStatistics = addStatisticsToMap(stats.id, stats, s.failureStatistics))
    } map { _ => () }

    def add(stats: FlowControlStatistics): Task[Unit] = state.modify { s =>
      s.copy(flowControlStatistics = addStatisticsToMap(stats.id, stats, s.flowControlStatistics))
    } map { _ => () }

    private def addStatisticsToMap[A](id: Identifier, stats: A, statsMap: Map[Identifier, Vector[A]]): Map[Identifier, Vector[A]] =
      statsMap + (id -> (statsMap.getOrElse(id, Vector.empty) :+ stats))
  }

  object TestStreamedStatisticsObserver {
    def create(registry: CircuitBreakerRegistry[Task], checkInterval: FiniteDuration): TestStreamedStatisticsObserver = {
      val tsso = new TestStreamedStatisticsObserver
      registry.statistics(checkInterval).evalMap {
        case fs @ FailureStatistics(_, _, _, _, _, _) => tsso.add(fs)
        case fcs @ FlowControlStatistics(_, _, _, _) => tsso.add(fcs)
      }.drain.run.unsafeRunAsync { _ => () }
      tsso
    }
  }

  /**
   * Executes tasks protected with a failure circuit breaker with a task failure
   * rate set-up to occur based on the input parameter. Failure related related
   * tests execute this method with different input parameters to ensure opened and
   * flowed events and exceptions are occurring as expected in the circuitbreaker
   * protecting the tasks.
   */
  def protectFailure(cb: CircuitBreaker[Task], failureRate: Percentage): Vector[Option[Throwable]] = {
    val groupedTasks = if (testRequestSamples < GroupTasksBy) testRequestSamples else GroupTasksBy
    val failedTasks = if (failureRate === Percentage.minimum) Vector.empty[Task[Unit]] else {
      (1 to (testRequestSamples.toDouble / (100.0 / failureRate.percent)).round.toInt).toVector map { num =>
        Task.delay {
          println(s"Failed task #$num.")
          Thread.sleep(5L)
        } flatMap { _ => Task.fail(new Exception(s"Expected failure #$num")) }
      }
    }
    val successfulTasks = (1 to (testRequestSamples - failedTasks.size)).toVector map { num =>
      Task.delay {
        println(s"Successful task #$num.")
        Thread.sleep(5L)
        ()
      }
    }

    Random.shuffle(successfulTasks ++ failedTasks).grouped(groupedTasks).toVector.traverse {
      def protectInParallel(taskGroup: Vector[Task[Unit]]) = {
        def attemptProtect(t: Task[Unit]) = cb.protect(t).attempt map { _.left.toOption }
        implicitly[Async[Task]].parallelTraverse(taskGroup map attemptProtect)(identity)
      }
      protectInParallel(_) map { r =>
        Thread.sleep(50L)
        r
      }
    }.map { _.flatten }.unsafeRun
  }

  /**
   * Executes tasks protected with a flow control circuit breaker in concurrent groups
   * with varying lengths of task execution and pauses between group executions.
   * This is all to cause the overall number of concurrent execution attempts (the simulatored
   * inbound requests) to be either greater or less than the tasks' ability to keep up based
   * on their execution lengths.  Flow control related tests execute this method with different
   * input parameters to ensure throttle up and/or throttle down events and associated throttle
   * exceptions are occurring as expected in the circuitbreaker protecting the tasks.
   */
  def protectFlowControl(
    cb: CircuitBreaker[Task],
    initialRequestTime: FiniteDuration,
    requestTimeAdjustment: FiniteDuration,
    pauseBetweenTaskGroups: FiniteDuration
  ): Unit = {
    val groupedTasks = if (testRequestSamples < GroupTasksBy) testRequestSamples else GroupTasksBy
    case class ExecutionContext(tasks: Vector[Task[Unit]] = Vector.empty, currentRequestTime: FiniteDuration = initialRequestTime)
    val ec = (1 to testRequestSamples).toVector.foldLeft(ExecutionContext()) {
      case (ctx, num) =>
        val updatedRequestTime = ctx.currentRequestTime.plus(requestTimeAdjustment).max(0.milliseconds)
        val task = Task.delay {
          println(s"Task #$num with request time of $updatedRequestTime.")
          Thread.sleep(updatedRequestTime.toMillis)
          ()
        }
        ExecutionContext(ctx.tasks :+ task, updatedRequestTime)
    }
    ec.tasks.grouped(groupedTasks).toVector.traverse {
      def protectInParallel(taskGroup: Vector[Task[Unit]]) = {
        def attemptProtect(t: Task[Unit]) = Task.start(cb.protect(t).attempt)
        implicitly[Async[Task]].parallelTraverse(taskGroup map attemptProtect)(identity)
      }
      protectInParallel(_) map { r =>
        Thread.sleep(pauseBetweenTaskGroups.toMillis)
        r
      }
    }.map { _ => () }.unsafeRun
  }
}
