package ai.rever.boss.git

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal const val GIT_CLONE_TIMEOUT_MILLIS = 600_000L
private const val CLONE_PROCESS_KILL_TIMEOUT_SECONDS = 5L
private const val CLONE_OUTPUT_READER_JOIN_TIMEOUT_MILLIS = 5_000L

private class CloneOutputReader(
    val thread: Thread,
    val failure: AtomicReference<Throwable?>,
    val acceptingCallbacks: AtomicBoolean,
)

internal class CloneOutputLifecycle(
    val onClosed: () -> Unit = {},
    val onFailure: (Throwable) -> Unit = {},
)

internal fun cloneOutputReaderThreadName(process: Process): String {
    val processId = runCatching { process.pid() }.getOrDefault(-1L)
    return "boss-git-clone-output-$processId"
}

/**
 * Streams a clone process's merged output while independently awaiting its exit.
 *
 * The output reader must never own the timeout: a live process may keep its pipe
 * open without producing another line. Timeout and caller cancellation therefore
 * suspend on [Process.onExit], then force process, stream, and reader cleanup.
 */
internal suspend fun runCloneProcess(
    process: Process,
    timeoutMillis: Long,
    onOutputLine: (String) -> Unit,
    onCancellation: () -> Unit,
    outputLifecycle: CloneOutputLifecycle = CloneOutputLifecycle(),
): Int {
    val processResult = CompletableDeferred<Int>()
    val outputReader = createCloneOutputReader(process, onOutputLine, processResult)
    val outputClosed = AtomicBoolean()
    var processExitObserved = false

    fun closeOutput() {
        if (outputClosed.compareAndSet(false, true)) {
            runCatching { outputLifecycle.onClosed() }
        }
    }

    try {
        registerCloneProcessExit(process, processResult)
        outputReader.thread.start()

        val exitCode =
            withTimeout(timeoutMillis) {
                processResult.await()
            }

        processExitObserved = true
        closeOutput()
        awaitCloneOutputReader(outputReader, outputLifecycle.onFailure)
        return exitCode
    } finally {
        outputReader.acceptingCallbacks.set(false)
        closeOutput()
        cleanupCloneProcess(
            process = process,
            outputReader = outputReader.thread,
            cancellationCleanup = if (processExitObserved) null else onCancellation,
        )
    }
}

private fun createCloneOutputReader(
    process: Process,
    onOutputLine: (String) -> Unit,
    processResult: CompletableDeferred<Int>,
): CloneOutputReader {
    val readerFailure = AtomicReference<Throwable?>(null)
    val acceptingCallbacks = AtomicBoolean(true)
    val outputReader =
        Thread(
            {
                runCatching {
                    BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (acceptingCallbacks.get()) {
                                onOutputLine(line)
                            }
                        }
                    }
                }.exceptionOrNull()?.let { error ->
                    readerFailure.compareAndSet(null, error)
                    processResult.completeExceptionally(error)
                }
            },
            cloneOutputReaderThreadName(process),
        ).apply {
            isDaemon = true
        }

    return CloneOutputReader(
        thread = outputReader,
        failure = readerFailure,
        acceptingCallbacks = acceptingCallbacks,
    )
}

private suspend fun awaitCloneOutputReader(
    outputReader: CloneOutputReader,
    onOutputFailure: (Throwable) -> Unit,
) {
    withContext(Dispatchers.IO) {
        outputReader.thread.join(CLONE_OUTPUT_READER_JOIN_TIMEOUT_MILLIS)
    }

    val failure =
        if (outputReader.thread.isAlive) {
            IOException("Git clone output reader did not finish after the process exited")
        } else {
            outputReader.failure.get()
        }

    outputReader.acceptingCallbacks.set(false)

    if (outputReader.thread.isAlive) {
        outputReader.thread.interrupt()
    }

    if (failure != null) {
        runCatching { onOutputFailure(failure) }
    }
}

/**
 * Kept clone-local because this source survives Windows ARM64 filtering while
 * the process helpers in the kernel package do not.
 */
private fun snapshotCloneProcessDescendants(process: Process): List<ProcessHandle> =
    runCatching {
        process
            .toHandle()
            .descendants()
            .use { it.toList() }
    }.getOrDefault(emptyList())

private fun killCloneProcessDescendants(descendants: List<ProcessHandle>) {
    descendants.forEach { descendant ->
        runCatching {
            if (descendant.isAlive) {
                descendant.destroyForcibly()
            }
        }
    }
}

private fun awaitCloneProcessDescendantsExit(descendants: List<ProcessHandle>) {
    val deadlineNanos =
        System.nanoTime() +
            TimeUnit.SECONDS.toNanos(CLONE_PROCESS_KILL_TIMEOUT_SECONDS)

    descendants.forEach { descendant ->
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) {
            return
        }

        runCatching {
            descendant.onExit().get(remainingNanos, TimeUnit.NANOSECONDS)
        }
    }
}

private suspend fun cleanupCloneProcess(
    process: Process,
    outputReader: Thread,
    cancellationCleanup: (() -> Unit)?,
) {
    withContext(NonCancellable + Dispatchers.IO) {
        val descendants = snapshotCloneProcessDescendants(process)

        if (process.isAlive) {
            killCloneProcessDescendants(descendants)
            runCatching { process.destroyForcibly() }
            runCatching {
                process.waitFor(
                    CLONE_PROCESS_KILL_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
            }
        }

        killCloneProcessDescendants(descendants)
        awaitCloneProcessDescendantsExit(descendants)
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }

        outputReader.interrupt()
        runCatching {
            outputReader.join(CLONE_OUTPUT_READER_JOIN_TIMEOUT_MILLIS)
        }

        if (cancellationCleanup != null) {
            runCatching { cancellationCleanup() }
        }
    }
}

private fun registerCloneProcessExit(
    process: Process,
    processResult: CompletableDeferred<Int>,
) {
    val exitFuture =
        runCatching { process.onExit() }
            .getOrElse { error ->
                processResult.completeExceptionally(error)
                return
            }

    exitFuture.whenComplete { completedProcess, error ->
        if (error == null) {
            processResult.complete(completedProcess.exitValue())
        } else {
            processResult.completeExceptionally(error)
        }
    }
}
