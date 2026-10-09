package server.agents.capabilities.partyquest.lmpq;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One LMPQ main source as a test reads it: comments blanked and string contents blanked (structure only), with its
 * methods' bodies located, so a pattern can be found by the method it occurs in; a second copy keeps the string
 * literals for reading arguments. Copied in spirit from the LPQ rescue site scan.
 */
final class LmpqSource {
    static final Path PACKAGE = Path.of("src/main/java/server/agents/capabilities/partyquest/lmpq");
    /** A member header at class level: four spaces, then space-separated words (modifiers, type), then name(. */
    private static final Pattern METHOD = Pattern.compile(
            "(?m)^    (?![ \\t])(?:[\\w<>\\[\\],.?]+ )+?(\\w+)\\s*\\(");

    final String name;
    /** Comments and string contents blanked, offsets kept. */
    final String code;
    /** Comments blanked, string literals intact, offsets kept. */
    final String text;
    private final List<Method> methods = new ArrayList<>();

    private record Method(String name, int bodyStart, int bodyEnd) {
    }

    private LmpqSource(String name, String source) {
        this.name = name;
        String lf = source.replace("\r\n", "\n");
        this.code = blank(lf, true);
        this.text = blank(lf, false);
        Matcher header = METHOD.matcher(code);
        while (header.find()) {
            int open = code.indexOf('{', header.end());
            int semicolon = code.indexOf(';', header.end());
            if (open < 0 || (semicolon >= 0 && semicolon < open)) continue;
            int depth = 0;
            int end = open;
            for (; end < code.length(); end++) {
                if (code.charAt(end) == '{') depth++;
                else if (code.charAt(end) == '}' && --depth == 0) break;
            }
            methods.add(new Method(header.group(1), open, end));
        }
    }

    /** The named main source of the lmpq package, e.g. "AgentLmpqCoordinator". */
    static LmpqSource of(String className) throws IOException {
        return new LmpqSource(className, Files.readString(PACKAGE.resolve(className + ".java")));
    }

    /** Every main source of the lmpq package. */
    static List<LmpqSource> all() throws IOException {
        List<LmpqSource> sources = new ArrayList<>();
        try (var files = Files.list(PACKAGE)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                String fileName = file.getFileName().toString();
                sources.add(new LmpqSource(fileName.substring(0, fileName.length() - 5), Files.readString(file)));
            }
        }
        return sources;
    }

    /** The innermost method whose body holds {@code offset}, or null (a field or a declaration). */
    String enclosing(int offset) {
        Method best = null;
        for (Method method : methods) {
            if (offset > method.bodyStart() && offset < method.bodyEnd()
                    && (best == null || method.bodyStart() > best.bodyStart())) {
                best = method;
            }
        }
        return best == null ? null : best.name();
    }

    /** How often {@code regex} matches inside a method body, by enclosing method; declarations are skipped. */
    Map<String, Integer> callers(String regex) {
        Map<String, Integer> callers = new TreeMap<>();
        Matcher matcher = Pattern.compile(regex).matcher(code);
        while (matcher.find()) {
            String owner = enclosing(matcher.start());
            if (owner != null) callers.merge(owner, 1, Integer::sum);
        }
        return callers;
    }

    /**
     * Each match of {@code regex} against the literal-keeping copy inside a method body, as "method group1" (group 1
     * of the pattern, e.g. a site label), in source order.
     */
    List<String> labelledCalls(String regex) {
        List<String> calls = new ArrayList<>();
        Matcher matcher = Pattern.compile(regex).matcher(text);
        while (matcher.find()) {
            String owner = enclosing(matcher.start());
            if (owner != null) calls.add(owner + " " + matcher.group(1));
        }
        return calls;
    }

    /** How often {@code regex} matches anywhere in the source, outside comments and string contents. */
    int count(String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(code);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    /** The body of the named method (the first of that name). */
    String body(String method) {
        for (Method candidate : methods) {
            if (candidate.name().equals(method)) return code.substring(candidate.bodyStart(), candidate.bodyEnd());
        }
        throw new AssertionError(name + " has no method " + method);
    }

    private static String blank(String source, boolean literals) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2) + 2;
                for (; i < end; i++) out.append(source.charAt(i) == '\n' ? '\n' : ' ');
            } else if (c == '"' || c == '\'') {
                out.append(c);
                i++;
                while (source.charAt(i) != c) {
                    if (source.charAt(i) == '\\') {
                        out.append(literals ? "__" : source.substring(i, i + 2));
                        i += 2;
                        continue;
                    }
                    out.append(literals && source.charAt(i) != '\n' ? '_' : source.charAt(i));
                    i++;
                }
                out.append(c);
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
