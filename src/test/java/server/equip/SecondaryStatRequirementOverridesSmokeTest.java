package server.equip;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Smoke-tests the override loader against the real patch file when present. */
class SecondaryStatRequirementOverridesSmokeTest {

    @Test
    void parseInlineFixture() {
        String body = "{\n" +
                "  \"_meta\": {\"total_items\": 3},\n" +
                "  \"item_patches\": [\n" +
                "    {\"item_id\": 1002013, \"fields\": {\"reqLUK\": 0}},\n" +
                "    {\"item_id\": 1302003, \"fields\": {\"reqSTR\": 0, \"reqDEX\": 0}},\n" +
                "    {\"item_id\": 1332000, \"fields\": {\"notRecognised\": 99, \"reqINT\": 0}}\n" +
                "  ]\n" +
                "}";
        Map<Integer, Map<SecondaryStatRequirementOverrides.Field, Integer>> parsed =
                SecondaryStatRequirementOverrides.parse(body);
        assertEquals(3, parsed.size());

        Map<SecondaryStatRequirementOverrides.Field, Integer> a = parsed.get(1002013);
        assertEquals(Integer.valueOf(0), a.get(SecondaryStatRequirementOverrides.Field.reqLUK));

        Map<SecondaryStatRequirementOverrides.Field, Integer> b = parsed.get(1302003);
        assertEquals(2, b.size());
        assertEquals(Integer.valueOf(0), b.get(SecondaryStatRequirementOverrides.Field.reqSTR));
        assertEquals(Integer.valueOf(0), b.get(SecondaryStatRequirementOverrides.Field.reqDEX));

        Map<SecondaryStatRequirementOverrides.Field, Integer> c = parsed.get(1332000);
        assertNotNull(c);
        // Non-req fields must be silently dropped.
        assertEquals(1, c.size());
        assertEquals(Integer.valueOf(0), c.get(SecondaryStatRequirementOverrides.Field.reqINT));
    }

    @Test
    void parseRealPatchFileWhenAvailable() throws Exception {
        // Best-effort smoke: only runs when the real patch file is sitting in its
        // conventional location. Unit tests shouldn't fail just because the patch
        // hasn't been generated in a given clone.
        Path candidate = Paths.get("../MapleEdit/app/patches/zero-secondary-stats.json");
        if (!Files.exists(candidate)) {
            return;
        }
        String body = new String(Files.readAllBytes(candidate));
        Map<Integer, Map<SecondaryStatRequirementOverrides.Field, Integer>> parsed =
                SecondaryStatRequirementOverrides.parse(body);
        // The patch _meta declares emitted=1083 at the time of writing. We don't
        // pin the exact number (the generator may re-emit) but assert it looks
        // sensible.
        assertTrue(parsed.size() >= 500,
                "expected at least 500 item overrides, got " + parsed.size());
        // Every entry must have at least one recognised req field.
        for (Map.Entry<Integer, Map<SecondaryStatRequirementOverrides.Field, Integer>> e : parsed.entrySet()) {
            assertTrue(!e.getValue().isEmpty(), "itemId " + e.getKey() + " has no recognised fields");
        }
    }

    @Test
    void bundledPatchFileShipsWithTheServer() throws Exception {
        // A checkout without MapleEdit next to it loads this copy, so it must always be there and parse.
        String body = Files.readString(SecondaryStatRequirementOverrides.BUNDLED_PATH);
        Map<Integer, Map<SecondaryStatRequirementOverrides.Field, Integer>> parsed =
                SecondaryStatRequirementOverrides.parse(body);
        assertTrue(parsed.size() >= 500, "expected at least 500 item overrides, got " + parsed.size());
    }

    @Test
    void mapleEditCopyWinsOverTheBundledOne(@TempDir Path dir) throws Exception {
        Path mapleEdit = dir.resolve("maple-edit.json");
        Path bundled = dir.resolve("bundled.json");

        assertEquals(bundled, SecondaryStatRequirementOverrides.defaultPath(mapleEdit, bundled));
        Files.writeString(mapleEdit, "{}");
        assertEquals(mapleEdit, SecondaryStatRequirementOverrides.defaultPath(mapleEdit, bundled));
    }
}
