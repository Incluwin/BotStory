package server.equip;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side implementation of the "Secondary-stat requirement removal" plan
 * item (PLAN.md §95-157).
 *
 * Reads {@code MapleEdit/app/patches/zero-secondary-stats.json} at startup and
 * remembers which of {@code reqSTR / reqDEX / reqINT / reqLUK} should be
 * clamped to zero for each itemId. {@link server.ItemInformationProvider#getEquipStats}
 * applies the override right before caching so the rest of the server never
 * sees the original stat requirement.
 *
 * Implemented server-side because the equivalent WZ-level patch (applied via
 * MapleEdit's {@code wz_item_editor.apply_item_patches}) corrupts Character.wz
 * in a way that crashes the v83 client's avatar renderer — see the project
 * memory {@code project_wz_file_bloat_crash.md}. This path leaves the WZ
 * pristine and accomplishes the same goal from the player's perspective.
 */
public final class SecondaryStatRequirementOverrides {
    private static final Logger log = LoggerFactory.getLogger(SecondaryStatRequirementOverrides.class);

    /** MapleEdit's copy next to the server, relative to the server working directory; MapleEdit regenerates it. */
    static final Path MAPLEEDIT_PATH = Paths.get("../MapleEdit/app/patches/zero-secondary-stats.json");

    /** The copy shipped with the server, used when there is no MapleEdit checkout next to it. */
    static final Path BUNDLED_PATH = Paths.get("scripts/config/zero-secondary-stats.json");

    /** Stat fields recognised by this override. Anything else in the patch is ignored. */
    public enum Field {
        reqSTR, reqDEX, reqINT, reqLUK;

        static Field maybe(String name) {
            try {
                return valueOf(name);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    private static Map<Integer, Map<Field, Integer>> OVERRIDES = Collections.emptyMap();
    private static boolean loaded = false;

    private SecondaryStatRequirementOverrides() {}

    /** Load MapleEdit's copy when there is one, else the bundled copy (both relative to the working directory). */
    public static void load() {
        load(defaultPath(MAPLEEDIT_PATH, BUNDLED_PATH));
    }

    static Path defaultPath(Path mapleEdit, Path bundled) {
        return Files.exists(mapleEdit) ? mapleEdit : bundled;
    }

    /** Load from an explicit path. Missing file is treated as "no overrides". */
    public static synchronized void load(Path path) {
        if (!Files.exists(path)) {
            log.info("SecondaryStatRequirementOverrides: no patch file at {}, running with zero overrides",
                    path.toAbsolutePath());
            OVERRIDES = Collections.emptyMap();
            loaded = true;
            return;
        }
        try {
            String body = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            OVERRIDES = parse(body);
            log.info("SecondaryStatRequirementOverrides: loaded overrides for {} item(s) from {}",
                    OVERRIDES.size(), path.toAbsolutePath());
        } catch (IOException e) {
            log.warn("SecondaryStatRequirementOverrides: failed to read {}: {}", path, e.getMessage());
            OVERRIDES = Collections.emptyMap();
        }
        loaded = true;
    }

    /** Parse the fixed-shape JSON without pulling in a full JSON library.
     *
     * The patch file is generator-produced with a stable pretty-printed layout:
     * one top-level {@code item_patches} array of objects, each with an
     * {@code item_id} number and a {@code fields} object of numeric values.
     * A regex pass over the entire text is sufficient and keeps us off the
     * classpath for a single dependency that isn't warranted elsewhere.
     */
    static Map<Integer, Map<Field, Integer>> parse(String body) {
        Map<Integer, Map<Field, Integer>> out = new HashMap<>();
        // Match each {"item_id": N, "fields": {...}} entry. The regex intentionally
        // accepts whitespace variation so hand-edited patches keep loading.
        Pattern itemPat = Pattern.compile(
                "\\{\\s*\"item_id\"\\s*:\\s*(-?\\d+)\\s*,\\s*\"fields\"\\s*:\\s*\\{([^}]*)\\}\\s*\\}",
                Pattern.DOTALL);
        Pattern fieldPat = Pattern.compile("\"(\\w+)\"\\s*:\\s*(-?\\d+)");
        Matcher m = itemPat.matcher(body);
        while (m.find()) {
            int itemId;
            try {
                itemId = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            String fieldsBlock = m.group(2);
            Matcher fm = fieldPat.matcher(fieldsBlock);
            Map<Field, Integer> fields = new EnumMap<>(Field.class);
            while (fm.find()) {
                Field f = Field.maybe(fm.group(1));
                if (f == null) {
                    continue;
                }
                try {
                    fields.put(f, Integer.parseInt(fm.group(2)));
                } catch (NumberFormatException ignored) {
                }
            }
            if (!fields.isEmpty()) {
                out.put(itemId, fields);
            }
        }
        return out;
    }

    /** Apply the override in place, if any, for the given itemId.
     *
     * @param itemId the equipment item id
     * @param equipStats the map produced by {@code ItemInformationProvider.getEquipStats};
     *                   mutated in place when an override is defined for itemId
     */
    public static void apply(int itemId, Map<String, Integer> equipStats) {
        if (!loaded || equipStats == null) {
            return;
        }
        Map<Field, Integer> fields = OVERRIDES.get(itemId);
        if (fields == null) {
            return;
        }
        for (Map.Entry<Field, Integer> e : fields.entrySet()) {
            equipStats.put(e.getKey().name(), e.getValue());
        }
    }

    /** Test hook: count of items with active overrides. */
    public static int overrideCount() {
        return OVERRIDES.size();
    }
}
