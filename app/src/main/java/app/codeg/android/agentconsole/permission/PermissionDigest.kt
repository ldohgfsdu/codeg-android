package app.codeg.android.agentconsole.permission

/**
 * Agent Console overlay — permission digest (what the user sees by default).
 *
 * rawCommand is always preserved verbatim for audit; the digest is the
 * human-readable projection. Never invented: when parsing is not confident,
 * confident=false and the UI must show "需要审查" (UNKNOWN), never a fake low risk.
 *
 * Modification for Agent Console overlay by ldohgfsdu (2026).
 */
data class PermissionDigest(
    /** One-line action summary (R1). */
    val summary: String,
    /** Why the agent wants to do this (the engineering question being answered). */
    val purpose: String?,
    /** Impact boundary list: what is deleted/read/untouched/executed (R2). */
    val impacts: List<String>,
    val riskLevel: RiskLevel,
    val commandClass: CommandClass,
    val classification: CommandClassification,
    /** Verbose original command — the only place raw shell text lives. */
    val rawCommand: String,
    /** False => parse not confident; UI must show UNKNOWN / 需要审查. */
    val confident: Boolean,
) {
    val modifiesSource: Boolean get() = classification.flags.modifiesSource
    val deletesFiles: Boolean get() = classification.flags.deletesFiles
    val networkAccess: Boolean get() = classification.flags.networkAccess
    val runsTests: Boolean get() = classification.flags.runsTests

    companion object {
        fun unknown(rawCommand: String, reason: String): PermissionDigest = PermissionDigest(
            summary = "需要审查",
            purpose = reason,
            impacts = listOf("无法可靠解析命令内容"),
            riskLevel = RiskLevel.UNKNOWN,
            commandClass = CommandClass.UNKNOWN,
            classification = CommandClassification.EMPTY,
            rawCommand = rawCommand,
            confident = false,
        )
    }
}
