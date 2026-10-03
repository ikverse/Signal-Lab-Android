package com.ikverse.signallab.update

import java.io.File

/** What identifies an app file: which app it is, how new, and the keys it is signed with (as hashes). */
data class ApkFacts(val packageName: String, val versionCode: Long, val signers: Set<String>)

/** Reads [ApkFacts] from the installed app and from a file. Android's own package manager does it on a phone. */
interface ApkInspector {
    fun installed(): ApkFacts

    /** Null when the file is not an app Android can read. */
    fun inspect(file: File): ApkFacts?
}

/**
 * The last check before Android's installer opens. Android would refuse most of these itself, with a vague message and
 * after the user has already tapped through; checking here says why, in words, and refuses before anything is installed.
 */
object ApkCheck {
    /**
     * Null when [archive] may be installed over [installed]; otherwise the reason it may not, as a sentence.
     * [expectedCode] is the version code the release's tag promised; a file that is a different version than its release is refused.
     */
    fun problem(archive: ApkFacts?, installed: ApkFacts, expectedCode: Long? = null): String? = when {
        archive == null -> "The downloaded file is not an app Android can read."
        archive.packageName != installed.packageName -> "The downloaded file is a different app, so it was not installed."
        // An empty set matches an empty set, so a file with no readable key must be refused outright.
        archive.signers.isEmpty() || archive.signers != installed.signers ->
            "The update is signed with a different key than this app, so Android would refuse it. Nothing was installed."
        archive.versionCode <= installed.versionCode -> "The downloaded file is not newer than this app, so it was not installed."
        expectedCode != null && archive.versionCode != expectedCode -> "The downloaded file is not the version its release says, so it was not installed."
        else -> null
    }
}
