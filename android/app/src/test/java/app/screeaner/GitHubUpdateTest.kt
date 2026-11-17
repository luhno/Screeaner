package app.screeaner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdateTest {
    @Test
    fun versionOrder() {
        assertTrue(GitHubUpdate.isNewer("0.10.0", "0.9.3 (11)"))
        assertTrue(GitHubUpdate.isNewer("v1.0", "0.9.9"))
        assertTrue(GitHubUpdate.isNewer("0.9.1", "0.9"))
        assertFalse(GitHubUpdate.isNewer("0.9.0", "0.9.0 (11)"))
        assertFalse(GitHubUpdate.isNewer("0.9.0-beta", "0.9.0"))
        assertFalse(GitHubUpdate.isNewer("0.8.9", "0.9.0"))
    }
}
