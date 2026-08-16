package app.codeg.android.agentconsole.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance fixture = the REAL Gradle isolation permission request from
 * conversation 12 (changjietu, OpenCode) — the command that overflowed the
 * upstream permission card. Digest must summarize it per the user-designed
 * target shape, and the raw command must be preserved verbatim for audit.
 */
class PermissionSummarizerTest {

    val REAL_GRADLE_ISOLATION_COMMAND = """
        cd /root/code/changjietu && ISOLATED=/tmp/opencode/gradle-isolated-home && rm -rf "${'$'}ISOLATED" && mkdir -p "${'$'}ISOLATED" && cp -r ~/.gradle/wrapper "${'$'}ISOLATED/wrapper" 2>/dev/null; cp -r ~/.gradle/caches "${'$'}ISOLATED/caches" 2>/dev/null; cp -r ~/.gradle/native "${'$'}ISOLATED/native" 2>/dev/null || true; cp -r ~/.gradle/jdks "${'$'}ISOLATED/jdks" 2>/dev/null || true; echo "isolated has gradle.properties? (expect not found):"; ls "${'$'}ISOLATED/gradle.properties" 2>&1 | head -1; export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64; export PATH=${'$'}JAVA_HOME/bin:${'$'}PATH; export GRADLE_USER_HOME="${'$'}ISOLATED"; echo "=== isolated :app:testDebugUnitTest ==="; ./gradlew :app:testDebugUnitTest --console=plain 2>&1 | grep -E "BUILD|FAILED|tests|e: |wrapper|aapt2" | head; echo "TEST_EXIT=${'$'}{PIPESTATUS[0]}"
    """.trimIndent()

    // ----- Acceptance fixture (user-authorized gate) -----

    @Test
    fun `acceptance - real gradle isolation command digests correctly`() {
        val digest = PermissionSummarizer.summarize(REAL_GRADLE_ISOLATION_COMMAND, cwd = "/root/code/changjietu")

        assertTrue("confident", digest.confident)
        assertEquals(CommandClass.REPRODUCIBILITY_TEST, digest.commandClass)
        assertEquals(RiskLevel.MEDIUM, digest.riskLevel)
        assertTrue("summary mentions isolation", digest.summary.contains("隔离"))
        assertEquals("验证项目是否依赖全局 Gradle 配置", digest.purpose)

        val impacts = digest.impacts
        assertTrue("delete+rebuild", impacts.any { it.contains("删除并重建 /tmp/opencode/gradle-isolated-home") })
        assertTrue("read gradle caches", impacts.any { it.contains("读取现有 Gradle 缓存") && it.contains("wrapper") && it.contains("caches") && it.contains("native") })
        assertTrue("excludes gradle.properties", impacts.any { it.contains("不复制全局 gradle.properties") })
        assertTrue("no source modification", impacts.any { it.contains("不修改项目源码") })
        assertTrue("executes test task", impacts.any { it.contains("执行 :app:testDebugUnitTest") })
    }

    @Test
    fun `acceptance - raw command preserved verbatim for audit`() {
        val digest = PermissionSummarizer.summarize(REAL_GRADLE_ISOLATION_COMMAND)
        assertEquals(REAL_GRADLE_ISOLATION_COMMAND, digest.rawCommand)
        assertTrue(digest.rawCommand.contains("rm -rf \"\$ISOLATED\""))
        assertTrue(digest.rawCommand.contains("GRADLE_USER_HOME"))
    }

    @Test
    fun `acceptance - default view does not leak raw shell into summary fields`() {
        val digest = PermissionSummarizer.summarize(REAL_GRADLE_ISOLATION_COMMAND)
        val defaultViewText = (digest.summary + " " + digest.purpose.orEmpty() + " " + digest.impacts.joinToString(" "))
        assertFalse("raw shell operators hidden by default", defaultViewText.contains("&&"))
        assertFalse(defaultViewText.contains("2>/dev/null"))
        assertFalse(defaultViewText.contains("PIPESTATUS"))
    }

    // ----- Dangerous delete -----

    @Test
    fun `dangerous delete of home is BLOCKED and visible`() {
        val digest = PermissionSummarizer.summarize("rm -rf ~")
        assertEquals(RiskLevel.BLOCKED, digest.riskLevel)
        assertTrue(digest.impacts.any { it.contains("删除文件") })
    }

    @Test
    fun `dangerous delete of project source is HIGH`() {
        val digest = PermissionSummarizer.summarize(
            "rm -rf /root/code/changjietu/src",
            cwd = "/root/code/changjietu",
        )
        assertEquals(RiskLevel.HIGH, digest.riskLevel)
    }

    // ----- Ordinary test command -----

    @Test
    fun `ordinary test command digests with test summary`() {
        val digest = PermissionSummarizer.summarize("./gradlew :app:testDebugUnitTest")
        assertEquals(RiskLevel.MEDIUM, digest.riskLevel)
        assertTrue(digest.confident)
        assertTrue(digest.summary.contains("测试"))
    }

    // ----- Unknown fallback -----

    @Test
    fun `unknown command shows review-required - never fakes low risk`() {
        val digest = PermissionSummarizer.summarize("xyzzypoo --frobnicate 42")
        assertFalse(digest.confident)
        assertEquals(RiskLevel.UNKNOWN, digest.riskLevel)
        assertTrue(digest.summary.contains("需要审查"))
        assertEquals("xyzzypoo --frobnicate 42", digest.rawCommand)
    }

    @Test
    fun `rm with unresolvable target is UNKNOWN - not downgraded`() {
        val digest = PermissionSummarizer.summarize("rm -rf \"\$SOMEWHERE\"")
        assertEquals(RiskLevel.UNKNOWN, digest.riskLevel)
        assertTrue(digest.summary.contains("需要审查"))
    }

    // ----- Long command handling -----

    @Test
    fun `long multi-line command keeps raw intact and digest compact`() {
        val long = (1..40).joinToString(" && ") { "echo step$it" }
        val digest = PermissionSummarizer.summarize(long)
        assertEquals(long, digest.rawCommand) // preserved, never truncated
        assertTrue(digest.summary.length < 60) // digest is compact
    }
}
