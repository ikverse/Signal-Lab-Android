package com.ikverse.signallab.analyst

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** A question ready to hand over: one text, the question first and the record's data below it. */
class Handoff(val text: String)

/**
 * Hands a question to the Claude app, where the user's own Claude plan answers it. Nothing is sent from here: Claude opens with the text as
 * a draft, and the user decides whether to send it. Without the Claude app, Android's share menu opens instead.
 *
 * The question and the data travel as one text, not as a message with a file: the Claude app keeps a shared file but drops the text that
 * comes with it, so the question would be lost.
 */
object ClaudeHandoff {
    const val CLAUDE_PACKAGE = "com.anthropic.claude"

    fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(CLAUDE_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** The share of [handoff]: to the Claude app when it is installed, otherwise through Android's chooser. */
    fun intent(context: Context, handoff: Handoff, toClaude: Boolean = installed(context)): Intent {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, handoff.text)
        return if (toClaude) send.setPackage(CLAUDE_PACKAGE) else Intent.createChooser(send, "Ask with")
    }
}
