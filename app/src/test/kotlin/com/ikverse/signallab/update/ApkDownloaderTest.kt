package com.ikverse.signallab.update

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApkDownloaderTest {
    @get:Rule
    val temp = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    private val bytes = ByteArray(200_000) { (it % 251).toByte() }

    private fun release(size: Long = bytes.size.toLong(), version: AppVersion = AppVersion(0, 2, 0)) =
        Release(version, "", server.url("/a.apk").toString(), size)

    private fun serve(body: ByteArray = bytes) {
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(body)).build())
    }

    private val updatesDir by lazy { temp.newFolder("updates") }

    private fun downloader(dir: java.io.File = updatesDir, http: OkHttpClient = OkHttpClient()) = ApkDownloader(http, dir)

    @Test
    fun `a complete download is saved under its real name, byte for byte, and progress reaches the full size`() = runTest {
        serve()
        val seen = ArrayList<Long>()
        val file = downloader().download(release()) { seen += it }
        assertEquals("signal-lab-0.2.0.apk", file.name)
        assertContentEquals(bytes, file.readBytes())
        assertEquals(bytes.size.toLong(), seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> b > a })
        assertTrue(file.parentFile!!.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun `a download that ends early leaves nothing behind and says how far it got`() = runTest {
        serve(bytes.copyOf(50_000))
        val dir = updatesDir
        val e = assertFailsWith<UpdateException> { downloader(dir).download(release()) {} }
        assertTrue("stopped early" in e.message!!, e.message)
        assertTrue("50000" in e.message!!, e.message)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun `a download larger than the release says is stopped and removed`() = runTest {
        serve(bytes)
        val dir = updatesDir
        val e = assertFailsWith<UpdateException> { downloader(dir).download(release(size = 100_000)) {} }
        assertTrue("larger" in e.message!!, e.message)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun `a connection that breaks halfway is an interrupted download, with nothing left behind`() = runTest {
        serve()
        val dir = updatesDir
        val breaking = OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val source = response.body.source()
            val cut = object : okio.ForwardingSource(source) {
                var left = 70_000L
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (left <= 0) throw IOException("connection reset")
                    val n = super.read(sink, minOf(byteCount, left))
                    if (n > 0) left -= n
                    return n
                }
            }
            response.newBuilder().body(cut.buffer().asResponseBody("application/octet-stream".toMediaType(), response.body.contentLength())).build()
        }.build()
        val e = assertFailsWith<UpdateException> { downloader(dir, breaking).download(release()) {} }
        assertTrue("interrupted" in e.message!!, e.message)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun `an error status is reported with its code and saves nothing`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("gone").build())
        val dir = updatesDir
        val e = assertFailsWith<UpdateException> { downloader(dir).download(release()) {} }
        assertTrue("404" in e.message!!, e.message)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun `a size of zero, or an absurd one, is refused before any request is made`() = runTest {
        assertFailsWith<UpdateException> { downloader().download(release(size = 0)) {} }
        assertFailsWith<UpdateException> { downloader().download(release(size = ApkDownloader.MAX_BYTES + 1)) {} }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an older download is cleared when a new one starts`() = runTest {
        val dir = updatesDir
        val old = java.io.File(dir, "signal-lab-0.1.5.apk").also { it.writeBytes(byteArrayOf(1)) }
        serve()
        downloader(dir).download(release()) {}
        assertFalse(old.exists())
    }

    @Test
    fun `clear removes what was downloaded`() {
        val dir = updatesDir
        java.io.File(dir, "signal-lab-0.2.0.apk").writeBytes(byteArrayOf(1))
        java.io.File(dir, "signal-lab-0.2.0.apk.part").writeBytes(byteArrayOf(1))
        downloader(dir).clear()
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun `clear on a folder that does not exist is fine`() {
        downloader(java.io.File(temp.root, "never-made")).clear()
    }
}
