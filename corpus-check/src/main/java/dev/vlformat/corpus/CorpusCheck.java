package dev.vlformat.corpus;

import com.sun.source.util.JavacTask;
import dev.vlformat.config.FormatConfig;
import dev.vlformat.engine.Formatter;
import dev.vlformat.engine.SafetyCheck;
import dev.vlformat.lexer.Language;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.PsiComment;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory;
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace;
import org.jetbrains.kotlin.config.CommonConfigurationKeys;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.idea.KotlinLanguage;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Formats every .java/.kt/.kts file under the given directories IN MEMORY
 * (nothing is written) with the default config, then reports:
 *
 * <ul>
 *   <li>crashes,</li>
 *   <li>files the safety check would refuse to write,</li>
 *   <li>files whose second format differs from the first (not idempotent),</li>
 *   <li>files the real parser reads differently after formatting: javac's
 *       parser for Java, the Kotlin compiler's PSI for Kotlin. Comments and
 *       whitespace are ignored; everything else must match.</li>
 * </ul>
 *
 * The parsers only parse; no code from the scanned trees is compiled or run.
 * Files that don't parse before formatting are counted and skipped by the oracle.
 *
 * Usage: ./gradlew :corpus-check:run --args="/abs/path/to/repo [more dirs...]"
 */
public final class CorpusCheck {
    private static Project project;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: corpus-check <dir> [dir...]");
            System.exit(1);
        }
        List<Path> files = new ArrayList<>();
        for (String arg : args) {
            try (Stream<Path> s = Files.walk(Path.of(arg))) {
                s.filter(Files::isRegularFile)
                    .filter(p -> Language.Companion.fromFileName(p.getFileName().toString()) != null)
                    .filter(p -> !p.toString().contains("/build/"))
                    .sorted()
                    .forEach(files::add);
            }
        }
        CompilerConfiguration cc = new CompilerConfiguration();
        cc.put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.Companion.getNONE());
        project = KotlinCoreEnvironment.createForProduction(
            Disposer.newDisposable(), cc, EnvironmentConfigFiles.JVM_CONFIG_FILES).getProject();

        Formatter formatter = new Formatter(new FormatConfig(), List.of(), List.of(), false);
        int total = 0, unreadable = 0, changed = 0, crash = 0, refused = 0, nonIdempotent = 0,
            meaningChanged = 0, unparseable = 0;
        List<String> details = new ArrayList<>();
        long started = System.nanoTime();
        for (Path p : files) {
            total++;
            String name = p.getFileName().toString();
            Language lang = Language.Companion.fromFileName(name);
            String src;
            try {
                src = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(p))).toString();
            } catch (Exception e) {
                unreadable++;
                continue;
            }
            String out;
            try {
                out = formatter.format(src, lang);
            } catch (Throwable t) {
                crash++;
                details.add("CRASH " + p + " :: " + t);
                continue;
            }
            if (out.equals(src)) continue;
            changed++;
            String problem = SafetyCheck.INSTANCE.verify(src, out, lang);
            if (problem != null) {
                refused++;
                details.add("REFUSED " + p + " :: " + problem);
                continue;
            }
            String again = formatter.format(out, lang);
            if (!again.equals(out)) {
                nonIdempotent++;
                details.add("NOT IDEMPOTENT " + p + " :: " + firstDiff(out, again));
            }
            String before = signature(src, lang, name);
            if (before == null) {
                unparseable++;
                continue;
            }
            String after = signature(out, lang, name);
            if (!before.equals(after)) {
                meaningChanged++;
                details.add("PARSE CHANGED " + p + " :: " + (after == null ? "output no longer parses" : firstDiff(before, after)));
            }
        }
        double secs = (System.nanoTime() - started) / 1e9;
        System.out.printf(
            "%d files, %d changed, %d crashed, %d refused by the safety check, %d not idempotent, " +
                "%d parsed differently after formatting (%d unreadable as UTF-8, %d unparseable before formatting) in %.1f s%n",
            total, changed, crash, refused, nonIdempotent, meaningChanged, unreadable, unparseable, secs);
        details.forEach(System.out::println);
        System.exit(crash + refused + nonIdempotent + meaningChanged == 0 ? 0 : 1);
    }

    private static String firstDiff(String a, String b) {
        String[] x = a.split("\n", -1), y = b.split("\n", -1);
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            if (!x[i].equals(y[i])) return "line " + (i + 1) + ": [" + clip(x[i]) + "] vs [" + clip(y[i]) + "]";
        }
        return "line count " + x.length + " vs " + y.length;
    }

    private static String clip(String s) {
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    /** Parse-tree signature without comments and whitespace, or null on a parse error. */
    private static String signature(String text, Language lang, String name) {
        return lang == Language.JAVA ? javaSignature(text) : kotlinSignature(text, name);
    }

    private static String javaSignature(String text) {
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        JavaFileObject fo = new SimpleJavaFileObject(URI.create("string:///X.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        };
        String release = Integer.toString(Runtime.version().feature());
        try {
            JavacTask task = (JavacTask) jc.getTask(null, null, diags,
                List.of("-proc:none", "--enable-preview", "--release", release), null, List.of(fo));
            StringBuilder sb = new StringBuilder();
            for (var unit : task.parse()) sb.append(unit);
            for (var d : diags.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) return null;
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String kotlinSignature(String text, String name) {
        PsiFile file = PsiFileFactory.getInstance(project)
            .createFileFromText(name.endsWith(".kts") ? "x.kts" : "x.kt", KotlinLanguage.INSTANCE, text);
        StringBuilder sb = new StringBuilder();
        boolean[] error = {false};
        walk(file, sb, error);
        return error[0] ? null : sb.toString();
    }

    private static void walk(PsiElement e, StringBuilder sb, boolean[] error) {
        if (e instanceof PsiErrorElement) {
            error[0] = true;
            return;
        }
        if (e instanceof PsiWhiteSpace || e instanceof PsiComment) return;
        PsiElement child = e.getFirstChild();
        if (child == null) {
            sb.append(e.getNode().getElementType()).append('\'').append(e.getText()).append("'\n");
            return;
        }
        sb.append('(').append(e.getNode().getElementType()).append('\n');
        for (; child != null; child = child.getNextSibling()) walk(child, sb, error);
        sb.append(")\n");
    }
}
