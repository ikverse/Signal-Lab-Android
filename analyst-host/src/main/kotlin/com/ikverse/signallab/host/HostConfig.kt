package com.ikverse.signallab.host

import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Properties
import java.util.concurrent.TimeUnit

object Secrets {
    private val rng = SecureRandom()

    /** [n] random characters from letters and digits that cannot be mistaken for one another. */
    fun random(n: Int, alphabet: String = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"): String = buildString { repeat(n) { append(alphabet[rng.nextInt(alphabet.length)]) } }

    /** Compares two secrets in time that does not depend on where they differ. */
    fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    /** A pairing code as a person types it: case, spaces and dashes do not matter. */
    fun normalize(code: String): String = code.uppercase().filter { it.isLetterOrDigit() }
}

/**
 * What the host keeps between starts, in its own folder (by default `~/.signal-lab-host`): the pairing code the phone must
 * send, and the key and certificate its HTTPS server uses. The certificate is made once on first start with the JDK's
 * keytool; the phone pins it when it pairs.
 */
class HostConfig(val home: File) {
    private val file = File(home, "host.properties")
    private val props = Properties()
    val keystore = File(home, "host.p12")

    init {
        home.mkdirs()
        if (file.exists()) file.inputStream().use { props.load(it) }
        var changed = false
        if (props.getProperty("pairing_code").isNullOrBlank()) {
            props.setProperty("pairing_code", Secrets.random(10))
            changed = true
        }
        if (props.getProperty("keystore_password").isNullOrBlank()) {
            props.setProperty("keystore_password", Secrets.random(24))
            changed = true
        }
        if (changed) save()
        if (!keystore.exists()) makeCertificate()
    }

    private fun save() = file.outputStream().use { props.store(it, "Signal Lab analyst host. The pairing code lets a phone use this PC's Claude Code.") }

    /** The code a phone sends with every request, shown as two groups of five. */
    val pairingCode: String get() = props.getProperty("pairing_code")

    val shownCode: String get() = pairingCode.chunked(5).joinToString("-")

    val keystorePassword: CharArray get() = props.getProperty("keystore_password").toCharArray()

    /** A new pairing code: every paired phone must pair again. */
    fun newPairingCode() {
        props.setProperty("pairing_code", Secrets.random(10))
        save()
    }

    private fun makeCertificate() {
        val keytool = File(System.getProperty("java.home"), "bin/" + if (isWindows) "keytool.exe" else "keytool")
        val pw = String(keystorePassword)
        val p = ProcessBuilder(
            keytool.path, "-genkeypair", "-alias", ALIAS, "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
            "-validity", "3650", "-dname", "CN=Signal Lab analyst host", "-keystore", keystore.path, "-storetype", "PKCS12",
            "-storepass", pw, "-keypass", pw,
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0 && keystore.exists()) { "Could not make the host's certificate: $out" }
    }

    fun loadKeystore(): KeyStore = KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { load(it, keystorePassword) } }

    /** The SHA-256 of the certificate, in hex: what the phone pins. */
    val fingerprint: String
        get() = MessageDigest.getInstance("SHA-256").digest(loadKeystore().getCertificate(ALIAS).encoded).joinToString("") { "%02X".format(it) }

    /** The last six characters of [fingerprint], for a person to compare with what the phone shows. */
    val shortFingerprint: String get() = fingerprint.takeLast(6)

    companion object {
        const val ALIAS = "host"
        val isWindows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")

        fun defaultHome(): File = File(System.getProperty("user.home"), ".signal-lab-host")

        /** This PC's addresses on its local networks, for the phone to connect to. */
        fun lanAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { it.hostAddress }
        }.getOrDefault(emptyList())
    }
}
