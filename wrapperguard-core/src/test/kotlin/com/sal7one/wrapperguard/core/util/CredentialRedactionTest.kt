package com.sal7one.wrapperguard.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CredentialRedactionTest {

    @Test
    fun masksQuotedSecretAssignments() {
        val (out, changed) = CredentialRedaction.mask("""password = "hunter2"""")
        assertEquals("""password = "[redacted]"""", out)
        assertTrue(changed)
    }

    @Test
    fun masksBareSecretAssignments() {
        val (out, changed) = CredentialRedaction.mask("token=abc123def456")
        assertEquals("token=[redacted]", out)
        assertTrue(changed)
    }

    @Test
    fun masksSecretWithSpacesInsideQuotes() {
        val (out, _) = CredentialRedaction.mask("secret: 'my secret value'")
        assertEquals("secret: '[redacted]'", out)
    }

    @Test
    fun masksAuthorizationBearerToken() {
        val (out, changed) = CredentialRedaction.mask("""Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc""")
        assertEquals("Authorization: Bearer [redacted]", out)
        assertTrue(changed)
    }

    @Test
    fun masksAuthorizationBasicHeader() {
        val (out, _) = CredentialRedaction.mask("""authorization = "Basic dXNlcjpwYXNzd29yZA=="""")
        assertEquals("""authorization = "Basic [redacted]"""", out)
    }

    @Test
    fun masksUrlUserinfoPassword() {
        val (out, changed) = CredentialRedaction.mask("https://deploy:hunter2@evil.example.com/repo")
        assertEquals("https://deploy:[redacted]@evil.example.com/repo", out)
        assertTrue(changed)
    }

    @Test
    fun masksEverythingInsideCredentialsBlock() {
        val input = """credentials { username = 'svc' password = 's3cret!' }"""
        val (out, changed) = CredentialRedaction.mask(input)
        assertEquals("""credentials { username = '[redacted]' password = '[redacted]' }""", out)
        assertTrue(changed)
    }

    @Test
    fun leavesBenignTextUntouched() {
        val input = "implementation(\"com.android.tools.build:gradle:8.7.0\")"
        val (out, changed) = CredentialRedaction.mask(input)
        assertEquals(input, out)
        assertFalse(changed)
    }

    @Test
    fun isIdempotentOnText() {
        val input = """token = "abc123" ; curl -u alice:s3cret https://host ; credentials { password = 'x' }"""
        val once = CredentialRedaction.mask(input).first
        val twice = CredentialRedaction.mask(once).first
        assertEquals(once, twice)
    }
}
