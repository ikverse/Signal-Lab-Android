package com.ikverse.signallab.analyst

import com.ikverse.signallab.ledger.LedgerDao
import com.ikverse.signallab.ledger.SettingRow
import okhttp3.OkHttpClient
import java.io.File

/** The PC this phone is paired with. */
data class PairedHost(val address: String, val pin: String, val code: String)

/** What the first contact with a PC found, for the owner to confirm. */
data class HostProbe(val address: String, val code: String, val fingerprint: String, val claude: String?) {
    /** The part of the fingerprint a person compares with the PC's window. */
    val shortFingerprint: String get() = fingerprint.takeLast(6)
}

/** The analyst host's route, as the analyst sees it: a client for the paired PC, and whether it is answering. */
interface PcRoute {
    suspend fun paired(): PairedHost?

    /** A client for the paired PC when it answers now; null when there is none or it does not. */
    suspend fun reachable(): HostClient?
}

/**
 * Pairing with the analyst host. The address and the pinned certificate are kept in the ledger's settings (never synced);
 * the pairing code is a secret, sealed in the no-backup folder like the API key.
 */
class HostPairing(
    private val dao: LedgerDao,
    private val codeFile: File,
    private val cipher: SecretCipher,
    /** Makes the HTTP client; tests use plain HTTP. */
    private val clientFor: (pin: String?, seen: (String) -> Unit) -> OkHttpClient = HostClient::pinned,
    private val scheme: String = "https",
    private val now: () -> Long = System::currentTimeMillis,
) : PcRoute {
    private val codes = ApiKeyStoreLike(codeFile, cipher)

    @Volatile
    private var lastCheck: Pair<Long, Boolean>? = null

    override suspend fun paired(): PairedHost? {
        val address = dao.setting(ADDRESS) ?: return null
        val pin = dao.setting(PIN) ?: return null
        val code = codes.load() ?: return null
        return PairedHost(address, pin, code)
    }

    fun client(host: PairedHost): HostClient? =
        HostClient.baseUrl(host.address, scheme)?.let { HostClient(it, host.code, clientFor(host.pin) {}) }

    override suspend fun reachable(): HostClient? {
        val host = paired() ?: return null
        val client = client(host) ?: return null
        val cached = lastCheck
        if (cached != null && now() - cached.first < CHECK_MS) return client.takeIf { cached.second }
        val ok = client.check()
        lastCheck = now() to ok
        return client.takeIf { ok }
    }

    /** Forgets the last reachability check, so the next question asks the PC again. */
    fun recheck() {
        lastCheck = null
    }

    /**
     * First contact with the PC at [address]: reads its certificate and checks the pairing code with it. Nothing is saved;
     * the owner compares the fingerprint with the PC's window and then [confirm]s.
     */
    suspend fun probe(address: String, code: String): HostProbe {
        val base = HostClient.baseUrl(address, scheme) ?: throw HostException("That is not an address. Type it as the PC shows it, such as 192.168.1.20.")
        var seen: String? = null
        val open = HostClient(base, code.trim(), clientFor(null) { seen = it })
        val hello = try {
            open.hello()
        } catch (e: HostException) {
            throw e
        } catch (e: Exception) {
            throw HostException("No analyst host answered at $address. Is it running on the PC, and is the phone on the same network?")
        }
        if (hello.optString("app") != "Signal Lab analyst host") throw HostException("Something else answered at $address.")
        val fp = seen ?: if (scheme == "http") "PLAIN-HTTP-TEST" else throw HostException("The PC sent no certificate.")
        // Same certificate as the first contact, and the code must be right.
        HostClient(base, code.trim(), clientFor(fp) {}).verify()
        return HostProbe(address.trim(), code.trim(), fp, hello.optString("claude").ifBlank { null })
    }

    /** The owner saw the same fingerprint on both screens: pair. */
    suspend fun confirm(p: HostProbe) {
        codes.save(p.code)
        dao.putSetting(SettingRow(ADDRESS, p.address, now()))
        dao.putSetting(SettingRow(PIN, p.fingerprint, now()))
        lastCheck = null
    }

    suspend fun forget() {
        codes.clear()
        dao.deleteSetting(ADDRESS)
        dao.deleteSetting(PIN)
        lastCheck = null
    }

    companion object {
        const val ADDRESS = "analyst_host_address"
        const val PIN = "analyst_host_pin"
        const val FILE_NAME = "analyst_host_code.bin"
        const val CHECK_MS = 30_000L
    }
}

/** A sealed secret in a file, as [ApiKeyStore] keeps the API key, without the key's own checks. */
internal class ApiKeyStoreLike(private val file: File, private val cipher: SecretCipher) {
    fun save(secret: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(cipher.encrypt(secret.toByteArray(Charsets.UTF_8)))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    fun load(): String? = if (!file.exists()) null else runCatching { String(cipher.decrypt(file.readBytes()), Charsets.UTF_8) }.getOrNull()

    fun clear() {
        file.delete()
    }
}
