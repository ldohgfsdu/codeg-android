package app.codeg.android.agentconsole.permission

/**
 * Agent Console overlay — deterministic shell command classifier.
 *
 * Rule-based only: splits on shell operators (quote-aware), classifies each
 * segment, extracts variable-path anchors, delete targets and exclusions.
 * Deliberately conservative: anything unrecognized is reported, never guessed
 * into a benign class.
 *
 * Modification for Agent Console overlay by ldohgfsdu (2026).
 */
object CommandClassifier {

    private val DELETE_CMD = Regex("""^(rm|rmdir|truncate)\b""")
    private val READ_CMD = Regex("""^(ls|cat|head|tail|find|grep|wc|file|stat|pwd|echo|true|false)\b""")
    private val NETWORK_CMD = Regex("""^(curl|wget|rsync)\b""")
    private val GIT_CMD = Regex("""^git\b""")
    private val PACKAGE_CMD = Regex("""^(apt-get|apt|dnf|yum|pacman|brew|pip|pip3|npm|cargo|go)\b.*\binstall\b""")
    private val BUILD_CMD = Regex("""^(\./gradlew|gradlew|gradle|mvn|mvnw|make|cargo)\b""")
    private val TEST_MARKER = Regex("""\btest\b|pytest|testDebugUnitTest|testReleaseUnitTest""")

    private val CREDENTIAL_PATH = Regex(
        """(gradle\.properties|\.aws|\.ssh|\.netrc|credentials|secrets|\.env|token|api[_-]?key)""",
        RegexOption.IGNORE_CASE,
    )
    private val SOURCE_EXT = Regex("""\.(kt|java|py|ts|js|go|rs|c|h|cpp|sh|gradle|kts|xml|yml|yaml|toml|md)(["'\s;>&|]|$)""")

    private const val VAR_NAME = "A-Za-z_"

    fun classify(command: String, cwd: String? = null): CommandClassification {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return CommandClassification.EMPTY

        val segments = splitSegments(trimmed)
        if (segments.isEmpty()) {
            return CommandClassification(
                classes = setOf(CommandClass.UNKNOWN),
                flags = CommandFlags(),
                variablePaths = emptyMap(),
                keyPaths = emptyList(),
                deleteTargets = emptyList(),
                exclusions = emptyList(),
                confident = false,
            )
        }

        var variablePaths = linkedMapOf<String, String>()
        var keyPaths = mutableListOf<String>()
        var deleteTargets = mutableListOf<String>()
        var exclusions = mutableListOf<String>()
        var classes = linkedSetOf<CommandClass>()
        var modifiesSource = false
        var networkAccess = false
        var runsTests = false
        var installsPackages = false
        var createsDirectories = false
        var understood = 0
        var total = 0

        for (seg in segments) {
            total++
            val s = seg.trim()
            if (s.isEmpty()) {
                understood++
                continue
            }
            // Variable assignment anchor: NAME=value (the semantic anchor).
            val assign = Regex("^([${VAR_NAME}][${VAR_NAME}0-9]*)=(.*)$").find(s)
            if (assign != null) {
                val name = assign.groupValues[1]
                val value = assign.groupValues[2].trim().trim('"', '\'')
                variablePaths[name] = value
                keyPaths += value
                understood++
                continue
            }
            // export NAME=value
            val export = Regex("^export\\s+([${VAR_NAME}][${VAR_NAME}0-9]*)=(.*)$").find(s)
            if (export != null) {
                val name = export.groupValues[1]
                val value = export.groupValues[2].trim().trim('"', '\'')
                variablePaths[name] = value
                keyPaths += value
                understood++
                continue
            }
            // cd PATH
            val cd = Regex("""^cd\s+(.+)$""").find(s)
            if (cd != null) {
                keyPaths += cd.groupValues[1].trim()
                understood++
                continue
            }

            var segKnown = false
            var isReadOnly = false
            var isBuild = false
            var isTest = false

            // Delete operations — capture targets for risk anchoring.
            if (DELETE_CMD.containsMatchIn(s)) {
                classes += CommandClass.FILE_DELETE
                deleteTargets += extractDeleteTargets(s, variablePaths)
                segKnown = true
            }
            if (READ_CMD.containsMatchIn(s) && !hasWriteRedirection(s)) {
                classes += CommandClass.READ_ONLY
                isReadOnly = true
                segKnown = true
            }
            if (NETWORK_CMD.containsMatchIn(s)) {
                classes += CommandClass.NETWORK
                networkAccess = true
                segKnown = true
            }
            if (GIT_CMD.containsMatchIn(s)) {
                classes += CommandClass.GIT
                if (Regex("""git\s+(clone|fetch|pull|push|submodule)""").containsMatchIn(s)) networkAccess = true
                segKnown = true
            }
            if (PACKAGE_CMD.containsMatchIn(s)) {
                classes += CommandClass.PACKAGE_INSTALL
                installsPackages = true
                networkAccess = true
                segKnown = true
            }
            if (BUILD_CMD.containsMatchIn(s)) {
                classes += CommandClass.BUILD
                isBuild = true
                segKnown = true
            }
            if (TEST_MARKER.containsMatchIn(s) && !isReadOnly) {
                classes += CommandClass.TEST
                runsTests = true
                isTest = true
                segKnown = true
            }
            // Source-modifying writes: sed -i / edit tools / redirection into source files.
            if (Regex("""sed\s+(-i|--in-place)""").containsMatchIn(s) ||
                Regex("""\b(edit|write|patch)\b""").containsMatchIn(s) ||
                (s.contains(">") && SOURCE_EXT.containsMatchIn(s))
            ) {
                classes += CommandClass.FILE_WRITE
                modifiesSource = true
                segKnown = true
            }
            if (Regex("""^mkdir\b""").containsMatchIn(s)) {
                createsDirectories = true
                segKnown = true
            }
            // copy operations: record source/target paths (strip trailing redirections).
            val cpSegment = s.replace(Regex("""\s+2?>&?\d*\s*\S*\s*$"""), "").trim()
            val cp = Regex("""^cp\s+(-[a-zA-Z]+\s+)*(\S+)\s+(\S+)$""").find(cpSegment)
            if (cp != null) {
                keyPaths += cp.groupValues[2].trim()
                keyPaths += cp.groupValues[3].trim()
                segKnown = true
            }
            // Credential exclusions: mentions gradle.properties (or similar) in a
            // verify/not-found context — the command deliberately avoids it.
            if (CREDENTIAL_PATH.containsMatchIn(s) && !Regex("""cat\s+""").containsMatchIn(s)) {
                val m = CREDENTIAL_PATH.find(s)!!.value
                if (exclusions.none { it.contains(m, ignoreCase = true) }) exclusions += m
            }
            // Reading credentials is itself a risk signal (kept for RiskAssessor).
            if (Regex("""(cat|head|tail|cp|curl)\s+.*""").containsMatchIn(s) &&
                CREDENTIAL_PATH.containsMatchIn(s)
            ) {
                keyPaths += CREDENTIAL_PATH.find(s)!!.value
            }

            if (!segKnown) understood--
            else understood++
        }

        val confident = total > 0 && understood >= total
        if (classes.isEmpty()) classes += CommandClass.SHELL

        // Reproducibility test pattern: ISOLATED-like anchor to /tmp + build/test
        // + explicit credential exclusion.
        val hasIsolationAnchor = variablePaths.entries.any { (n, v) ->
            n.uppercase().contains("ISOLAT") || v.startsWith("/tmp/")
        }
        if (hasIsolationAnchor && (isBuildCmd(classes) || runsTests) && exclusions.isNotEmpty() && deleteTargets.isNotEmpty()) {
            classes += CommandClass.REPRODUCIBILITY_TEST
        }

        return CommandClassification(
            classes = classes,
            flags = CommandFlags(
                modifiesSource = modifiesSource,
                deletesFiles = deleteTargets.isNotEmpty(),
                networkAccess = networkAccess,
                runsTests = runsTests,
                installsPackages = installsPackages,
                createsDirectories = createsDirectories,
            ),
            variablePaths = variablePaths,
            keyPaths = keyPaths.distinct(),
            deleteTargets = deleteTargets.distinct(),
            exclusions = exclusions.distinct(),
            confident = confident,
        )
    }

    private fun isBuildCmd(classes: Set<CommandClass>): Boolean =
        CommandClass.BUILD in classes || CommandClass.TEST in classes

    /** True when the segment writes to a real file (excludes `2>&1` style fd redirects). */
    private fun hasWriteRedirection(segment: String): Boolean =
        Regex("""\s+[12]?>>?\s*[^&\s]""").containsMatchIn(segment)

    /** Quote-aware split on && / ; / || / | — pipe only when outside quotes. */
    internal fun splitSegments(command: String): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var i = 0
        var quote: Char? = null
        var prevOperator = false
        while (i < command.length) {
            val c = command[i]
            if (quote != null) {
                buf.append(c)
                if (c == quote) quote = null
                i++
                continue
            }
            if (c == '"' || c == '\'') {
                quote = c
                buf.append(c)
                i++
                continue
            }
            val two = if (i + 1 < command.length) command.substring(i, i + 2) else ""
            if (two == "&&" || two == "||" || two == ";;") {
                out += buf.toString()
                buf.clear()
                prevOperator = true
                i += 2
                continue
            }
            if (c == ';') {
                out += buf.toString()
                buf.clear()
                prevOperator = true
                i++
                continue
            }
            if (c == '|' && two != "|=") {
                out += buf.toString()
                buf.clear()
                prevOperator = true
                i++
                continue
            }
            if (!(prevOperator && c == ' ')) buf.append(c)
            if (c != ' ') prevOperator = false
            i++
        }
        out += buf.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Extract rm targets, resolving $VAR against known variable paths. */
    private fun extractDeleteTargets(
        segment: String,
        variablePaths: Map<String, String>,
    ): List<String> {
        val targets = mutableListOf<String>()
        val tokens = segment.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        var collecting = false
        for (t in tokens) {
            if (t == "rm" || t == "rmdir" || t == "truncate") {
                collecting = true
                continue
            }
            if (collecting) {
                if (t.startsWith("-")) continue
                val unquoted = t.trim('"', '\'')
                val resolved = if (unquoted.startsWith("$")) {
                    variablePaths[unquoted.removePrefix("$").trim('{', '}').split(":").first()] ?: unquoted
                } else unquoted
                targets += resolved.trim('"', '\'')
                break
            }
        }
        return targets
    }
}
