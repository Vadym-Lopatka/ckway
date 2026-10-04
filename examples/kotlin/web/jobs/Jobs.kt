package web.jobs

import kotlinx.coroutines.runBlocking
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

annotation class NoTransaction

interface Job {
  suspend fun run()
  val name: String get() = this::class.simpleName ?: "job"
  val allowParallelRun: Boolean get() = false
  val noTransaction: Boolean get() = this::class.java.isAnnotationPresent(NoTransaction::class.java)
}

class JobRunner(val pool: ScheduledExecutorService) {
  fun runOnce(job: Job, delayMs: Long = 0) { pool.schedule({ runBlocking { job.run() } }, delayMs, TimeUnit.MILLISECONDS) }
}
