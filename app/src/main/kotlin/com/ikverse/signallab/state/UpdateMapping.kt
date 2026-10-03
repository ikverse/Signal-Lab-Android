package com.ikverse.signallab.state

import com.ikverse.signallab.ui.UpdateStatus
import com.ikverse.signallab.ui.UpdateUi
import com.ikverse.signallab.update.UpdateState

/** What the Updates section shows for each state of the updater. */
fun UpdateState.toUi(): UpdateUi = when (this) {
    is UpdateState.Idle -> UpdateUi(UpdateStatus.IDLE, checkedAt = lastCheckedAt)
    UpdateState.Checking -> UpdateUi(UpdateStatus.CHECKING)
    is UpdateState.UpToDate -> UpdateUi(UpdateStatus.UP_TO_DATE, checkedAt = checkedAt)
    is UpdateState.Available -> UpdateUi(UpdateStatus.AVAILABLE, release.version.toString(), release.notes, checkedAt = checkedAt, canInstall = true)
    is UpdateState.Downloading -> UpdateUi(
        UpdateStatus.DOWNLOADING, release.version.toString(), release.notes,
        progress = (bytes.toFloat() / release.apkBytes).coerceIn(0f, 1f),
    )
    is UpdateState.NeedsPermission -> UpdateUi(
        UpdateStatus.NEEDS_PERMISSION, release.version.toString(), release.notes,
        message = "The update is downloaded and checked. Allow installs from Signal Lab in the screen that opened, come back, and tap Install again.",
        canInstall = true,
    )
    is UpdateState.Handed -> UpdateUi(
        UpdateStatus.INSTALLER_OPEN, release.version.toString(), release.notes,
        message = "Android's installer is open. If you backed out, you can install again.", canInstall = true,
    )
    is UpdateState.Failed -> UpdateUi(UpdateStatus.FAILED, release?.version?.toString(), release?.notes.orEmpty(), message = message, canInstall = release != null)
}
