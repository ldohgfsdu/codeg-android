package app.codeg.android.agentconsole.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandClassifierTest {

    // ----- Segmentation -----

    @Test
    fun `split is quote-aware - operator inside quotes is not a boundary`() {
        val segments = CommandClassifier.splitSegments(
            """echo "a && b"; ls -la"""
        )
        assertEquals(2, segments.size)
        assertEquals("""echo "a && b"""", segments[0])
        assertEquals("ls -la", segments[1])
    }

    @Test
    fun `split handles pipe and semicolon chains`() {
        val segments = CommandClassifier.splitSegments(
            "./gradlew test 2>&1 | grep -E \"BUILD|FAILED\" | head; echo done"
        )
        assertEquals(4, segments.size)
        assertEquals("./gradlew test 2>&1", segments[0])
    }

    // ----- Classification -----

    @Test
    fun `classifies delete with variable-resolved target`() {
        val c = CommandClassifier.classify(
            """ISOLATED=/tmp/opencode/iso && rm -rf "${'$'}ISOLATED""""
        )
        assertTrue(CommandClass.FILE_DELETE in c.classes)
        assertTrue(c.flags.deletesFiles)
        assertEquals(listOf("/tmp/opencode/iso"), c.deleteTargets)
        assertEquals("/tmp/opencode/iso", c.variablePaths["ISOLATED"])
        assertTrue(c.confident)
    }

    @Test
    fun `classifies build and test`() {
        val c = CommandClassifier.classify("./gradlew :app:testDebugUnitTest --console=plain")
        assertTrue(CommandClass.BUILD in c.classes)
        assertTrue(CommandClass.TEST in c.classes)
        assertTrue(c.flags.runsTests)
    }

    @Test
    fun `classifies network and package install`() {
        val c = CommandClassifier.classify("pip install requests && curl https://example.com/x")
        assertTrue(CommandClass.PACKAGE_INSTALL in c.classes)
        assertTrue(CommandClass.NETWORK in c.classes)
        assertTrue(c.flags.networkAccess)
        assertTrue(c.flags.installsPackages)
    }

    @Test
    fun `classifies git operations`() {
        val c = CommandClassifier.classify("git status && git fetch origin")
        assertTrue(CommandClass.GIT in c.classes)
        assertTrue(c.flags.networkAccess) // fetch is network
    }

    @Test
    fun `classifies read-only probe`() {
        val c = CommandClassifier.classify("ls -la && cat README.md && find . -name '*.kt'")
        assertTrue(CommandClass.READ_ONLY in c.classes)
        assertFalse(c.flags.modifiesSource)
    }

    @Test
    fun `classifies source modification via sed -i`() {
        val c = CommandClassifier.classify("sed -i 's/old/new/' src/Main.kt")
        assertTrue(CommandClass.FILE_WRITE in c.classes)
        assertTrue(c.flags.modifiesSource)
    }

    @Test
    fun `echo with stderr redirect is still understood`() {
        val c = CommandClassifier.classify("""ls "${'$'}X/gradle.properties" 2>&1 | head -1""")
        assertTrue(c.confident)
        assertTrue(CommandClass.READ_ONLY in c.classes)
    }

    @Test
    fun `unknown garbage is not confident`() {
        val c = CommandClassifier.classify("xyzzypoo --frobnicate 42")
        assertFalse(c.confident)
    }

    @Test
    fun `credential file mention becomes an exclusion - not a read`() {
        val c = CommandClassifier.classify("""ls "${'$'}ISOLATED/gradle.properties" 2>&1 | head -1""")
        assertTrue(c.exclusions.any { it.contains("gradle.properties") })
        assertFalse(c.flags.modifiesSource)
    }

    // ----- Reproducibility pattern -----

    @Test
    fun `reproducibility test pattern is recognized`() {
        val cmd = "ISOLATED=/tmp/opencode/gradle-isolated-home && rm -rf \"\$ISOLATED\" && " +
            "mkdir -p \"\$ISOLATED\" && cp -r ~/.gradle/wrapper \"\$ISOLATED/wrapper\" 2>/dev/null; " +
            "ls \"\$ISOLATED/gradle.properties\" 2>&1 | head -1; ./gradlew :app:testDebugUnitTest"
        val c = CommandClassifier.classify(cmd)
        assertTrue(CommandClass.REPRODUCIBILITY_TEST in c.classes)
        assertTrue(c.flags.deletesFiles)
        assertTrue(c.flags.runsTests)
        assertTrue(c.confident)
    }
}
