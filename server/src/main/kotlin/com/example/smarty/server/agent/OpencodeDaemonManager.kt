package com.example.smarty.server.agent

/**
 * Daemon is now managed by the Docker entrypoint script (start.sh) on port 4096.
 * This object is kept as a dummy to avoid breaking existing imports.
 */
object OpencodeDaemonManager {
    var daemonPort: Int = 4096
    var daemonUsername: String = ""
    var daemonPassword: String = ""
    val healthUrl get() = "http://127.0.0.1:$daemonPort/global/health"

    fun startMonitoring() {}

    fun stopMonitoring() {}
}
