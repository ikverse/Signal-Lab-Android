package com.ikverse.signallab.host

import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

/**
 * Starts the analyst host:
 *   signal-lab-host [--port 8765] [--claude <path to claude>] [--home <folder>] [--new-code]
 * and prints what to enter on the phone. It runs until the window is closed or Ctrl+C.
 */
fun main(args: Array<String>) {
    fun arg(name: String): String? = args.indexOf(name).takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }
    val port = arg("--port")?.toIntOrNull() ?: 8765
    val claude = InstalledClaude(arg("--claude") ?: "claude")
    val config = HostConfig(arg("--home")?.let(::File) ?: HostConfig.defaultHome())
    if ("--new-code" in args) config.newPairingCode()

    val version = claude.version()
    if (version == null) {
        println("Claude Code was not found. Install it and sign in once (run: claude), then start this again.")
        exitProcess(1)
    }
    val work = Files.createTempDirectory("signal-lab-host").toFile().apply { deleteOnExit() }
    val bridge = ToolBridge()
    val server = PhoneServer({ config.pairingCode }, ClaudeRunner(claude, work), bridge, work, version) { println("  ${java.time.LocalTime.now().withNano(0)}  $it") }
    try {
        server.start(port, PhoneServer.tls(config))
    } catch (e: Exception) {
        println("Could not listen on port $port: ${e.message}. Is the host already running?")
        exitProcess(1)
    }
    val addresses = HostConfig.lanAddresses()
    println(
        """
        Signal Lab analyst host
        Claude Code ${version.removeSuffix(" (Claude Code)")}, signed in on this PC

        On the phone, in the Analyst tab under "Use your PC", enter:
          Address       ${addresses.firstOrNull() ?: "this PC's address on your network"}${if (port != 8765) ":$port" else ""}
          Pairing code  ${config.shownCode}
        The phone then shows a fingerprint; it should end in ${config.shortFingerprint}.
        ${if (addresses.size > 1) "Other addresses of this PC: ${addresses.drop(1).joinToString()}" else ""}
        Keep this window open while you use the analyst. Close it, or press Ctrl+C, to stop.
        """.trimIndent(),
    )
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop()
        bridge.stop()
    })
    Thread.currentThread().join()
}
