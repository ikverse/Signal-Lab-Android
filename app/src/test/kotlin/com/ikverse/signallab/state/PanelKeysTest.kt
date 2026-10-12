package com.ikverse.signallab.state

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every pane key a screen saves is one the panel preferences read back, or a divider dragged today is back where it started tomorrow. */
class PanelKeysTest {
    @Test
    fun `every pane key in the screens is read back at start`() {
        val dir = File("src/main/kotlin/com/ikverse/signallab/ui")
        assertTrue("run from the app module: ${dir.absolutePath}", dir.isDirectory)
        val used = Regex("""(?:SplitPane|rememberPaneLayout)\(\s*panels,\s*"([a-z-]+)"""")
        val found = LinkedHashSet<String>()
        for (f in dir.listFiles { f -> f.extension == "kt" }!!) for (m in used.findAll(f.readText())) found += m.groupValues[1]
        assertTrue("found only $found; the scan is not looking in the right place", found.size >= 5)
        val missing = found - LivePanelPrefs.KEYS.toSet()
        assertTrue("saved by a screen but never read back: $missing", missing.isEmpty())
    }
}
