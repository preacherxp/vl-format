package dev.vlformat.cli

import dev.vlformat.config.FormatConfig
import dev.vlformat.engine.Formatter
import dev.vlformat.engine.SafetyCheck
import dev.vlformat.lexer.Language
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.stream.Collectors
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.system.exitProcess

/** Resolved from the jar manifest (set by the release build); "dev" locally. */
private val VERSION: String =
    object {}.javaClass.`package`?.implementationVersion ?: "dev"

private val SKIPPED_DIRS = setOf(".git", ".gradle", ".idea", "build", "out", "target", "node_modules")

private fun usage(): String = """
    vl-format $VERSION — fast, extensible Kotlin & Java formatter

    Usage: vl-format [options] <files or directories...>
           vl-format init [dir]     Interactively create a .vlformat config

    Options:
      --check         Don't write; exit 2 if any file needs formatting
      --dry-run, -n   Don't write; list files that would change
      --stdout        Format a single file to stdout
      --config <p>    Use an explicit .vlformat properties file
      --threads <n>   Worker threads (default: CPU cores)
      --no-verify     Skip the safety check that runs before each write
      --stats         Print timing and throughput
      --version       Print version
      --help, -h      This help
""".trimIndent()

/** Thrown for user errors; carries the message for stderr. */
class CliError(message: String) : Exception(message)

class Options(args: Array<String>) {
    var check = false
    var dryRun = false
    var stdout = false
    var stats = false
    var verify = true
    var helpOrVersion: String? = null
    var configPath: Path? = null
    var threads = Runtime.getRuntime().availableProcessors()
    val paths = ArrayList<Path>()

    init {
        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "--check" -> check = true
                "--dry-run", "-n" -> dryRun = true
                "--stdout" -> stdout = true
                "--stats" -> stats = true
                "--no-verify" -> verify = false
                "--config" -> configPath = Path.of(args.getOrNull(++i) ?: throw CliError("--config needs a path"))
                "--threads" -> threads = args.getOrNull(++i)?.toIntOrNull() ?: throw CliError("--threads needs a number")
                "--version" -> helpOrVersion = "vl-format $VERSION"
                "--help", "-h" -> helpOrVersion = usage()
                else -> {
                    if (a.startsWith("-")) throw CliError("unknown option: $a")
                    paths.add(Path.of(a))
                }
            }
            i++
        }
        if (helpOrVersion == null) {
            if (paths.isEmpty()) throw CliError("no input files.\n\n" + usage())
            if (stdout && (check || dryRun)) throw CliError("--stdout cannot be combined with --check/--dry-run")
        }
    }
}

private fun collectFiles(roots: List<Path>): List<Path> {
    val files = ArrayList<Path>()
    for (root in roots) {
        when {
            !Files.exists(root) -> throw CliError("no such file: $root")
            root.isDirectory() -> Files.walk(root).use { stream ->
                files.addAll(
                    stream
                        .filter { p -> Files.isRegularFile(p) && Language.fromFileName(p.name) != null }
                        // Only directories below the given root count: a project
                        // that itself lives under e.g. ~/build/ is still walked.
                        .filter { p -> root.relativize(p).none { part -> part.name in SKIPPED_DIRS } }
                        .collect(Collectors.toList()),
                )
            }
            else -> {
                if (Language.fromFileName(root.name) == null) throw CliError("unsupported file type: $root")
                files.add(root)
            }
        }
    }
    return files
}

private sealed interface FileResult {
    data class Ok(val path: Path, val changed: Boolean, val warnings: List<String> = emptyList()) : FileResult
    data class Error(val path: Path, val message: String) : FileResult
}

private fun longLineWarnings(path: Path, content: String, max: Int): List<String> {
    if (max <= 0) return emptyList()
    val warnings = ArrayList<String>(0)
    var lineNo = 1
    for (line in content.lineSequence()) {
        if (line.length > max) warnings.add("LONG LINE $path:$lineNo (${line.length} > $max)")
        lineNo++
    }
    return warnings
}

/** Full CLI logic; returns the process exit code. Never calls exitProcess. */
fun runCli(
    args: Array<String>,
    out: PrintStream = System.out,
    err: PrintStream = System.err,
    input: java.io.BufferedReader = System.`in`.bufferedReader(),
): Int {
    if (args.firstOrNull() == "init") {
        val dir = Path.of(args.getOrNull(1) ?: ".")
        if (!Files.isDirectory(dir)) {
            err.println("vl-format: not a directory: $dir")
            return 1
        }
        return InitWizard(input, out).run(dir)
    }

    val opts = try {
        Options(args)
    } catch (e: CliError) {
        err.println("vl-format: ${e.message}")
        return 1
    }
    opts.helpOrVersion?.let { out.println(it); return 0 }

    val files = try {
        collectFiles(opts.paths)
    } catch (e: CliError) {
        err.println("vl-format: ${e.message}")
        return 1
    }
    if (files.isEmpty()) {
        err.println("vl-format: no .java/.kt/.kts files found under given paths")
        return 1
    }
    if (opts.stdout && files.size != 1) {
        err.println("vl-format: --stdout requires exactly one input file")
        return 1
    }

    // One Formatter per distinct config; formatters are thread-safe.
    val explicitConfig = opts.configPath?.let { FormatConfig.load(it) }
    val configCache = ConcurrentHashMap<Path, FormatConfig>()
    val formatterCache = ConcurrentHashMap<FormatConfig, Formatter>()
    fun configFor(file: Path): FormatConfig = explicitConfig
        ?: configCache.computeIfAbsent(file.toAbsolutePath().parent) { FormatConfig.discover(it) }
    fun formatterFor(config: FormatConfig): Formatter =
        formatterCache.computeIfAbsent(config) { Formatter(it) }

    val started = System.nanoTime()
    val pool = Executors.newFixedThreadPool(opts.threads.coerceIn(1, 128))
    val results = try {
        files.map { file ->
            pool.submit<FileResult> {
                try {
                    val language = Language.fromFileName(file.name)!!
                    val original = Files.readString(file)
                    val config = configFor(file)
                    val formatted = formatterFor(config).format(original, language)
                    val changed = formatted != original
                    if (changed && opts.verify) {
                        SafetyCheck.verify(original, formatted, language)?.let { problem ->
                            return@submit FileResult.Error(file, "REFUSED (safety check): $problem")
                        }
                    }
                    if (changed) {
                        when {
                            opts.stdout -> out.print(formatted)
                            opts.check || opts.dryRun -> {} // report only
                            else -> Files.writeString(file, formatted)
                        }
                    } else if (opts.stdout) {
                        out.print(formatted)
                    }
                    FileResult.Ok(file, changed, longLineWarnings(file, formatted, config.maxLineLength))
                } catch (e: java.nio.charset.CharacterCodingException) {
                    FileResult.Error(file, "not valid UTF-8; skipped")
                } catch (e: Exception) {
                    FileResult.Error(file, e.message ?: e.javaClass.simpleName)
                }
            }
        }.map { it.get() }
    } finally {
        pool.shutdown()
    }

    val errors = results.filterIsInstance<FileResult.Error>()
    val changed = results.filterIsInstance<FileResult.Ok>().filter { it.changed }

    // Errors (including safety refusals) always go to stderr, also with --stdout.
    for (e in errors) err.println("ERROR ${e.path}: ${e.message}")
    if (!opts.stdout) {
        // Long-line reports are warnings: printed, never fatal.
        results.filterIsInstance<FileResult.Ok>().flatMap { it.warnings }.forEach { err.println(it) }
        when {
            opts.check -> changed.forEach { out.println("NEEDS FORMAT ${it.path}") }
            opts.dryRun -> changed.forEach { out.println("WOULD FORMAT ${it.path}") }
            else -> changed.forEach { out.println("FORMATTED ${it.path}") }
        }
    }

    if (opts.stats) {
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        val totalBytes = files.sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }
        err.printf(
            "%d files (%d changed, %d errors) in %.1f ms — %.1f files/s, %.2f MB/s%n",
            files.size, changed.size, errors.size, elapsedMs,
            files.size / (elapsedMs / 1000.0),
            totalBytes / 1024.0 / 1024.0 / (elapsedMs / 1000.0),
        )
    }

    return when {
        errors.isNotEmpty() -> 1
        opts.check && changed.isNotEmpty() -> 2
        else -> 0
    }
}

fun main(args: Array<String>) {
    exitProcess(runCli(args))
}
