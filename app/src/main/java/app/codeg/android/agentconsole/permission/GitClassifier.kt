package app.codeg.android.agentconsole.permission

/**
 * Agent Console overlay — deterministic Git operation classification.
 *
 * Risk must be derived from the concrete git OPERATION + ARGUMENTS, never from
 * the bare "git" command name. Anything we cannot reliably identify (unknown
 * subcommand, aliases, variable expansion, exotic flag shapes) is UNKNOWN —
 * never silently downgraded to LOW.
 *
 * Modification for Agent Console overlay by ldohgfsdu (2026).
 */
enum class GitOpClass {
    READ_ONLY,
    LOCAL_MUTATE,
    LOCAL_DESTRUCTIVE,
    REMOTE_WRITE,
    REMOTE_DESTRUCTIVE,
    UNKNOWN,
}

data class GitAssessment(
    val operation: String,
    val opClass: GitOpClass,
    val confident: Boolean,
)

object GitClassifier {

    private val READ_ONLY_OPS = setOf(
        "status", "diff", "log", "show", "shortlog", "describe", "blame", "grep",
        "rev-parse", "rev-list", "ls-files", "ls-tree", "ls-remote", "diff-tree",
        "diff-index", "diff-files", "name-rev", "reflog", "fsck", "count-objects",
    )

    private val MUTATE_OPS = setOf(
        "add", "mv", "rm", "commit", "merge", "rebase", "cherry-pick", "apply",
        "am", "switch", "checkout", "config", "init", "clone",
    )

    private val DESTRUCTIVE_LOCAL_FLAGS = setOf("--hard")
    private val DESTRUCTIVE_REMOTE_FLAGS = setOf("--force", "-f", "--force-with-lease", "--mirror", "--delete", "-d")

    /**
     * Assess a single git command segment (e.g. "git reset --hard HEAD~1").
     * Segments are produced by the quote-aware splitter, so no && / ; / | here.
     */
    fun assess(segment: String): GitAssessment {
        val tokens = segment.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens[0] != "git") {
            return GitAssessment("", GitOpClass.UNKNOWN, confident = false)
        }
        if (tokens.size < 2) {
            // Bare "git" — shows help; harmless but not confidently an operation.
            return GitAssessment("", GitOpClass.UNKNOWN, confident = false)
        }
        val operation = tokens[1].trim('"', '\'')

        // Variable expansion anywhere in the segment: cannot reliably reason
        // about arguments — UNKNOWN, never downgraded.
        if (tokens.any { it.contains("$") }) {
            return GitAssessment(operation, GitOpClass.UNKNOWN, confident = false)
        }
        // Short flag form of a subcommand (e.g. git -C dir status): rare in agent
        // workflows and not confidently parseable here — UNKNOWN.
        if (operation.startsWith("-")) {
            return GitAssessment(operation, GitOpClass.UNKNOWN, confident = false)
        }

        val args = tokens.drop(2).map { it.trim('"', '\'') }

        return when {
            operation in READ_ONLY_OPS -> GitAssessment(operation, GitOpClass.READ_ONLY, confident = true)

            operation == "branch" -> assessBranch(args, operation)
            operation == "tag" -> assessTag(args, operation)
            operation == "remote" -> assessRemote(args, operation)
            operation == "clean" -> assessClean(args, operation)
            operation == "reset" -> assessReset(args, operation)
            operation == "restore" -> assessRestore(args, operation)
            operation == "stash" -> assessStash(args, operation)
            operation == "push" -> assessPush(args, operation)
            operation == "pull" -> GitAssessment(operation, GitOpClass.REMOTE_WRITE, confident = true)
            operation == "fetch" -> GitAssessment(operation, GitOpClass.REMOTE_WRITE, confident = true)
            operation == "checkout" -> assessCheckout(args, operation)
            operation == "switch" -> GitAssessment(operation, GitOpClass.LOCAL_MUTATE, confident = true)
            operation in MUTATE_OPS -> GitAssessment(operation, GitOpClass.LOCAL_MUTATE, confident = true)
            else -> GitAssessment(operation, GitOpClass.UNKNOWN, confident = false)
        }
    }

    private fun assessBranch(args: List<String>, op: String): GitAssessment {
        // Listing forms: no args, --list/-l/-a/-r/-v/--all/--remotes/--verbose...
        val listingOnly = args.isEmpty() || args.all {
            it == "--list" || it == "-l" || it == "-a" || it == "--all" ||
                it == "-r" || it == "--remotes" || it == "-v" || it == "-vv" ||
                it == "--verbose" || it == "--merged" || it == "--no-merged" ||
                it == "--show-current" || it == "--column"
        }
        if (listingOnly) return GitAssessment(op, GitOpClass.READ_ONLY, confident = true)
        // Delete branch: -d / -D / --delete.
        if (args.any { it == "-d" || it == "-D" || it == "--delete" }) {
            return GitAssessment(op, GitOpClass.LOCAL_DESTRUCTIVE, confident = true)
        }
        // Unknown shape (e.g. alias-like flags) — do not guess.
        if (args.any { it.startsWith("-") && it !in setOf("--track", "--no-track", "--copy", "--move", "-m", "-M", "-c", "-C") }) {
            return GitAssessment(op, GitOpClass.UNKNOWN, confident = false)
        }
        return GitAssessment(op, GitOpClass.LOCAL_MUTATE, confident = true)
    }

    private fun assessTag(args: List<String>, op: String): GitAssessment {
        if (args.isEmpty() || args.all { it == "-l" || it == "--list" || it == "-n" }) {
            return GitAssessment(op, GitOpClass.READ_ONLY, confident = true)
        }
        if (args.any { it == "-d" || it == "--delete" }) {
            return GitAssessment(op, GitOpClass.LOCAL_DESTRUCTIVE, confident = true)
        }
        if (args.any { it.startsWith("-") }) {
            return GitAssessment(op, GitOpClass.UNKNOWN, confident = false)
        }
        return GitAssessment(op, GitOpClass.LOCAL_MUTATE, confident = true)
    }

    private fun assessRemote(args: List<String>, op: String): GitAssessment {
        if (args.isEmpty() || args.all { it == "-v" || it == "--verbose" }) {
            return GitAssessment(op, GitOpClass.READ_ONLY, confident = true)
        }
        if (args.any { it in setOf("add", "remove", "rename", "set-url", "set-branches", "set-head", "update", "prune") }) {
            // prune removes stale remote-tracking refs — still local bookkeeping, not workspace destruction.
            return GitAssessment(op, GitOpClass.LOCAL_MUTATE, confident = true)
        }
        if (args.any { it.startsWith("-") }) {
            return GitAssessment(op, GitOpClass.UNKNOWN, confident = false)
        }
        return GitAssessment(op, GitOpClass.LOCAL_MUTATE, confident = true)
    }

    private fun assessClean(args: List<String>, op: String): GitAssessment {
        // Without -f, git clean only reports (dry run) — non-destructive.
        val forceFlag = args.any { it == "-f" || it == "-d" || it == "-x" || it == "-X" || it == "-fd" || it == "-df" || it == "-fdx" || it == "-ffdx" || it == "--force" }
        if (args.isEmpty() || args.all { it == "-n" || it == "--dry-run" }) {
            return GitAssessment(op, GitOpClass.READ_ONLY, confident = true)
        }
        if (forceFlag) return GitAssessment(op, GitOpClass.LOCAL_DESTRUCTIVE, confident = true)
        if (args.any { it.startsWith("-") && it != "-n" && it != "--dry-run" && it != "-i" && it != "--interactive" }) {
            return GitAssessment(op, GitOpClass.UNKNOWN, confident = false)
        }
        // Interactive clean deletes files too — destructive by nature of the operation.
        return GitAssessment(op, GitOpClass.LOCAL_DESTRUCTIVE, confident = true)
    }

    private fun assessReset(args: List<String>, op: String): GitAssessment {
        if (args.any { it == "--hard" }) return GitAssessment(op, GitOpClass.LOCAL_DESTRUCTIVE, confident = true)
        if (args.any { it.startsWith("-") && it !in setOf("--soft", "--mixed", "--merge", "--keep", "-q", "--quiet", "-p", "--patch") }) {
            return GitAssessment(op, GitOpClass.UNKNOWN, confident = false)
        }
        return GitAssessment(op, GitOpClass.LOCAL_MUTATE, confident = true)
    }

    private fun assessRestore(args: List<String>, op: String): GitAssessment {
        if (args.any { it == "--staged" || it == "-S" }) {
            return GitAssessment(op, GitOpClass.LOCAL_MUTATE, confident = true)
        }
        // restore without --staged overwrites working-tree files — destructive to uncommitted work.
        return GitAssessment(op, GitOpClass.LOCAL_DESTRUCTIVE, confident = true)
    }

    private fun assessStash(args: List<String>, op: String): GitAssessment {
        if (args.any { it == "drop" || it == "clear" }) {
            return GitAssessment(op, GitOpClass.LOCAL_DESTRUCTIVE, confident = true)
        }
        return GitAssessment(op, GitOpClass.LOCAL_MUTATE, confident = true)
    }

    private fun assessPush(args: List<String>, op: String): GitAssessment {
        // Force forms.
        if (args.any { it == "--force" || it == "-f" || it == "--force-with-lease" || it == "--mirror" || it == "--delete" || it == "-d" }) {
            return GitAssessment(op, GitOpClass.REMOTE_DESTRUCTIVE, confident = true)
        }
        // Deleting remote branch via push origin :branch / --delete.
        if (args.any { it.startsWith(":") }) {
            return GitAssessment(op, GitOpClass.REMOTE_DESTRUCTIVE, confident = true)
        }
        // +refspec force push.
        if (args.any { it.startsWith("+") }) {
            return GitAssessment(op, GitOpClass.REMOTE_DESTRUCTIVE, confident = true)
        }
        if (args.any { it.contains("$") }) {
            return GitAssessment(op, GitOpClass.UNKNOWN, confident = false)
        }
        return GitAssessment(op, GitOpClass.REMOTE_WRITE, confident = true)
    }

    private fun assessCheckout(args: List<String>, op: String): GitAssessment {
        // checkout -- <path> or checkout <path> overwrites working-tree files.
        val dashed = args.any { it == "--" }
        if (dashed) return GitAssessment(op, GitOpClass.LOCAL_DESTRUCTIVE, confident = true)
        // checkout -b / -B branch creation-switch.
        if (args.any { it == "-b" }) return GitAssessment(op, GitOpClass.LOCAL_MUTATE, confident = true)
        if (args.any { it == "-B" }) {
            // -B resets an existing branch — destructive to branch ref.
            return GitAssessment(op, GitOpClass.LOCAL_DESTRUCTIVE, confident = true)
        }
        if (args.any { it.startsWith("-") && it !in setOf("--track", "--no-track", "--detach", "--guess", "--no-guess") }) {
            return GitAssessment(op, GitOpClass.UNKNOWN, confident = false)
        }
        return GitAssessment(op, GitOpClass.LOCAL_MUTATE, confident = true)
    }
}
