package app.codeg.android.agentconsole.permission

/**
 * Agent Console overlay — deterministic risk assessment with target anchoring.
 *
 * Core rule: `rm -rf` danger is decided by its TARGET, not by the command name.
 * Unknown targets are never silently downgraded — UNKNOWN is surfaced.
 *
 * Modification for Agent Console overlay by ldohgfsdu (2026).
 */
object RiskAssessor {

    private val CREDENTIAL_PATH = Regex(
        """(gradle\.properties|\.aws|\.ssh|\.netrc|credentials|secrets|\.env|token|api[_-]?key)""",
        RegexOption.IGNORE_CASE,
    )

    fun assess(classification: CommandClassification, cwd: String? = null, rawCommand: String = ""): RiskLevel {
        if (!classification.confident) return RiskLevel.UNKNOWN

        val flags = classification.flags
        val deleteTargets = classification.deleteTargets
        val commandText = rawCommand + " " + classification.keyPaths.joinToString(" ") + " " + classification.exclusions.joinToString(" ")

        // 1. Delete-target anchoring.
        if (flags.deletesFiles) {
            if (deleteTargets.isEmpty()) return RiskLevel.UNKNOWN // rm with unresolvable target
            var worst = RiskLevel.LOW
            for (t in deleteTargets) {
                val level = deleteTargetRisk(t, cwd)
                if (level == RiskLevel.BLOCKED) return RiskLevel.BLOCKED
                if (level.ordinal > worst.ordinal) worst = level
            }
            if (worst == RiskLevel.HIGH) return RiskLevel.HIGH
            if (worst == RiskLevel.UNKNOWN) return RiskLevel.UNKNOWN
            // Confined deletes with nothing else dangerous going on are LOW.
            val nothingElse = !flags.modifiesSource && !flags.networkAccess &&
                !flags.installsPackages && !flags.runsTests && CommandClass.BUILD !in classification.classes
            if (worst == RiskLevel.LOW && nothingElse) return RiskLevel.LOW
        }

        // 2. Credential handling.
        if (CREDENTIAL_PATH.containsMatchIn(commandText)) {
            // Reading credentials or sending them over the network is HIGH.
            val readsCredential = Regex("""(cat|head|tail|cp|curl|wget)\s+\S*(gradle\.properties|\.aws|\.ssh|\.netrc|credentials|secrets|\.env|token|api[_-]?key)""", RegexOption.IGNORE_CASE)
                .containsMatchIn(commandText)
            if (readsCredential) return RiskLevel.HIGH
            if (flags.networkAccess && CREDENTIAL_PATH.containsMatchIn(commandText)) return RiskLevel.HIGH
        }

        // 3. Git risk derives from the concrete operation + arguments, never the
        // bare command name (P0 corrective). Placed before the network check so
        // push --force is HIGH even though push is also a network op.
        if (CommandClass.GIT in classification.classes) {
            val ga = classification.gitAssessment
            if (ga == null || !ga.confident) return RiskLevel.UNKNOWN
            return when (ga.opClass) {
                GitOpClass.READ_ONLY -> RiskLevel.LOW
                GitOpClass.LOCAL_MUTATE -> RiskLevel.MEDIUM
                GitOpClass.LOCAL_DESTRUCTIVE -> RiskLevel.HIGH
                GitOpClass.REMOTE_WRITE -> RiskLevel.MEDIUM
                GitOpClass.REMOTE_DESTRUCTIVE -> RiskLevel.HIGH
                GitOpClass.UNKNOWN -> RiskLevel.UNKNOWN
            }
        }

        // 4. Network exfil: curl/wget with data/upload flags.
        if (flags.networkAccess && Regex("""curl\s+.*(-d|--data|-F|--form|--upload-file)""").containsMatchIn(commandText)) {
            return RiskLevel.HIGH
        }

        // 4. Source modification.
        if (flags.modifiesSource) return RiskLevel.MEDIUM

        // 5. Package install / network.
        if (flags.installsPackages) return RiskLevel.MEDIUM

        // 6. Build/test with deletes confined to /tmp: MEDIUM.
        if (flags.runsTests || CommandClass.BUILD in classification.classes) {
            if (flags.deletesFiles || flags.networkAccess) return RiskLevel.MEDIUM
            return RiskLevel.MEDIUM
        }

        if (flags.networkAccess) return RiskLevel.MEDIUM

        if (classification.classes.all { it == CommandClass.READ_ONLY }) {
            return RiskLevel.LOW
        }

        return RiskLevel.MEDIUM
    }

    /** Risk of a single delete target, anchored against cwd and known-safe roots. */
    private fun deleteTargetRisk(target: String, cwd: String?): RiskLevel {
        val t = target.trim().trim('"', '\'')
        if (t.isEmpty()) return RiskLevel.UNKNOWN
        if (t == "/" || t == "~" || t == "\$HOME") return RiskLevel.BLOCKED
        if (t.startsWith("$")) return RiskLevel.UNKNOWN // unresolved variable
        if (t == "/root" || t == "/home") return RiskLevel.BLOCKED
        if (t == "/root/code" || (cwd != null && t == cwd)) return RiskLevel.BLOCKED
        if (cwd != null && t.startsWith(cwd.trimEnd('/') + "/") && !t.startsWith(cwd.trimEnd('/') + "/build/")) {
            return RiskLevel.HIGH // deleting inside the project, not just build outputs
        }
        if (t.startsWith("/tmp/") || t.startsWith("/var/tmp/") || t.startsWith("/tmp/opencode/")) {
            return RiskLevel.LOW // confined to scratch space
        }
        return RiskLevel.MEDIUM
    }
}
