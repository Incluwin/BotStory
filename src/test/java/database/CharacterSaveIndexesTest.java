package database;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A character save rewrites each section as DELETE ... WHERE column = ? then INSERT, in one transaction. A DELETE
 * whose column has no index scans the whole table and waits for every row another open save has written, so
 * concurrent saves (the shutdown's citizen unloads, autosaves) run one at a time. Every such DELETE must find its
 * rows through an index: one in the base schema (a key, or the index MySQL adds for a foreign key) or one the
 * 2026-10-09 extension creates.
 */
class CharacterSaveIndexesTest {
    private static final Path EXTENSION = Path.of("src/main/resources/db/extensions/2026-10-09-character-save-indexes.xml");
    private static final Pattern DELETE = Pattern.compile("DELETE FROM `?(\\w+)`? WHERE `?(\\w+)`?\\s*=\\s*\\?");

    @Test
    void everyDeleteOfTheSaveFindsItsRowsThroughAnIndex() throws IOException {
        Set<String> deletes = new TreeSet<>();
        String character = Files.readString(Path.of("src/main/java/client/Character.java"));
        deletes.addAll(deletes(between(character, "public synchronized void saveCharToDB(boolean notAutosave, SaveReason saveReason)",
                "public void sendPolice(int greason")));
        deletes.addAll(deletes(between(character, "private static void deleteQuestProgressWhereCharacterId(", "\n    }\n")));
        deletes.addAll(deletes(between(Files.readString(Path.of("src/main/java/server/CashShop.java")),
                "public void save(Connection con)", "\n    }\n")));
        String itemFactory = Files.readString(Path.of("src/main/java/client/inventory/ItemFactory.java"));
        assertTrue(itemFactory.contains("FROM `inventoryitems` LEFT JOIN `inventoryequipment` USING(`inventoryitemid`) WHERE `type` = ? AND `\");")
                && itemFactory.contains("query.append(account ? \"accountid\" : \"characterid\").append(\"` = ?\");"),
                "ItemFactory deletes a character's items by characterid and an account's (cash shop, storage) by accountid");
        deletes.add("inventoryitems.characterid");
        deletes.add("inventoryitems.accountid");

        assertTrue(deletes.containsAll(List.of("keymap.characterid", "queststatus.characterid", "wishlists.charid",
                "buddies.characterid")), "the save's statements were found: " + deletes);
        Set<String> indexed = indexedColumns();
        Set<String> scanning = new TreeSet<>(deletes);
        scanning.removeAll(indexed);
        assertEquals(Set.of(), scanning, "DELETEs of the save with no index on their column");
    }

    @Test
    void eachIndexIsCreatedOnceAndSkippedWhereItExists() throws IOException {
        String xml = Files.readString(EXTENSION);
        Matcher changeSet = Pattern.compile("<changeSet id=\"([^\"]+)\" author=\"ganre\">(.*?)</changeSet>", Pattern.DOTALL).matcher(xml);
        Set<String> names = new HashSet<>();
        int count = 0;
        while (changeSet.find()) {
            count++;
            String body = changeSet.group(2);
            Matcher create = Pattern.compile("<createIndex tableName=\"(\\w+)\" indexName=\"(\\w+)\">").matcher(body);
            assertTrue(create.find(), changeSet.group(1));
            String table = create.group(1);
            String index = create.group(2);
            assertTrue(names.add(index), "index named once: " + index);
            assertTrue(body.contains("<preConditions onFail=\"MARK_RAN\">")
                    && body.contains("<not><indexExists tableName=\"" + table + "\" indexName=\"" + index + "\"/></not>"),
                    changeSet.group(1) + " is skipped where " + index + " exists");
        }
        assertEquals(11, count);
        assertTrue(xml.chars().allMatch(c -> c < 128), "ASCII only");
    }

    /** "table.column" for every column that leads an index: base schema keys, foreign keys, ALTER ... ADD INDEX, the extension. */
    private static Set<String> indexedColumns() throws IOException {
        Set<String> indexed = new HashSet<>();
        Pattern createTable = Pattern.compile("CREATE TABLE (?:IF NOT EXISTS )?`?(\\w+)`?\\s*\\((.*?)\\n\\)", Pattern.DOTALL);
        Pattern key = Pattern.compile("(?:PRIMARY KEY|FOREIGN KEY|KEY|INDEX)\\s*(?:`?\\w+`?\\s*)?\\(\\s*`?(\\w+)`?");
        Pattern alter = Pattern.compile("ALTER TABLE `?(\\w+)`?\\s+ADD (?:UNIQUE )?(?:INDEX|KEY) `?\\w+`?\\s*\\(\\s*`?(\\w+)`?");
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/tables"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".sql") && !p.toString().endsWith("-down.sql")).toList()) {
                String sql = Files.readString(file);
                Matcher table = createTable.matcher(sql);
                while (table.find()) {
                    Matcher column = key.matcher(table.group(2));
                    while (column.find()) {
                        indexed.add(table.group(1) + "." + column.group(1));
                    }
                }
                Matcher added = alter.matcher(sql);
                while (added.find()) {
                    indexed.add(added.group(1) + "." + added.group(2));
                }
            }
        }
        Matcher created = Pattern.compile("<createIndex tableName=\"(\\w+)\" indexName=\"\\w+\">\\s*<column name=\"(\\w+)\"/>")
                .matcher(Files.readString(EXTENSION));
        while (created.find()) {
            indexed.add(created.group(1) + "." + created.group(2));
        }
        return indexed;
    }

    private static Set<String> deletes(String source) {
        Set<String> found = new TreeSet<>();
        Matcher delete = DELETE.matcher(source);
        while (delete.find()) {
            found.add(delete.group(1) + "." + delete.group(2));
        }
        return found;
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        assertTrue(from >= 0, start);
        int to = source.indexOf(end, from + start.length());
        assertTrue(to > from, end);
        return source.substring(from, to);
    }
}
