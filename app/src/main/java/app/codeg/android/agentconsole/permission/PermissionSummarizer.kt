package app.codeg.android.agentconsole.permission

/**
 * Agent Console overlay — deterministic permission summarizer.
 *
 * Generates the digest shown on the permission card: action summary, purpose,
 * impact boundary list, risk level. Purely rule-based (no LLM). The raw command
 * is preserved verbatim in the digest for audit; it is never mutated.
 *
 * Template follows docs/CODEG_OVERLAY_PERMISSION_CARD_REQUIREMENT.md §3 target
 * shape (user-designed): summary / purpose / impacts / risk / [查看完整命令].
 *
 * Modification for Agent Console overlay by ldohgfsdu (2026).
 */
object PermissionSummarizer {

    private val GRADLE_COPY_ITEMS = listOf("wrapper", "caches", "native", "jdks")

    fun summarize(command: String, cwd: String? = null): PermissionDigest {
        val raw = command
        val classification = CommandClassifier.classify(command, cwd)
        val flags = classification.flags

        if (!classification.confident) {
            return PermissionDigest.unknown(raw, "命令包含无法识别的片段")
        }

        val classes = classification.classes
        val risk = RiskAssessor.assess(classification, cwd, raw)
        if (risk == RiskLevel.UNKNOWN) {
            return PermissionDigest.unknown(raw, "删除目标或关键路径无法可靠解析")
        }

        return when {
            CommandClass.REPRODUCIBILITY_TEST in classes -> reproducibilityDigest(raw, classification, risk)
            classes.contains(CommandClass.UNKNOWN) -> PermissionDigest.unknown(raw, "命令类型无法识别")
            flags.deletesFiles && flags.modifiesSource -> genericDigest(raw, classification, risk)
            else -> genericDigest(raw, classification, risk)
        }
    }

    private fun reproducibilityDigest(
        raw: String,
        c: CommandClassification,
        risk: RiskLevel,
    ): PermissionDigest {
        val impacts = mutableListOf<String>()

        val isolateVar = c.variablePaths.entries.firstOrNull { (n, v) ->
            n.uppercase().contains("ISOLAT") || v.startsWith("/tmp/")
        }
        val isolatePath = isolateVar?.value ?: c.deleteTargets.firstOrNull { it.startsWith("/tmp/") }

        // Delete + rebuild of the isolation dir.
        if (c.flags.deletesFiles) {
            impacts += if (isolatePath != null) "删除并重建 $isolatePath"
            else "删除并重建隔离目录"
        } else if (c.flags.createsDirectories) {
            impacts += if (isolatePath != null) "创建 $isolatePath" else "创建隔离目录"
        }

        // Gradle cache copies: read-only intake from ~/.gradle.
        val copied = GRADLE_COPY_ITEMS.filter { item ->
            c.keyPaths.any { it.contains("/.gradle/$item") || it.contains("~/.gradle/$item") }
        }
        if (copied.isNotEmpty()) impacts += "读取现有 Gradle 缓存（${copied.joinToString("/")}）"

        // Exclusion of global gradle.properties.
        if (c.exclusions.any { it.contains("gradle.properties", ignoreCase = true) }) {
            impacts += "不复制全局 gradle.properties"
        }

        // Source untouched.
        if (!c.flags.modifiesSource) impacts += "不修改项目源码"

        // The build/test task executed.
        val gradleTask = Regex("""(:\S*(test\S*)\b)""").find(raw)?.groupValues?.get(1)
        if (gradleTask != null) impacts += "执行 $gradleTask"
        else if (c.flags.runsTests) impacts += "执行测试"

        val buildTool = when {
            raw.contains("./gradlew") || raw.contains("gradlew") -> "Gradle"
            raw.contains("mvn") -> "Maven"
            raw.contains("make") -> "Make"
            else -> null
        }
        val summary = if (buildTool != null) "运行隔离 $buildTool 单元测试"
        else "运行隔离构建测试"

        val projectName = isolatePath?.substringAfterLast('/') ?: "隔离环境"

        return PermissionDigest(
            summary = summary,
            purpose = "验证项目是否依赖全局 Gradle 配置",
            impacts = impacts,
            riskLevel = risk,
            commandClass = CommandClass.REPRODUCIBILITY_TEST,
            classification = c,
            rawCommand = raw,
            confident = true,
        )
    }

    private fun genericDigest(
        raw: String,
        c: CommandClassification,
        risk: RiskLevel,
    ): PermissionDigest {
        val impacts = mutableListOf<String>()
        if (c.flags.deletesFiles) impacts += "删除文件（${c.deleteTargets.joinToString(", ")}）"
        if (c.flags.modifiesSource) impacts += "修改源码文件"
        if (c.flags.networkAccess) impacts += "访问网络"
        if (c.flags.installsPackages) impacts += "安装软件包"
        if (c.flags.runsTests) impacts += "运行测试"
        if (c.flags.createsDirectories) impacts += "创建目录"
        if (impacts.isEmpty()) impacts += "执行 Shell 命令"

        val primary = when {
            c.flags.runsTests -> CommandClass.TEST
            else -> c.classes.firstOrNull { it != CommandClass.SHELL } ?: CommandClass.SHELL
        }
        val summary = when (primary) {
            CommandClass.READ_ONLY -> "查看文件内容"
            CommandClass.FILE_WRITE -> "修改文件"
            CommandClass.FILE_DELETE -> "删除文件"
            CommandClass.NETWORK -> "网络请求"
            CommandClass.PACKAGE_INSTALL -> "安装软件包"
            CommandClass.GIT -> "Git 操作"
            CommandClass.BUILD -> "构建项目"
            CommandClass.TEST -> "运行测试"
            else -> "执行 Shell 命令"
        }

        return PermissionDigest(
            summary = summary,
            purpose = if (c.keyPaths.isNotEmpty()) "涉及：${c.keyPaths.take(3).joinToString(" · ")}" else null,
            impacts = impacts,
            riskLevel = risk,
            commandClass = primary,
            classification = c,
            rawCommand = raw,
            confident = true,
        )
    }
}
