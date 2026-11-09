package io.github.edrock200.appdownloader.install

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Drain output while streaming the APK, and bound every package-manager command. */
class InstallProcessRunner : InstallCommandRunner {
    override fun run(arguments: List<String>, input: InputStream?): String {
        val process = ProcessBuilder(listOf("/system/bin/pm") + arguments).redirectErrorStream(true).start()
        val output = ByteArrayOutputStream()
        val timedOut = AtomicBoolean(false)
        val timer = Timer("install-timeout", true)
        timer.schedule(object : TimerTask() {
            override fun run() {
                timedOut.set(true)
                process.destroy()
            }
        }, 180_000)
        val reader = thread(name = "install-output", isDaemon = true) {
            runCatching {
                process.inputStream.use { stream ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        synchronized(output) { output.write(buffer, 0, minOf(count, 16_384 - output.size())) }
                    }
                }
            }
        }
        try {
            var writeFailure: IOException? = null
            try {
                process.outputStream.use { stdin -> input?.copyTo(stdin) }
            } catch (error: IOException) {
                writeFailure = error
            }
            val exit = process.waitFor()
            reader.join(5_000)
            val message = synchronized(output) { output.toString("UTF-8").trim() }
            if (timedOut.get()) throw IOException("Installation timed out. Check Shizuku and try again.")
            if (exit != 0) throw IOException(message.take(700).ifEmpty { "Android's installer exited with code $exit" })
            if (writeFailure != null) throw IOException(message.take(700).ifEmpty { "Could not send the APK to Android's installer" }, writeFailure)
            return message
        } finally {
            timer.cancel()
            process.destroy()
        }
    }
}
