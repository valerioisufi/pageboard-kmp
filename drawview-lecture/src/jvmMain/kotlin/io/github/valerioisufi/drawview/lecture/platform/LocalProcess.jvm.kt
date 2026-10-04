package io.github.valerioisufi.drawview.lecture.platform

import java.util.concurrent.TimeUnit

actual fun startLocalProcess(command: List<String>): LocalProcess? = JvmLocalProcess(command)

private class JvmLocalProcess(command: List<String>) : LocalProcess {
    private val process = ProcessBuilder(command).redirectErrorStream(true).start()
    private val output = ArrayDeque<String>()
    private val shutdownHook = Thread { process.destroy() }

    init {
        // The output is read continuously (a full pipe would block the program)
        Thread({
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(output) {
                        output.addLast(line)
                        while (output.size > MAX_LINES) output.removeFirst()
                    }
                }
            } catch (_: Exception) {
                // The program stopped
            }
        }, "local-process-output").apply {
            isDaemon = true
            start()
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    override val isAlive: Boolean get() = process.isAlive

    override fun recentOutput(): String = synchronized(output) { output.joinToString("\n") }

    override fun stop() {
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        } catch (_: IllegalStateException) {
            // The application is exiting
        }
    }

    private companion object {
        const val MAX_LINES = 40
    }
}
