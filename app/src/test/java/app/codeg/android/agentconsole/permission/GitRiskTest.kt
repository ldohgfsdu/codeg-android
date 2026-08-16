package app.codeg.android.agentconsole.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 corrective regression: git risk must come from the concrete operation +
 * arguments. Destructive git commands must NEVER produce LOW. Unresolvable git
 * forms must be UNKNOWN — never silently downgraded.
 */
class GitRiskTest {

    private fun assess(cmd: String, cwd: String? = "/root/code/changjietu"): RiskLevel =
        RiskAssessor.assess(CommandClassifier.classify(cmd, cwd), cwd = cwd, rawCommand = cmd)

    private fun gitClass(cmd: String): GitOpClass =
        CommandClassifier.classify(cmd).gitAssessment!!.opClass

    // ----- Read-only: LOW -----

    @Test
    fun `git status is LOW`() {
        assertEquals(GitOpClass.READ_ONLY, gitClass("git status"))
        assertEquals(RiskLevel.LOW, assess("git status"))
    }

    @Test
    fun `git diff is LOW`() {
        assertEquals(GitOpClass.READ_ONLY, gitClass("git diff"))
        assertEquals(RiskLevel.LOW, assess("git diff"))
    }

    @Test
    fun `git log and show are LOW`() {
        assertEquals(RiskLevel.LOW, assess("git log --oneline -5"))
        assertEquals(RiskLevel.LOW, assess("git show HEAD"))
    }

    @Test
    fun `git branch listing is LOW`() {
        assertEquals(RiskLevel.LOW, assess("git branch --list"))
        assertEquals(RiskLevel.LOW, assess("git branch -a"))
        assertEquals(RiskLevel.LOW, assess("git remote -v"))
    }

    // ----- Local mutations: MEDIUM -----

    @Test
    fun `git add commit switch checkout-branch merge rebase cherry-pick stash are MEDIUM`() {
        for (cmd in listOf(
            "git add src/Main.kt",
            "git commit -m test",
            "git switch feature",
            "git checkout main",
            "git merge feature",
            "git rebase main",
            "git cherry-pick abc123",
            "git stash",
        )) {
            assertEquals("$cmd should be MEDIUM", RiskLevel.MEDIUM, assess(cmd))
        }
    }

    // ----- Local destructive: HIGH -----

    @Test
    fun `git reset --hard is HIGH`() {
        assertEquals(GitOpClass.LOCAL_DESTRUCTIVE, gitClass("git reset --hard HEAD~1"))
        assertEquals(RiskLevel.HIGH, assess("git reset --hard HEAD~1"))
    }

    @Test
    fun `git clean -fdx is HIGH`() {
        assertEquals(RiskLevel.HIGH, assess("git clean -fdx"))
    }

    @Test
    fun `git restore dot is HIGH`() {
        assertEquals(RiskLevel.HIGH, assess("git restore ."))
    }

    @Test
    fun `git checkout -- dot is HIGH`() {
        assertEquals(RiskLevel.HIGH, assess("git checkout -- ."))
    }

    @Test
    fun `git branch -D is HIGH`() {
        assertEquals(RiskLevel.HIGH, assess("git branch -D test"))
    }

    @Test
    fun `git stash drop is HIGH`() {
        assertEquals(RiskLevel.HIGH, assess("git stash drop"))
    }

    @Test
    fun `git tag -d is HIGH`() {
        assertEquals(RiskLevel.HIGH, assess("git tag -d v1.0"))
    }

    // ----- Remote: MEDIUM / HIGH -----

    @Test
    fun `plain git push is MEDIUM - not HIGH and not LOW`() {
        assertEquals(GitOpClass.REMOTE_WRITE, gitClass("git push origin main"))
        assertEquals(RiskLevel.MEDIUM, assess("git push origin main"))
    }

    @Test
    fun `git push --force is HIGH`() {
        assertEquals(RiskLevel.HIGH, assess("git push --force origin main"))
    }

    @Test
    fun `git push --force-with-lease is HIGH`() {
        assertEquals(RiskLevel.HIGH, assess("git push --force-with-lease origin main"))
    }

    @Test
    fun `git push -f short flag is HIGH`() {
        assertEquals(RiskLevel.HIGH, assess("git push -f origin main"))
    }

    @Test
    fun `remote branch deletion via push is HIGH`() {
        assertEquals(RiskLevel.HIGH, assess("git push origin :test"))
        assertEquals(RiskLevel.HIGH, assess("git push origin --delete test"))
    }

    // ----- Unresolvable: UNKNOWN -----

    @Test
    fun `unknown git subcommand is UNKNOWN - never LOW`() {
        val risk = assess("git frobnicate --weird")
        assertEquals(RiskLevel.UNKNOWN, risk)
    }

    @Test
    fun `git with variable expansion is UNKNOWN`() {
        assertEquals(RiskLevel.UNKNOWN, assess("git reset \$FLAGS"))
        assertEquals(RiskLevel.UNKNOWN, assess("git push \$ARGS"))
    }

    @Test
    fun `bare git is UNKNOWN`() {
        assertEquals(RiskLevel.UNKNOWN, assess("git"))
    }

    // ----- Never LOW for destructive -----

    @Test
    fun `destructive git commands are never LOW`() {
        val destructive = listOf(
            "git reset --hard HEAD~1",
            "git clean -fdx",
            "git clean -f",
            "git restore .",
            "git checkout -- .",
            "git branch -D test",
            "git push --force origin main",
            "git push --force-with-lease origin main",
            "git push origin :main",
            "git tag -d v1.0",
            "git stash drop",
            "git checkout -B main",
        )
        for (cmd in destructive) {
            val risk = assess(cmd)
            assertTrue("$cmd must not be LOW (was $risk)", risk != RiskLevel.LOW)
            assertTrue("$cmd must not be UNKNOWN (was $risk)", risk != RiskLevel.UNKNOWN)
            assertEquals("$cmd must be HIGH", RiskLevel.HIGH, risk)
        }
    }

    // ----- Classification integrity -----

    @Test
    fun `git assessment survives classification model`() {
        val c = CommandClassifier.classify("git push --force origin main")
        assertTrue(CommandClass.GIT in c.classes)
        assertEquals(GitOpClass.REMOTE_DESTRUCTIVE, c.gitAssessment?.opClass)
        assertTrue(c.confident)
    }

    @Test
    fun `multi-segment git keeps the most dangerous assessment`() {
        val c = CommandClassifier.classify("git status && git push --force origin main")
        assertEquals(GitOpClass.REMOTE_DESTRUCTIVE, c.gitAssessment?.opClass)
        assertTrue(c.confident) // both segments understood
    }
}
