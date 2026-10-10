package server.set;

import client.Character;
import client.inventory.Item;
import client.inventory.InventoryType;
import com.esotericsoftware.yamlbeans.YamlReader;
import constants.string.CharsetConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads set-effect definitions from scripts/config/set-items.yml and computes the cumulative
 * bonus each character gets based on what they have equipped. See PLAN.md → "Set effects system".
 *
 * Bonuses that this class can currently realise end-to-end (via Character.recalcEquipStats):
 * STR / DEX / INT / LUK / WATK / MATK / HP / MP.
 *
 * Bonuses that are recorded but not yet applied to combat math (v83 resolves these client-side
 * and Cosmic has no server hook): WDEF / MDEF / ACC / AVOID / Speed / Jump / bossDamage% /
 * finalDamage%. These will show in !checksets and the YAML stays honest about them so future
 * wiring only needs to find a hook, not re-author config.
 */
public final class SetItemManager {
    private static final Logger log = LoggerFactory.getLogger(SetItemManager.class);
    private static final String DEFAULT_CONFIG_FILE = "scripts/config/set-items.yml";

    private static Map<String, SetItemDef> setsById = Collections.emptyMap();
    private static Map<Integer, List<String>> itemToSetIds = Collections.emptyMap();

    private SetItemManager() {
    }

    public static void loadAllSets() {
        loadAllSets(Paths.get(DEFAULT_CONFIG_FILE));
    }

    public static void loadAllSets(Path configPath) {
        if (!Files.exists(configPath)) {
            log.info("SetItemManager: no set config at {}, running with zero sets", configPath.toAbsolutePath());
            installConfig(new SetItemsConfig());
            return;
        }
        try (Reader r = Files.newBufferedReader(configPath, CharsetConstants.CHARSET)) {
            YamlReader yr = new YamlReader(r);
            SetItemsConfig cfg = yr.read(SetItemsConfig.class);
            installConfig(cfg != null ? cfg : new SetItemsConfig());
            log.info("SetItemManager: loaded {} sets covering {} items from {}",
                    setsById.size(), itemToSetIds.size(), configPath.toAbsolutePath());
        } catch (IOException e) {
            log.warn("SetItemManager: failed to load {}: {}", configPath, e.getMessage());
            installConfig(new SetItemsConfig());
        }
    }

    /** Package-private hook for tests — bypass YAML parsing by supplying a config object directly. */
    static void installConfig(SetItemsConfig cfg) {
        Map<String, SetItemDef> byId = new LinkedHashMap<>();
        Map<Integer, List<String>> reverse = new HashMap<>();
        if (cfg != null && cfg.sets != null) {
            for (SetItemDef def : cfg.sets) {
                if (def == null || def.id == null || def.id.isEmpty()) {
                    log.warn("SetItemManager: skipping set with null/empty id");
                    continue;
                }
                if (byId.containsKey(def.id)) {
                    log.warn("SetItemManager: duplicate set id '{}', last wins", def.id);
                }
                byId.put(def.id, def);
                if (def.members != null) {
                    for (Integer itemId : def.members) {
                        if (itemId == null) continue;
                        reverse.computeIfAbsent(itemId, k -> new ArrayList<>()).add(def.id);
                    }
                }
            }
        }
        setsById = Collections.unmodifiableMap(byId);
        itemToSetIds = Collections.unmodifiableMap(reverse);
    }

    public static SetBonusTotal compute(Character chr) {
        if (setsById.isEmpty()) {
            return SetBonusTotal.EMPTY;
        }
        List<Integer> equipped = new ArrayList<>();
        for (Item item : chr.getInventory(InventoryType.EQUIPPED)) {
            equipped.add(item.getItemId());
        }
        return computeFromItemIds(equipped);
    }

    /** Pure, test-friendly core: given a list of equipped item IDs, produce the cumulative bonus. */
    public static SetBonusTotal computeFromItemIds(Collection<Integer> itemIds) {
        if (setsById.isEmpty() || itemIds == null || itemIds.isEmpty()) {
            return SetBonusTotal.EMPTY;
        }
        Map<String, Integer> pieceCount = new LinkedHashMap<>();
        for (Integer itemId : itemIds) {
            List<String> ids = itemToSetIds.get(itemId);
            if (ids == null) continue;
            for (String id : ids) {
                pieceCount.merge(id, 1, Integer::sum);
            }
        }
        if (pieceCount.isEmpty()) {
            return SetBonusTotal.EMPTY;
        }
        int str = 0, dex = 0, int_ = 0, luk = 0;
        int watk = 0, matk = 0, hp = 0, mp = 0;
        int wdef = 0, mdef = 0, acc = 0, avoid = 0, speed = 0, jump = 0;
        int bossDmg = 0, finalDmg = 0;
        List<ActiveSet> active = new ArrayList<>(pieceCount.size());
        for (Map.Entry<String, Integer> e : pieceCount.entrySet()) {
            SetItemDef def = setsById.get(e.getKey());
            if (def == null) continue;
            int pieces = e.getValue();
            int total = def.members != null ? def.members.size() : 0;
            boolean anyBonus = false;
            if (def.bonuses == null) continue;
            for (SetBonusDef b : def.bonuses) {
                if (b == null || b.pieces > pieces) continue;
                anyBonus = true;
                str += b.allStats;
                dex += b.allStats;
                int_ += b.allStats;
                luk += b.allStats;
                watk += b.watk;
                matk += b.matk;
                hp += b.hp;
                mp += b.mp;
                wdef += b.wdef;
                mdef += b.mdef;
                acc += b.acc;
                avoid += b.avoid;
                speed += b.speed;
                jump += b.jump;
                bossDmg += b.bossDamagePct;
                finalDmg += b.finalDamagePct;
            }
            if (anyBonus) {
                active.add(new ActiveSet(def.id, def.name, pieces, total));
            }
        }
        return new SetBonusTotal(str, dex, int_, luk, watk, matk, hp, mp,
                wdef, mdef, acc, avoid, speed, jump, bossDmg, finalDmg,
                Collections.unmodifiableList(active));
    }

    /**
     * Send the player a chat line whenever an active set's piece count changes. No message on
     * login — {@code prev == null} suppresses the announce so players don't get a wall of
     * "Set bonus active" lines on every character load.
     */
    public static void announceChanges(Character chr, SetBonusTotal prev, SetBonusTotal curr) {
        if (prev == null || curr == null || chr == null) {
            return;
        }
        Map<String, ActiveSet> prevById = indexById(prev.activeSets);
        Map<String, ActiveSet> currById = indexById(curr.activeSets);

        for (ActiveSet c : curr.activeSets) {
            ActiveSet p = prevById.get(c.id);
            if (p == null || p.pieces != c.pieces) {
                chr.yellowMessage("Set bonus active: " + c.name + " (" + c.pieces + "/" + c.total + ")");
            }
        }
        for (ActiveSet p : prev.activeSets) {
            if (!currById.containsKey(p.id)) {
                chr.yellowMessage("Set bonus lost: " + p.name);
            }
        }
    }

    private static Map<String, ActiveSet> indexById(List<ActiveSet> list) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, ActiveSet> m = new HashMap<>(list.size() * 2);
        for (ActiveSet a : list) {
            m.put(a.id, a);
        }
        return m;
    }

    // Read-only accessor for tests and debug commands.
    public static Map<String, SetItemDef> getSetsById() {
        return setsById;
    }
}
