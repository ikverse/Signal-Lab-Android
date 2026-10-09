package com.ikverse.signallab.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.ikverse.signallab.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What Google said when asked for access: a token, or a screen the user must see first. */
sealed interface AuthOutcome {
    data class Token(val token: String) : AuthOutcome
    data class NeedsUser(val intent: PendingIntent) : AuthOutcome
}

/** Asks Google for access to the app's Drive folder. */
interface Authorizer {
    suspend fun authorize(): AuthOutcome
    /** The token from the screen the user just saw; throws if they said no or it failed. */
    fun tokenFrom(data: Intent?): String
}

/**
 * Google's own authorization, through Play services: the first time it shows the account picker and one consent screen, and after
 * that it hands over a token without showing anything. Nothing is stored by the app; the app is matched to its Google Cloud client
 * by package name and signing key, so there is no client id or secret in the code either.
 */
class GoogleAuthorizer(private val context: Context) : Authorizer {
    private val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_APPDATA))).build()

    override suspend fun authorize(): AuthOutcome {
        val r = Identity.getAuthorizationClient(context).authorize(request).await()
        val intent = r.pendingIntent
        return if (r.hasResolution() && intent != null) AuthOutcome.NeedsUser(intent)
        else AuthOutcome.Token(r.accessToken ?: throw SyncAuthException("Google gave no access token."))
    }

    override fun tokenFrom(data: Intent?): String {
        val r: AuthorizationResult = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        return r.accessToken ?: throw SyncAuthException("Google gave no access token.")
    }

    companion object {
        const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

/** Where sync stands, for Settings. */
data class SyncStatus(
    val enabled: Boolean = false,
    val account: String? = null,
    val lastSyncedAt: Long? = null,
    /** Other devices seen in the folder at the last round. */
    val devices: Int = 0,
    val busy: Boolean = false,
    /** Google needs the user again (they signed out or took the access away); signing in fixes it. */
    val needsSignIn: Boolean = false,
    /** What went wrong last, in a sentence; null when the last round went well. */
    val problem: String? = null,
)

/**
 * Keeps this device's record in step with the user's other devices through their Google Drive. Off until the user signs in from
 * Settings. Once on, it syncs when the app opens, a little after anything worth sending changes (a trade opened or closed, a list,
 * a lab pattern, a report, a cost), and when asked; automatic rounds are at least [minGapMs] apart. A failure is shown in Settings and
 * never stops scanning.
 */
class DeviceSync(
    private val settings: SettingsStore,
    private val authorizer: Authorizer,
    /** The shared folder, given a way to get a current token. */
    private val folder: (token: suspend () -> String) -> RemoteFolder,
    private val record: RecordSync,
    private val memory: SyncMemory,
    /** Tells the stores what a merge brought in, so the screens read again. */
    private val afterMerge: suspend (MergeResult) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minGapMs: Long = MIN_GAP_MS,
    private val settleMs: Long = SETTLE_MS,
) {
    private val state = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = state.asStateFlow()

    private val consents = MutableSharedFlow<PendingIntent>(extraBufferCapacity = 1)

    /** Google's sign-in screen, for the activity to show. */
    val consent: SharedFlow<PendingIntent> = consents.asSharedFlow()

    private val lock = Mutex()
    private val wanted = Channel<Unit>(Channel.CONFLATED)

    /** Reads what was remembered and, if sync is on, starts listening for changes. [changes] emits whenever something worth sending changed. */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope, changes: List<Flow<*>>) {
        scope.launch {
            state.value = state.value.copy(
                enabled = settings.getBoolean(ENABLED, false), account = settings.get(ACCOUNT)?.ifEmpty { null },
                lastSyncedAt = settings.get(LAST_SYNCED)?.toLongOrNull(),
            )
            requestSync()
        }
        scope.launch {
            merge(*changes.map { it.drop(1) }.toTypedArray()).debounce(settleMs).collect { requestSync() }
        }
        scope.launch {
            for (ignored in wanted) {
                if (!state.value.enabled || state.value.needsSignIn) continue
                val last = state.value.lastSyncedAt ?: 0L
                val wait = last + minGapMs - clock()
                if (wait > 0) delay(wait)
                runRound(interactive = false)
            }
        }
    }

    /** Asks for a round soon; rounds asked for close together become one. */
    fun requestSync() {
        wanted.trySend(Unit)
    }

    /** Turns sync on: Google shows its account picker and consent screen the first time, then the first round runs. */
    suspend fun signIn() {
        state.value = state.value.copy(busy = true, problem = null)
        try {
            when (val a = authorizer.authorize()) {
                is AuthOutcome.Token -> turnOn()
                is AuthOutcome.NeedsUser -> {
                    state.value = state.value.copy(busy = false)
                    consents.emit(a.intent)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.value = state.value.copy(busy = false, problem = "Could not reach Google: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** The user finished Google's screen. [ok] is false when they backed out. */
    suspend fun onConsent(ok: Boolean, data: Intent?) {
        if (!ok) {
            state.value = state.value.copy(busy = false, problem = if (state.value.enabled) state.value.problem else "Sign-in was cancelled.")
            return
        }
        try {
            authorizer.tokenFrom(data)
            turnOn()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.value = state.value.copy(busy = false, problem = "Google did not give access: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun turnOn() {
        settings.setBoolean(ENABLED, true)
        state.value = state.value.copy(enabled = true, needsSignIn = false, problem = null)
        runRound(interactive = true)
    }

    /** A round now, whatever the time since the last. */
    suspend fun syncNow() {
        if (!state.value.enabled) return
        runRound(interactive = true)
    }

    /** Stops syncing from this device. What is already in Drive stays there, and nothing here is removed. */
    suspend fun turnOff() {
        settings.setBoolean(ENABLED, false)
        settings.set(ACCOUNT, "")
        state.value = state.value.copy(enabled = false, busy = false, needsSignIn = false, problem = null, account = null)
    }

    private suspend fun runRound(interactive: Boolean) = lock.withLock {
        state.value = state.value.copy(busy = true)
        try {
            when (val a = authorizer.authorize()) {
                is AuthOutcome.Token -> {}
                is AuthOutcome.NeedsUser -> {
                    state.value = state.value.copy(busy = false, needsSignIn = true, problem = "Google needs you to sign in again.")
                    if (interactive) consents.emit(a.intent)
                    return@withLock
                }
            }
            // Play services keeps the token and renews it, so asking again for each request costs nothing and is never stale.
            val drive = folder { (authorizer.authorize() as? AuthOutcome.Token)?.token ?: throw SyncAuthException("Google needs you to sign in again.") }
            val round = SyncEngine(drive, record, memory, clock).round()
            if (round.merged.any) afterMerge(round.merged)
            val account = state.value.account ?: runCatching { drive.accountEmail() }.getOrNull()
            val now = clock()
            settings.set(LAST_SYNCED, now.toString())
            account?.let { settings.set(ACCOUNT, it) }
            state.value = state.value.copy(
                busy = false, needsSignIn = false, lastSyncedAt = now, devices = round.devices, account = account,
                problem = if (round.unreadable > 0) "${round.unreadable} device record(s) could not be read. Is every device on the newest version?" else null,
            )
        } catch (e: CancellationException) {
            state.value = state.value.copy(busy = false)
            throw e
        } catch (e: SyncAuthException) {
            state.value = state.value.copy(busy = false, needsSignIn = true, problem = e.message)
        } catch (e: Exception) {
            state.value = state.value.copy(busy = false, problem = "Sync did not finish: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        const val ENABLED = "sync_enabled"
        const val ACCOUNT = "sync_account"
        const val LAST_SYNCED = "sync_last"
        const val DEVICE_ID = "sync_device_id"
        const val OWN_FILE = "sync_own_file"
        const val LAST_SENT = "sync_last_sent"
        const val SEEN_PREFIX = "sync_seen_"

        const val MIN_GAP_MS = 5 * 60_000L
        const val SETTLE_MS = 20_000L
    }
}

/** [SyncMemory] in the settings table, under keys that are never synced themselves. */
class SettingsSyncMemory(private val settings: SettingsStore) : SyncMemory {
    override suspend fun deviceId(): String =
        settings.get(DeviceSync.DEVICE_ID) ?: com.ikverse.signallab.data.RecordDatabase.newSyncId().also { settings.set(DeviceSync.DEVICE_ID, it) }

    override suspend fun ownFileId(): String? = settings.get(DeviceSync.OWN_FILE)
    override suspend fun saveOwnFileId(id: String) = settings.set(DeviceSync.OWN_FILE, id)
    override suspend fun lastSent(): String? = settings.get(DeviceSync.LAST_SENT)
    override suspend fun saveLastSent(fingerprint: String) = settings.set(DeviceSync.LAST_SENT, fingerprint)
    override suspend fun seen(fileId: String): String? = settings.get(DeviceSync.SEEN_PREFIX + fileId)
    override suspend fun saveSeen(fileId: String, modified: String) = settings.set(DeviceSync.SEEN_PREFIX + fileId, modified)
}
