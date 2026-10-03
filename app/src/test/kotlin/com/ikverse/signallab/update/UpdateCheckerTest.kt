package com.ikverse.signallab.update

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdateCheckerTest {
    private lateinit var server: MockWebServer
    private val installed = AppVersion(0, 1, 0)

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    private fun checker(trusted: Boolean = true, http: OkHttpClient = OkHttpClient()) =
        UpdateChecker(http, latestUrl = server.url("/latest").toString(), trustedHost = { trusted })

    private fun release(
        tag: String = "v0.2.0", body: String = "Notes.", draft: Boolean = false, prerelease: Boolean = false,
        assets: String = """[{"name":"signal-lab-0.2.0.apk","size":1234,"browser_download_url":"https://github.com/ikverse/Signal-Lab-Android/releases/download/v0.2.0/signal-lab-0.2.0.apk"}]""",
    ) = """{"tag_name":"$tag","body":"$body","draft":$draft,"prerelease":$prerelease,"assets":$assets}"""

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    @Test
    fun `a newer release with an app file is offered, with its notes and size`() = runTest {
        server.enqueue(ok(release(body = "Adds the updater.")))
        val r = assertIs<CheckResult.Newer>(checker().check(installed)).release
        assertEquals(AppVersion(0, 2, 0), r.version)
        assertEquals("Adds the updater.", r.notes)
        assertEquals(1234L, r.apkBytes)
        assertTrue(r.apkUrl.endsWith("signal-lab-0.2.0.apk"))
    }

    @Test
    fun `it asks GitHub the way GitHub asks to be asked`() = runTest {
        server.enqueue(ok(release()))
        checker().check(installed)
        val request = server.takeRequest()
        assertEquals("/latest", request.url.encodedPath)
        assertEquals("application/vnd.github+json", request.headers["Accept"])
        assertTrue(request.headers["User-Agent"]!!.isNotBlank())
    }

    @Test
    fun `the same version, or an older one, is up to date`() = runTest {
        server.enqueue(ok(release(tag = "v0.1.0")))
        assertEquals(CheckResult.UpToDate, checker().check(installed))
        server.enqueue(ok(release(tag = "v0.0.9")))
        assertEquals(CheckResult.UpToDate, checker().check(installed))
    }

    @Test
    fun `the installed version carries a debug suffix and still compares`() = runTest {
        server.enqueue(ok(release(tag = "v0.1.0")))
        assertEquals(CheckResult.UpToDate, checker().check(AppVersion.parse("0.1.0-debug")!!))
    }

    @Test
    fun `a draft or a pre-release is never offered`() = runTest {
        server.enqueue(ok(release(draft = true)))
        assertEquals(CheckResult.UpToDate, checker().check(installed))
        server.enqueue(ok(release(prerelease = true)))
        assertEquals(CheckResult.UpToDate, checker().check(installed))
    }

    @Test
    fun `a newer release with no app file attached says so instead of offering nothing`() = runTest {
        server.enqueue(ok(release(assets = "[]")))
        val failed = assertIs<CheckResult.Failed>(checker().check(installed))
        assertTrue("no app file" in failed.message, failed.message)
        server.enqueue(ok(release(assets = """[{"name":"notes.txt","size":10,"browser_download_url":"https://github.com/x/notes.txt"}]""")))
        assertIs<CheckResult.Failed>(checker().check(installed))
    }

    @Test
    fun `a tag that is not a version is skipped with a reason`() = runTest {
        server.enqueue(ok(release(tag = "nightly")))
        val failed = assertIs<CheckResult.Failed>(checker().check(installed))
        assertTrue("nightly" in failed.message, failed.message)
    }

    @Test
    fun `a download that points outside GitHub is refused`() = runTest {
        server.enqueue(ok(release()))
        val failed = assertIs<CheckResult.Failed>(checker(trusted = false).check(installed))
        assertTrue("outside GitHub" in failed.message, failed.message)
    }

    @Test
    fun `an app file of no size, or an unreadable size, is refused`() = runTest {
        server.enqueue(ok(release(assets = """[{"name":"signal-lab-0.2.0.apk","size":0,"browser_download_url":"https://github.com/a/b.apk"}]""")))
        assertIs<CheckResult.Failed>(checker().check(installed))
        server.enqueue(ok(release(assets = """[{"name":"signal-lab-0.2.0.apk","browser_download_url":"https://github.com/a/b.apk"}]""")))
        assertIs<CheckResult.Failed>(checker().check(installed))
    }

    @Test
    fun `the app file is the one named signal-lab, whatever order the assets come in`() = runTest {
        server.enqueue(
            ok(
                release(
                    assets = """[{"name":"first.apk","size":5,"browser_download_url":"https://github.com/a/first.apk"},
                        {"name":"signal-lab-0.2.0.apk","size":9,"browser_download_url":"https://github.com/a/signal-lab-0.2.0.apk"},
                        {"name":"last.apk","size":7,"browser_download_url":"https://github.com/a/last.apk"}]""",
                ),
            ),
        )
        val r = assertIs<CheckResult.Newer>(checker().check(installed)).release
        assertTrue(r.apkUrl.endsWith("signal-lab-0.2.0.apk"))
        assertEquals(9L, r.apkBytes)
    }

    @Test
    fun `with no asset named signal-lab, the first app file is used`() = runTest {
        server.enqueue(
            ok(
                release(
                    assets = """[{"name":"notes.txt","size":1,"browser_download_url":"https://github.com/a/notes.txt"},
                        {"name":"first.apk","size":5,"browser_download_url":"https://github.com/a/first.apk"},
                        {"name":"last.apk","size":7,"browser_download_url":"https://github.com/a/last.apk"}]""",
                ),
            ),
        )
        val r = assertIs<CheckResult.Newer>(checker().check(installed)).release
        assertTrue(r.apkUrl.endsWith("first.apk"))
    }

    @Test
    fun `GitHub's answers are turned into plain sentences`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("{}").build())
        assertTrue("No release" in assertIs<CheckResult.Failed>(checker().check(installed)).message)
        server.enqueue(MockResponse.Builder().code(403).body("{}").build())
        assertTrue("limiting" in assertIs<CheckResult.Failed>(checker().check(installed)).message)
        server.enqueue(MockResponse.Builder().code(429).body("{}").build())
        assertTrue("limiting" in assertIs<CheckResult.Failed>(checker().check(installed)).message)
        server.enqueue(MockResponse.Builder().code(500).body("{}").build())
        assertTrue("error 500" in assertIs<CheckResult.Failed>(checker().check(installed)).message)
    }

    @Test
    fun `an answer that is not JSON is reported, not crashed on`() = runTest {
        server.enqueue(ok("<html>not json</html>"))
        assertTrue("could not be read" in assertIs<CheckResult.Failed>(checker().check(installed)).message)
    }

    @Test
    fun `no network is reported as such`() = runTest {
        val offline = OkHttpClient.Builder().addInterceptor { throw IOException("no route") }.build()
        val failed = assertIs<CheckResult.Failed>(checker(http = offline).check(installed))
        assertTrue("Could not reach GitHub" in failed.message, failed.message)
    }

    @Test
    fun `only https links on GitHub's own hosts are trusted`() {
        assertTrue(UpdateChecker.isGitHubHost("https://github.com/ikverse/Signal-Lab-Android/releases/download/v1/a.apk".toHttpUrl()))
        assertTrue(UpdateChecker.isGitHubHost("https://objects.githubusercontent.com/x".toHttpUrl()))
        assertTrue(UpdateChecker.isGitHubHost("https://release-assets.githubusercontent.com/x".toHttpUrl()))
        assertFalse(UpdateChecker.isGitHubHost("http://github.com/a.apk".toHttpUrl()))
        assertFalse(UpdateChecker.isGitHubHost("https://evil.example/a.apk".toHttpUrl()))
        assertFalse(UpdateChecker.isGitHubHost("https://github.com.evil.example/a.apk".toHttpUrl()))
        assertFalse(UpdateChecker.isGitHubHost("https://notgithub.com/a.apk".toHttpUrl()))
        assertFalse(UpdateChecker.isGitHubHost("https://githubusercontent.com.evil.example/a.apk".toHttpUrl()))
        // A host that merely ends with the same letters is somebody else's.
        assertFalse(UpdateChecker.isGitHubHost("https://evilgithubusercontent.com/a.apk".toHttpUrl()))
        assertFalse(UpdateChecker.isGitHubHost("https://evilgithub.com/a.apk".toHttpUrl()))
    }

    @Test
    fun `the repository it asks is this app's own`() {
        assertEquals("https://api.github.com/repos/ikverse/Signal-Lab-Android/releases/latest", UpdateChecker.LATEST_URL)
    }
}
