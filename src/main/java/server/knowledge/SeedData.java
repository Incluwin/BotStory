package server.knowledge;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The drop, global-drop and shop rows a fresh database holds once Liquibase has run: offline content lint, the
 * PowerShell catalog exporters (through SeedDataMain) and tests. The live server reads the tables (KnowledgeOverlayLoader);
 * operator edits made through the database console are therefore only in the live rows.
 * Order is db/changelog-root.xml's: each &lt;include&gt; in document order, each &lt;includeAll&gt; folder's *.xml by file
 * name; in a changelog each &lt;changeSet&gt;'s direct sqlFile / sql / insert / update / delete children in order
 * (&lt;rollback&gt; never runs). Applied statement shapes: INSERT INTO t (cols) VALUES (..),(..); INSERT INTO t (cols)
 * SELECT * FROM (SELECT v AS c, ..) AS x WHERE NOT EXISTS (SELECT 1 FROM t WHERE ..); UPDATE t SET c = v[, ..] WHERE
 * a = x [AND ..]; DELETE FROM t WHERE a = x [AND ..], all with literal values. DDL and other tables are skipped; any
 * other data change on the four tables is a warning, not applied.
 */
public final class SeedData {
    public static final String DEFAULT_ROOT = "src/main/resources/db";
    /** The replayed tables, in the order {@link Replay#tables()} lists them. */
    public static final List<String> TABLES = List.of("drop_data", "drop_data_global", "shops", "shopitems");
    private static final String ROOT_CHANGELOG = "changelog-root.xml";
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final int SHOWN_CHARS = 100;

    /** Rows per table (column -> raw value, insertion order), the changesets that changed them, and what was not applied. */
    public record Replay(Map<String, List<Map<String, String>>> tables, List<String> applied, List<String> warnings) {
    }

    private SeedData() {
    }

    public static Replay replay(Path dbRoot) throws IOException {
        Replayer replayer = new Replayer(dbRoot.toAbsolutePath().getParent());
        replayer.changelog(dbRoot.toAbsolutePath().resolve(ROOT_CHANGELOG));
        return replayer.result();
    }

    /** drop_data, drop_data_global (chance > 0, as the live loader) and shopitems JOIN shops, as KnowledgeOverlayLoader reads them. */
    public static KnowledgeOverlay overlay(Replay replay) {
        KnowledgeOverlay.Builder builder = KnowledgeOverlay.builder();
        for (Map<String, String> row : replay.tables().get("drop_data")) {
            builder.drop(column(row, "dropperid"), column(row, "itemid"), column(row, "chance"),
                    column(row, "minimum_quantity"), column(row, "maximum_quantity"), column(row, "questid"));
        }
        for (Map<String, String> row : replay.tables().get("drop_data_global")) {
            if (column(row, "chance") > 0) {
                builder.globalDrop(column(row, "continent"), column(row, "itemid"), column(row, "chance"),
                        column(row, "minimum_quantity"), column(row, "maximum_quantity"), column(row, "questid"));
            }
        }
        Map<Integer, Integer> npcByShop = new HashMap<>();
        for (Map<String, String> row : replay.tables().get("shops")) {
            npcByShop.put(column(row, "shopid"), column(row, "npcid"));
        }
        for (Map<String, String> row : replay.tables().get("shopitems")) {
            Integer npc = npcByShop.get(column(row, "shopid"));
            if (npc != null) {
                builder.shopItem(column(row, "shopid"), npc, column(row, "itemid"), column(row, "price"), column(row, "pitch"));
            }
        }
        return builder.build();
    }

    public static KnowledgeOverlay load(Path dbRoot) throws IOException {
        return overlay(replay(dbRoot));
    }

    /** A row's column as an int; a column the statement left out is 0. */
    public static int column(Map<String, String> row, String name) {
        return Integer.parseInt(row.getOrDefault(name, "0"));
    }

    /** One SQL token: a word or number, a punctuation character, or a quoted string's unescaped text. */
    private record Token(String text, boolean quoted) {
        boolean is(String word) {
            return !quoted && text.equalsIgnoreCase(word);
        }
    }

    /** One statement: its text (comments stripped, whitespace collapsed) and tokens. */
    private record Statement(String text, List<Token> tokens) {
    }

    private static final class Replayer {
        private final Path resources;
        private final Map<String, List<Map<String, String>>> tables = new LinkedHashMap<>();
        private final Set<String> applied = new LinkedHashSet<>();
        private final List<String> warnings = new ArrayList<>();
        private final DocumentBuilder xml;

        Replayer(Path resources) throws IOException {
            this.resources = resources;
            for (String table : TABLES) {
                tables.put(table, new ArrayList<>());
            }
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                xml = factory.newDocumentBuilder();
            } catch (ParserConfigurationException failure) {
                throw new IOException(failure);
            }
        }

        Replay result() {
            Map<String, List<Map<String, String>>> out = new LinkedHashMap<>();
            tables.forEach((table, rows) -> out.put(table, Collections.unmodifiableList(rows)));
            return new Replay(Collections.unmodifiableMap(out), List.copyOf(applied), List.copyOf(warnings));
        }

        void changelog(Path file) throws IOException {
            Document document;
            try {
                document = xml.parse(file.toFile());
            } catch (SAXException failure) {
                throw new IOException(file + ": " + failure.getMessage(), failure);
            }
            for (Element child : children(document.getDocumentElement())) {
                switch (name(child)) {
                    case "include" -> changelog(resolve(file, child.getAttribute("file"), child));
                    case "includeAll" -> {
                        Path dir = resolve(file, child.getAttribute("path"), child);
                        if (Files.isDirectory(dir)) {
                            List<Path> changelogs;
                            try (Stream<Path> list = Files.list(dir)) {
                                changelogs = list.filter(path -> path.getFileName().toString().endsWith(".xml"))
                                        .sorted((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString())).toList();
                            }
                            for (Path included : changelogs) {
                                changelog(included);
                            }
                        }
                    }
                    case "changeSet" -> changeSet(file, child);
                    default -> {
                    }
                }
            }
        }

        private void changeSet(Path changelog, Element set) throws IOException {
            String id = set.getAttribute("id");
            String file = label(changelog);
            boolean preconditions = false;
            boolean changed = false;
            for (Element child : children(set)) {
                switch (name(child)) {
                    case "preConditions" -> preconditions = true;
                    case "sqlFile" -> {
                        Path sql = resolve(changelog, child.getAttribute("path"), child);
                        String text = new String(Files.readAllBytes(sql), StandardCharsets.UTF_8);
                        for (Statement statement : statements(text)) {
                            changed |= apply(label(sql), id, statement);
                        }
                    }
                    case "sql" -> {
                        for (Statement statement : statements(child.getTextContent())) {
                            changed |= apply(file, id, statement);
                        }
                    }
                    case "insert", "update", "delete" -> changed |= change(file, id, child);
                    case "loadData", "loadUpdateData" -> {
                        if (watched(child.getAttribute("tableName"))) {
                            warn(file, id, "<" + name(child) + " tableName=\"" + child.getAttribute("tableName") + "\">");
                        }
                    }
                    default -> {
                    }
                }
            }
            if (changed) {
                applied.add(id);
                if (preconditions) {
                    warnings.add(file + ": changeset " + id + ": preconditions not evaluated");
                }
            }
        }

        /** An XML insert/update/delete; true when it changed a replayed table. */
        private boolean change(String file, String changeSet, Element change) {
            String table = change.getAttribute("tableName").toLowerCase(Locale.ROOT);
            if (!watched(table)) {
                return false;
            }
            Map<String, String> values = new LinkedHashMap<>();
            String where = null;
            boolean supported = true;
            for (Element child : children(change)) {
                if ("column".equals(name(child))) {
                    String value = child.hasAttribute("valueNumeric") ? child.getAttribute("valueNumeric")
                            : child.hasAttribute("value") ? child.getAttribute("value")
                            : child.hasAttribute("valueBoolean") ? ("true".equalsIgnoreCase(child.getAttribute("valueBoolean")) ? "1" : "0")
                            : null;
                    supported &= value != null;
                    values.put(child.getAttribute("name").toLowerCase(Locale.ROOT), value);
                } else if ("where".equals(name(child))) {
                    where = child.getTextContent();
                }
            }
            List<String[]> condition = where == null ? null : condition(where);
            String shown = "<" + name(change) + " tableName=\"" + table + "\">" + (where == null ? "" : " where " + where.trim());
            String kind = name(change);
            if (!supported || !"insert".equals(kind) && condition == null || "insert".equals(kind) && where != null) {
                warn(file, changeSet, shown);
                return false;
            }
            List<Map<String, String>> rows = tables.get(bare(table));
            switch (kind) {
                case "insert" -> rows.add(values);
                case "update" -> rows.stream().filter(row -> matches(row, condition)).forEach(row -> row.putAll(values));
                default -> rows.removeIf(row -> matches(row, condition));
            }
            return true;
        }

        /** An XML {@code <where>}: one {@link Cursor#condition()} and nothing after it, else null. */
        private static List<String[]> condition(String where) {
            List<Statement> parsed = statements(where);
            if (parsed.size() != 1) {
                return null;
            }
            Cursor cursor = new Cursor(parsed.get(0).tokens());
            List<String[]> condition = cursor.condition();
            return cursor.atEnd() ? condition : null;
        }

        /** One SQL statement; true when it changed a replayed table. Statements on other tables and DDL are skipped. */
        private boolean apply(String file, String changeSet, Statement statement) {
            Cursor cursor = new Cursor(statement.tokens());
            String verb = cursor.word().toUpperCase(Locale.ROOT);
            String table = switch (verb) {
                case "INSERT", "REPLACE" -> cursor.after("INTO");
                case "UPDATE" -> cursor.word();
                case "DELETE" -> cursor.after("FROM");
                case "TRUNCATE" -> cursor.peek("TABLE") ? cursor.after("TABLE") : cursor.word();
                default -> null;
            };
            if (table == null || !watched(table)) {
                return false;
            }
            table = bare(table);
            boolean done = switch (verb) {
                case "INSERT" -> statement.tokens().get(1).is("INTO") && insert(table, cursor);
                case "UPDATE" -> update(table, cursor);
                case "DELETE" -> delete(table, cursor);
                default -> false;
            };
            if (!done) {
                warn(file, changeSet, statement.text());
            }
            return done;
        }

        private boolean insert(String table, Cursor cursor) {
            List<String> columns = cursor.names();
            if (columns == null) {
                return false;
            }
            List<Map<String, String>> rows = new ArrayList<>();
            if (cursor.take("VALUES")) {
                do {
                    List<String> values = cursor.tuple();
                    if (values == null || values.size() != columns.size()) {
                        return false;
                    }
                    rows.add(row(columns, values));
                } while (cursor.take(","));
                if (!cursor.atEnd()) {
                    return false;
                }
            } else {
                // INSERT INTO t (cols) SELECT * FROM (SELECT v AS c, ..) AS x WHERE NOT EXISTS (SELECT 1 FROM t2 WHERE ..)
                if (!(cursor.take("SELECT") && cursor.take("*") && cursor.take("FROM") && cursor.take("(") && cursor.take("SELECT"))) {
                    return false;
                }
                List<String> values = new ArrayList<>();
                do {
                    String value = cursor.value();
                    if (value == null || !cursor.take("AS") || cursor.word().isEmpty()) {
                        return false;
                    }
                    values.add(value);
                } while (cursor.take(","));
                if (values.size() != columns.size() || !cursor.take(")")) {
                    return false;
                }
                cursor.take("AS");
                if (!cursor.peek("WHERE")) {
                    cursor.word(); // the derived table's alias
                }
                if (!(cursor.take("WHERE") && cursor.take("NOT") && cursor.take("EXISTS") && cursor.take("(")
                        && cursor.take("SELECT") && cursor.take("1") && cursor.take("FROM"))) {
                    return false;
                }
                String other = cursor.word();
                if (!watched(other) || !cursor.take("WHERE")) {
                    return false;
                }
                List<String[]> condition = cursor.condition();
                if (condition == null || !cursor.take(")") || !cursor.atEnd()) {
                    return false;
                }
                if (tables.get(bare(other)).stream().noneMatch(row -> matches(row, condition))) {
                    rows.add(row(columns, values));
                }
            }
            tables.get(table).addAll(rows);
            return true;
        }

        private boolean update(String table, Cursor cursor) {
            if (!cursor.take("SET")) {
                return false;
            }
            Map<String, String> values = new LinkedHashMap<>();
            do {
                String column = cursor.word().toLowerCase(Locale.ROOT);
                String value = cursor.take("=") ? cursor.value() : null;
                if (column.isEmpty() || value == null) {
                    return false;
                }
                values.put(column, value);
            } while (cursor.take(","));
            List<String[]> condition = cursor.take("WHERE") ? cursor.condition() : null;
            if (condition == null || !cursor.atEnd()) {
                return false;
            }
            tables.get(table).stream().filter(row -> matches(row, condition)).forEach(row -> row.putAll(values));
            return true;
        }

        private boolean delete(String table, Cursor cursor) {
            List<String[]> condition = cursor.take("WHERE") ? cursor.condition() : null;
            if (condition == null || !cursor.atEnd()) {
                return false;
            }
            tables.get(table).removeIf(row -> matches(row, condition));
            return true;
        }

        private static Map<String, String> row(List<String> columns, List<String> values) {
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                row.put(columns.get(i), values.get(i));
            }
            return row;
        }

        private static boolean matches(Map<String, String> row, List<String[]> condition) {
            for (String[] term : condition) {
                if (!row.getOrDefault(term[0], "0").equals(term[1])) {
                    return false;
                }
            }
            return true;
        }

        private void warn(String file, String changeSet, String shown) {
            String text = shown.length() > SHOWN_CHARS ? shown.substring(0, SHOWN_CHARS) : shown;
            warnings.add(file + ": changeset " + changeSet + ": not applied: " + text);
        }

        private Path resolve(Path changelog, String path, Element element) {
            return "true".equalsIgnoreCase(element.getAttribute("relativeToChangelogFile"))
                    ? changelog.getParent().resolve(path) : resources.resolve(path);
        }

        private String label(Path file) {
            Path relative = file.startsWith(resources) ? resources.relativize(file) : file;
            return relative.toString().replace('\\', '/');
        }
    }

    private static boolean watched(String table) {
        return TABLES.contains(bare(table));
    }

    /** A table or column name without its qualifier, lowercased: {@code db.Table} -> {@code table}. */
    private static String bare(String name) {
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private static String name(Element element) {
        return element.getLocalName() == null ? element.getTagName() : element.getLocalName();
    }

    private static List<Element> children(Element parent) {
        List<Element> out = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                out.add(element);
            }
        }
        return out;
    }

    /**
     * Splits SQL text into statements on {@code ;} outside quotes, dropping {@code --}, {@code #} and {@code /* *\/}
     * comments. Quotes are ', " (backslash or doubled escapes) and ` (identifiers).
     */
    private static List<Statement> statements(String sql) {
        List<Statement> out = new ArrayList<>();
        List<Token> tokens = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
            if (c == '-' && next == '-' || c == '#') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (Character.isWhitespace(c)) {
                i++;
            } else if (c == ';') {
                flush(out, tokens, text);
                i++;
            } else {
                int start = i;
                if (c == '\'' || c == '"' || c == '`') {
                    StringBuilder value = new StringBuilder();
                    i++;
                    while (i < n) {
                        char ch = sql.charAt(i);
                        if (ch == '\\' && c != '`' && i + 1 < n) {
                            value.append(sql.charAt(i + 1));
                            i += 2;
                        } else if (ch == c && i + 1 < n && sql.charAt(i + 1) == c) {
                            value.append(c);
                            i += 2;
                        } else if (ch == c) {
                            i++;
                            break;
                        } else {
                            value.append(ch);
                            i++;
                        }
                    }
                    tokens.add(new Token(value.toString(), c != '`'));
                } else if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '-' && Character.isDigit(next)) {
                    i++;
                    while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || "_$.".indexOf(sql.charAt(i)) >= 0)) {
                        i++;
                    }
                    tokens.add(new Token(sql.substring(start, i), false));
                } else {
                    i++;
                    tokens.add(new Token(String.valueOf(c), false));
                }
                if (!text.isEmpty() && start > 0 && Character.isWhitespace(sql.charAt(start - 1))) {
                    text.append(' ');
                }
                text.append(sql, start, i);
            }
        }
        flush(out, tokens, text);
        return out;
    }

    private static void flush(List<Statement> out, List<Token> tokens, StringBuilder text) {
        if (!tokens.isEmpty()) {
            out.add(new Statement(text.toString(), List.copyOf(tokens)));
        }
        tokens.clear();
        text.setLength(0);
    }

    /** Reads one statement's tokens left to right; every method returns null / false / "" when the shape does not fit. */
    private static final class Cursor {
        private final List<Token> tokens;
        private int at;

        Cursor(List<Token> tokens) {
            this.tokens = tokens;
        }

        boolean atEnd() {
            return at >= tokens.size();
        }

        boolean peek(String word) {
            return !atEnd() && tokens.get(at).is(word);
        }

        boolean take(String word) {
            if (peek(word)) {
                at++;
                return true;
            }
            return false;
        }

        /** The next unquoted token (a word, keyword or name), else "". */
        String word() {
            if (atEnd() || tokens.get(at).quoted()) {
                return "";
            }
            return tokens.get(at++).text();
        }

        /** Skips to just past {@code keyword} and returns the word after it, or null when it is missing. */
        String after(String keyword) {
            while (!atEnd() && !peek(keyword)) {
                at++;
            }
            return take(keyword) && !atEnd() ? word() : null;
        }

        /** A literal: a number or a quoted string (its text), else null. */
        String value() {
            if (atEnd()) {
                return null;
            }
            Token token = tokens.get(at);
            if (token.quoted() || NUMBER.matcher(token.text()).matches()) {
                at++;
                return token.text();
            }
            return null;
        }

        /** {@code (a, b, ..)} column names, lowercased. */
        List<String> names() {
            if (!take("(")) {
                return null;
            }
            List<String> out = new ArrayList<>();
            do {
                String name = word();
                if (name.isEmpty()) {
                    return null;
                }
                out.add(name.toLowerCase(Locale.ROOT));
            } while (take(","));
            return take(")") ? out : null;
        }

        /** {@code (v, v, ..)} literals. */
        List<String> tuple() {
            if (!take("(")) {
                return null;
            }
            List<String> out = new ArrayList<>();
            do {
                String value = value();
                if (value == null) {
                    return null;
                }
                out.add(value);
            } while (take(","));
            return take(")") ? out : null;
        }

        /** {@code a = x [AND b = y ..]} as {column, value} pairs; anything else ({@code <>}, OR, IN, LIKE) is null. */
        List<String[]> condition() {
            List<String[]> out = new ArrayList<>();
            do {
                String column = word();
                String value = take("=") ? value() : null;
                if (column.isEmpty() || value == null) {
                    return null;
                }
                out.add(new String[]{bare(column), value});
            } while (take("AND"));
            return out;
        }
    }
}
