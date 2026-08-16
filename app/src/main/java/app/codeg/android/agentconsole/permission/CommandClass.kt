package app.codeg.android.agentconsole.permission

/**
 * Agent Console overlay — deterministic command classification.
 *
 * Modification for Agent Console overlay by ldohgfsdu (2026).
 * Android is NOT the source of truth; this only classifies for display.
 */
enum class CommandClass(val displayName: String) {
    READ_ONLY("只读查看"),
    FILE_WRITE("修改文件"),
    FILE_DELETE("删除文件"),
    NETWORK("网络访问"),
    PACKAGE_INSTALL("安装软件包"),
    GIT("Git 操作"),
    BUILD("构建"),
    TEST("运行测试"),
    REPRODUCIBILITY_TEST("隔离构建验证"),
    SHELL("Shell 命令"),
    UNKNOWN("需要审查"),
}

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    BLOCKED,
    UNKNOWN,
}

/** Result of deterministic command classification. */
data class CommandClassification(
    val classes: Set<CommandClass>,
    val flags: CommandFlags,
    /** Var name -> assigned path (e.g. ISOLATED -> /tmp/opencode/gradle-isolated-home). */
    val variablePaths: Map<String, String>,
    /** Explicit paths referenced by the command (cd / rm targets / cp targets / var values). */
    val keyPaths: List<String>,
    /** Targets of delete operations (rm/rmdir). Empty if none. */
    val deleteTargets: List<String>,
    /** Explicitly excluded / avoided items (e.g. global gradle.properties). */
    val exclusions: List<String>,
    /** False when parsing could not confidently identify the command shape. */
    val confident: Boolean,
) {
    companion object {
        val EMPTY = CommandClassification(
            classes = emptySet(),
            flags = CommandFlags(),
            variablePaths = emptyMap(),
            keyPaths = emptyList(),
            deleteTargets = emptyList(),
            exclusions = emptyList(),
            confident = false,
        )
    }
}

data class CommandFlags(
    val modifiesSource: Boolean = false,
    val deletesFiles: Boolean = false,
    val networkAccess: Boolean = false,
    val runsTests: Boolean = false,
    val installsPackages: Boolean = false,
    val createsDirectories: Boolean = false,
)
