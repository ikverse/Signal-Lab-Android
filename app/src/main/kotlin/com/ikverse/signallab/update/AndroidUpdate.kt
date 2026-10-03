package com.ikverse.signallab.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/** The updater's pieces that need Android: the package manager for the keys, and the intents that open the installer. */

class AndroidApkInspector(private val context: Context) : ApkInspector {
    private val pm: PackageManager get() = context.packageManager

    override fun installed(): ApkFacts = try {
        facts(pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES))
    } catch (_: PackageManager.NameNotFoundException) {
        // Cannot happen for the running app; an empty set of keys makes every file fail the check.
        ApkFacts(context.packageName, 0L, emptySet())
    }

    override fun inspect(file: File): ApkFacts? = try {
        pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)?.let(::facts)
    } catch (_: Exception) {
        null
    }

    private fun facts(info: PackageInfo): ApkFacts {
        val signers = info.signingInfo?.apkContentsSigners.orEmpty().map { sha256(it.toByteArray()) }.toSet()
        return ApkFacts(info.packageName, info.longVersionCode, signers)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

class AndroidInstallLauncher(private val context: Context) : InstallLauncher {
    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun openPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun launch(file: File) {
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            throw UpdateException("This phone has no installer that can open the update.")
        }
    }

    companion object {
        /** The file provider named in the manifest, which lets Android's installer read the downloaded file. */
        fun authority(context: Context) = "${context.packageName}.updates"

        /** Where downloads go: the app's cache, so Android can reclaim it and nothing else can read it. */
        fun downloadDir(context: Context) = File(context.cacheDir, "updates")
    }
}
