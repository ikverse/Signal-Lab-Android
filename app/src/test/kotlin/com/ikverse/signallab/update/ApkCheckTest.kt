package com.ikverse.signallab.update

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApkCheckTest {
    private val installed = ApkFacts("com.ikverse.signallab", 100, setOf("aa11"))
    private fun file(
        pkg: String = "com.ikverse.signallab", code: Long = 200, signers: Set<String> = setOf("aa11"),
    ) = ApkFacts(pkg, code, signers)

    @Test
    fun `a newer file of this app, signed with the same key, passes`() {
        assertNull(ApkCheck.problem(file(), installed))
        assertNull(ApkCheck.problem(file(), installed, expectedCode = 200))
    }

    @Test
    fun `a file Android cannot read is refused`() {
        assertTrue("not an app" in ApkCheck.problem(null, installed)!!)
    }

    @Test
    fun `a different app is refused`() {
        assertTrue("different app" in ApkCheck.problem(file(pkg = "com.example.other"), installed)!!)
    }

    @Test
    fun `a file signed with a different key is refused, and says why`() {
        val message = ApkCheck.problem(file(signers = setOf("bb22")), installed)!!
        assertTrue("different key" in message, message)
        assertTrue("Nothing was installed" in message, message)
    }

    @Test
    fun `a file with extra or missing keys is not the same signer`() {
        assertNotNull(ApkCheck.problem(file(signers = setOf("aa11", "bb22")), installed))
        assertNotNull(ApkCheck.problem(file(signers = emptySet()), installed))
    }

    @Test
    fun `no key on the installed side refuses everything rather than trusting an empty match`() {
        assertNotNull(ApkCheck.problem(file(signers = emptySet()), installed.copy(signers = emptySet())))
        assertNotNull(ApkCheck.problem(file(), installed.copy(signers = emptySet())))
    }

    @Test
    fun `the same version or an older one is refused`() {
        assertTrue("not newer" in ApkCheck.problem(file(code = 100), installed)!!)
        assertTrue("not newer" in ApkCheck.problem(file(code = 99), installed)!!)
    }

    @Test
    fun `a file that is not the version its release promised is refused`() {
        val message = ApkCheck.problem(file(code = 300), installed, expectedCode = 200)
        assertEquals("The downloaded file is not the version its release says, so it was not installed.", message)
    }

    @Test
    fun `the key problem is reported before the version problem`() {
        // A foreign file that is also old should say it is foreign: that is the one that matters.
        assertTrue("different key" in ApkCheck.problem(file(code = 50, signers = setOf("bb22")), installed)!!)
    }
}
