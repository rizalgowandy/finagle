package com.twitter.finagle.offload

import com.twitter.finagle.stats.{FinagleStatsReceiver, StatsReceiver}
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util.{Duration, ExecutorServiceFuturePool, FuturePool}
import java.util.concurrent.ExecutorService

private final class OffloadFuturePool(executor: ExecutorService, stats: StatsReceiver)
    extends ExecutorServiceFuturePool(executor) {
  // Reference held so GC doesn't clean these up automatically.
  private val gauges = Seq(
    stats.addGauge("pool_size") { poolSize },
    stats.addGauge("active_tasks") { numActiveTasks },
    stats.addGauge("completed_tasks") { numCompletedTasks },
    stats.addGauge("queue_depth") { numPendingTasks }
  )

  val admissionControl: Option[OffloadFilterAdmissionControl] =
    OffloadFilterAdmissionControl(this, stats.scope("admission_control"))
}

object OffloadFuturePool {

  /**
   * A central `FuturePool` to use for your application work.
   *
   * If configured, this `FuturePool` is used by `OffloadFilter` to shift your application work
   * from the I/O threads to this pool. This has the benefit of dramatically increasing the
   * responsiveness of I/O work and also acts as a safety against long running or blocking work
   * that may be unknowingly scheduled on the I/O threads.
   *
   * This pool should be used only for non-blocking application work and preferably tasks
   * that are not expected to take a very long time to compute.
   */
  lazy val configuredPool: Option[FuturePool] = {
    val workers =
      numWorkers.get.orElse(if (auto()) Some(com.twitter.jvm.numProcs().ceil.toInt) else None)

    workers.map { threads =>
      val stats = FinagleStatsReceiver.scope("offload_pool")
      val pool = new OffloadFuturePool(OffloadThreadPool(threads, queueSize(), stats), stats)

      // Start sampling the offload delay if the interval isn't Duration.Top.
      if (statsSampleInterval().isFinite && statsSampleInterval() > Duration.Zero) {
        val sampleStats = new SampleQueueStats(pool, stats, DefaultTimer)
        sampleStats()
      }

      pool
    }
  }

  /**
   * Get the configured offload pool if available or default to the unbounded [[FuturePool]].
   *
   * @note that the unbounded `FuturePool` can grow indefinitely, both in queue size and in terms
   *       of thread count and should be used with caution.
   */
  def getPool: FuturePool = configuredPool match {
    case Some(pool) => pool
    case None => FuturePool.unboundedPool
  }
}
