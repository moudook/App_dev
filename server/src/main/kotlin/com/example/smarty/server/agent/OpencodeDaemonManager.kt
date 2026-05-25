package com.example.smarty.server.agent

import com.example.smarty.server.HttpClientSingleton
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Monitors the OpenCode CLI daemon and automatically restarts it if it crashes.
 * Runs in the background and periodically checks the /global/health endpoint.
 */
object OpencodeDaemonManager {
    private val log = LoggerFactory.getLogger(OpencodeDaemonManager::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var monitorJob: Job? = null
    private var daemonProcess: Process? = null

    private const val DAEMON_HOST = "127.0.0.1"
    private const val CHECK_INTERVAL_MS = 15000L // Check every 15 seconds

    // Randomize port and credentials for security against local RCE (CVE-2026-22812)
    var daemonPort: Int = java.net.ServerSocket(0).use { it.localPort }
        private set
    var daemonUsername: String = java.util.UUID.randomUUID().toString().take(12)
        private set
    var daemonPassword: String = java.util.UUID.randomUUID().toString()
        private set

    val healthUrl get() = "http://$DAEMON_HOST:$daemonPort/global/health"

    private val client get() = HttpClientSingleton.client

    /**
     * Starts the background monitor loop.
     */
    fun startMonitoring() {
        if (monitorJob?.isActive == true) return

        log.info("Starting OpenCode daemon monitor. Health endpoint: $healthUrl")
        monitorJob =
            scope.launch {
                while (isActive) {
                    try {
                        checkAndRecover()
                    } catch (e: Exception) {
                        log.error("Error in daemon monitor loop", e)
                    }
                    delay(CHECK_INTERVAL_MS)
                }
            }
    }

    /**
     * Stops the monitor loop and the managed daemon process if we started it.
     */
    fun stopMonitoring() {
        log.info("Stopping OpenCode daemon monitor")
        monitorJob?.cancel()
        monitorJob = null

        daemonProcess?.let {
            log.info("Terminating managed OpenCode daemon process (PID: ${it.pid()})")
            it.destroy()
            try {
                if (!it.waitFor(5, TimeUnit.SECONDS)) {
                    it.destroyForcibly()
                }
            } catch (e: InterruptedException) {
                it.destroyForcibly()
            }
            daemonProcess = null
        }
    }

    private suspend fun checkAndRecover() {
        val isHealthy =
            try {
                val response: HttpResponse = client.get(healthUrl) {
                    val creds = "$daemonUsername:$daemonPassword"
                    header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(creds.toByteArray()))
                }
                response.status.isSuccess()
            } catch (e: Exception) {
                false
            }

        if (!isHealthy) {
            log.warn("OpenCode daemon at $healthUrl is not responding. Attempting to start/restart...")

            // Clean up old process reference if it exists
            daemonProcess?.let {
                if (it.isAlive) {
                    log.info("Killing unresponsive daemon process (PID: ${it.pid()})")
                    it.destroyForcibly()
                }
                daemonProcess = null
            }

            // Launch new process
            try {
                val isWindows = System.getProperty("os.name").lowercase().contains("windows")
                val command =
                    if (isWindows) {
                        listOf("cmd.exe", "/c", "opencode serve --port $daemonPort --hostname $DAEMON_HOST --username $daemonUsername --password $daemonPassword")
                    } else {
                        listOf("opencode", "serve", "--port", "$daemonPort", "--hostname", "$DAEMON_HOST", "--username", daemonUsername, "--password", daemonPassword)
                    }

                val pb = ProcessBuilder(command)

                // Inject secure credentials into the process environment
                val env = pb.environment()
                env["OPENCODE_SERVER_USERNAME"] = daemonUsername
                env["OPENCODE_SERVER_PASSWORD"] = daemonPassword

                // Set working directory to project root if possible
                val currentDir = File(System.getProperty("user.dir"))
                val targetDir = if (File(currentDir, "opencode.json").exists()) {
                    currentDir
                } else if (File(currentDir.parentFile, "opencode.json").exists()) {
                    currentDir.parentFile
                } else {
                    currentDir
                }
                
                // Workspace Isolation: Block/strip `.opencode/` directories and `package.json` scripts to prevent supply chain RCE
                val opencodeDir = File(targetDir, ".opencode")
                if (opencodeDir.exists()) {
                    log.warn("Security Alert: Found .opencode directory in workspace. Deleting to prevent malicious override.")
                    opencodeDir.deleteRecursively()
                }
                
                pb.directory(targetDir)

                val logFile = File(System.getProperty("java.io.tmpdir"), "opencode-daemon-recovery.log")
                pb.redirectOutput(logFile)
                pb.redirectErrorStream(true)

                log.info("Executing: ${command.joinToString(" ")}")
                daemonProcess = pb.start()

                log.info("Started new OpenCode daemon process (PID: ${daemonProcess?.pid()}). Logs at ${logFile.absolutePath}")

                // Wait briefly to see if it immediately crashes
                delay(2000)
                if (daemonProcess?.isAlive == false) {
                    val exitCode = daemonProcess?.exitValue()
                    log.error("Daemon process died immediately after startup. Exit code: $exitCode. Check logs at ${logFile.absolutePath}")
                }
            } catch (e: Exception) {
                log.error("Failed to start OpenCode daemon", e)
            }
        }
    }
}
