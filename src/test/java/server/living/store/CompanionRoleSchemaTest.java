package server.living.store;

import liquibase.util.StringUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Changeset 54 (docs/BOT_CONCEPT_PLAN.md F4): living_roster.role and role_target, registered once with its rollback
 * and appended last; two nullable columns, so every existing row is a companion with no role; ASCII only; and the
 * full-row roster writes never name them (a role is written apart, LivingStore.saveRosterRole).
 */
class CompanionRoleSchemaTest {
    private static final Path CHANGELOG = Path.of("src/main/resources/db/changelog-tables.xml");
    private static final Path UP = Path.of("src/main/resources/db/tables/045-companion-role.sql");
    private static final Path DOWN = Path.of("src/main/resources/db/tables/045-companion-role-down.sql");

    @Test
    void changeset54IsRegisteredOnceWithItsRollback() throws IOException {
        String changelog = Files.readString(CHANGELOG);
        int start = changelog.indexOf("<changeSet id=\"54-companion-role\" author=\"ganre\">");
        assertTrue(start > changelog.indexOf("<changeSet id=\"52-owner-habits\""), "after 52");
        String changeSet = changelog.substring(start, changelog.indexOf("</changeSet>", start));
        assertTrue(changeSet.contains("<sqlFile path=\"db/tables/045-companion-role.sql\" splitStatements=\"true\"/>"));
        assertTrue(changeSet.indexOf("<rollback>") < changeSet.indexOf(
                "<sqlFile path=\"db/tables/045-companion-role-down.sql\" splitStatements=\"true\"/>"));
        assertEquals(changelog.indexOf("<changeSet id=\"54-"), changelog.lastIndexOf("<changeSet id=\"54-"), "once");
    }

    @Test
    void twoNullableColumnsUpAndBothGoneDown() throws IOException {
        String[] up = StringUtil.processMultiLineSQL(Files.readString(UP), false, true, null);
        String[] down = StringUtil.processMultiLineSQL(Files.readString(DOWN), false, true, null);
        assertEquals(1, up.length, String.join("\n;;\n", up));
        String alter = StringUtil.stripComments(up[0]).strip();
        assertTrue(alter.startsWith("ALTER TABLE living_roster"));
        assertTrue(alter.contains("ADD COLUMN role VARCHAR(16) NULL"));
        assertTrue(alter.contains("ADD COLUMN role_target VARCHAR(32) NULL"));
        assertFalse(alter.toUpperCase().contains("NOT NULL"), "an existing row has no role");
        assertEquals(1, down.length);
        String drop = StringUtil.stripComments(down[0]).strip();
        assertTrue(drop.contains("DROP COLUMN role_target") && drop.contains("DROP COLUMN role"));
        assertTrue(Files.readString(UP).chars().allMatch(c -> c < 128) && Files.readString(DOWN).chars().allMatch(c -> c < 128),
                "ASCII only");
    }

    @Test
    void theFullRowRosterWritesNeverNameTheRoleColumns() throws IOException {
        String store = Files.readString(Path.of("src/main/java/server/living/store/LivingStore.java"));
        assertTrue(store.contains("UPDATE living_roster SET role = ?, role_target = ?"), "the one write of a role");
        assertEquals(store.indexOf("role = ?"), store.lastIndexOf("role = ?"), "and no other write names it");
        assertEquals(store.indexOf("role_target = ?"), store.lastIndexOf("role_target = ?"));
    }
}
