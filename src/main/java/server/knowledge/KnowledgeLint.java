package server.knowledge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Content lint facts from the knowledge layer (docs/SERVER_KNOWLEDGE_PLAN.md section 9): an id's name, whether an id
 * exists, where players really get an item, gear levels, training-field and PQ-lobby sanity. Host lints (living-world
 * ContentLint, population PopulationLoader) walk their content and pass ids with a "where" prefix; this class words and
 * grades the findings. Only an unknown item, mob or map is an ERROR, and only while the knowledge file matches the WZ it
 * was exported from; everything else is a WARN, so authoring saves are never blocked by a content decision.
 */
public final class KnowledgeLint {
    /** Who is handed a loadout, at which levels (a persona's band, a career band). */
    public record Holder(String who, int minLevel, int maxLevel) {
    }

    private static final String EXPORT = "run tools\\knowledge-export.bat";
    /** The changed-input marker {@link KnowledgeLoader#changedInputs} adds when the exporter's input list changed. */
    private static final String ALL_INPUTS = "inputs";
    /** Exporter inputs that list every id of a kind: a change there may add ids the file does not have yet. */
    private static final Map<EntityKind, List<String>> TABLE_INPUTS = Map.of(
            EntityKind.ITEM, List.of("wz/Character.wz/", "wz/Item.wz/"),
            EntityKind.MOB, List.of("wz/Mob.wz"),
            EntityKind.MAP, List.of("wz/Map.wz/Map"));

    private static volatile KnowledgeLint offline;   // set by the command-line lints only
    private static volatile KnowledgeLint live;      // cached per published snapshot

    private final WorldKnowledge knowledge;
    private final List<String> stale;
    private final boolean rows;
    private final boolean seed;
    /** Maps some other map's portal or a travel.yaml leg leads to. */
    private final Set<Integer> entrances;

    /**
     * @param stale knowledge-file inputs changed since the export ({@link KnowledgeLoader#changedInputs})
     * @param rows  drop and shop rows are present (the live overlay or the seed replay); without them sources are not checked
     * @param seed  offline: the rows are the Liquibase seed and DB-placed life is not loaded
     */
    KnowledgeLint(WorldKnowledge knowledge, List<String> stale, boolean rows, boolean seed) {
        this.knowledge = knowledge;
        this.stale = List.copyOf(stale);
        this.rows = rows;
        this.seed = seed;
        Set<Integer> in = new HashSet<>();
        for (MapFact map : knowledge.maps()) {
            for (int exit : map.exits()) {
                if (exit != map.id()) {
                    in.add(exit);
                }
            }
        }
        for (TravelLeg leg : knowledge.travel()) {
            in.add(leg.toMap());
        }
        this.entrances = Set.copyOf(in);
    }

    /** The offline lint when installed, else the live snapshot when ready (cached per snapshot), else null: checks skipped. */
    public static KnowledgeLint current() {
        KnowledgeLint installed = offline;
        if (installed != null) {
            return installed;
        }
        WorldKnowledge snapshot = WorldKnowledge.current();
        if (!snapshot.ready()) {
            return null;
        }
        KnowledgeLint cached = live;
        if (cached == null || cached.knowledge != snapshot) {
            cached = new KnowledgeLint(snapshot, KnowledgeLoader.staleSources(), true, false);
            live = cached;
        }
        return cached;
    }

    /**
     * Command-line lints, before loading content: the knowledge file, travel.yaml and aliases.yaml from knowledgeDir plus the
     * SeedData replay of dbRoot. Returns notes for the operator (a missing seed folder, replay warnings, a stale file, or why
     * nothing was installed); empty when everything loaded.
     */
    public static List<String> installOffline(Path knowledgeDir, Path dbRoot) {
        List<String> notes = new ArrayList<>();
        Path path = knowledgeDir.resolve(KnowledgeSources.FILE_NAME);
        KnowledgeFile file;
        List<TravelLeg> travel;
        AliasCatalog aliases;
        try {
            file = KnowledgeFile.read(path);
            travel = TravelCatalog.load(knowledgeDir.resolve(KnowledgeLoader.TRAVEL_FILE), file);
            aliases = AliasCatalog.load(knowledgeDir.resolve(KnowledgeLoader.ALIAS_FILE), file);
        } catch (IOException | RuntimeException failure) {
            notes.add("cannot read " + path + " (" + failure + "); id and source checks skipped");
            return notes;
        }
        KnowledgeOverlay overlay = KnowledgeOverlay.EMPTY;
        boolean rows = false;
        if (!Files.isDirectory(dbRoot)) {
            notes.add("no Liquibase seed data at " + dbRoot + "; item source checks skipped");
        } else {
            try {
                SeedData.Replay replay = SeedData.replay(dbRoot);
                for (String warning : replay.warnings()) {
                    notes.add("seed data " + warning);
                }
                overlay = SeedData.overlay(replay);
                rows = true;
            } catch (IOException | RuntimeException failure) {
                notes.add("cannot replay the Liquibase seed data at " + dbRoot + " (" + failure + "); item source checks skipped");
            }
        }
        List<String> stale;
        try {
            stale = KnowledgeLoader.changedInputs(file);
        } catch (IOException failure) {
            stale = List.of(ALL_INPUTS);
            notes.add("staleness check failed (" + failure + ")");
        }
        if (!stale.isEmpty()) {
            notes.add("knowledge file older than " + String.join(", ", stale) + "; " + EXPORT
                    + " (until then unknown ids from those inputs are warnings)");
        }
        offline = new KnowledgeLint(WorldKnowledge.build(file, travel, aliases, Map.of(), overlay, 1), stale, rows, true);
        return notes;
    }

    static void clearOffline() {
        offline = null;
    }

    /** "4003003 Fairy Wing", "209080000 Extra Frosty Snow Zone near Happyville", "1222014 (unnamed custom equip)"; the bare id when unknown. */
    public String label(EntityKind kind, int id) {
        String name = switch (kind) {
            case ITEM -> knowledge.item(id).map(item -> item.unnamed() ? "(unnamed custom equip)" : item.name()).orElse("");
            case MOB -> knowledge.mob(id).map(MobFact::name).orElse("");
            case MAP -> knowledge.map(id).map(this::mapName).orElse("");
            case NPC -> knowledge.npc(id).map(NpcFact::name).orElse("");
            case QUEST -> knowledge.quest(id).map(QuestFact::name).orElse("");
            default -> "";
        };
        return name.isEmpty() ? String.valueOf(id) : id + " " + name;
    }

    private String mapName(MapFact map) {
        if (map.name().isEmpty()) {
            return "";
        }
        int town = knowledge.townOf(map.id());
        String near = town == 0 || town == map.id() ? "" : knowledge.map(town).map(MapFact::name).orElse("");
        return near.isEmpty() ? map.name() : map.name() + " near " + near;
    }

    /**
     * True when the id exists; otherwise one finding. Items, mobs and maps are complete tables, so an unknown one is an
     * ERROR unless the knowledge file is older than the WZ that lists them. The NPC table leaves out unnamed, unplaced
     * Npc.wz entries, so an unknown NPC is only a WARN.
     */
    public boolean require(EntityKind kind, int id, String where, List<String> out) {
        boolean exists = switch (kind) {
            case ITEM -> knowledge.item(id).isPresent();
            case MOB -> knowledge.mob(id).isPresent();
            case MAP -> knowledge.map(id).isPresent();
            case NPC -> knowledge.npc(id).isPresent();
            case QUEST -> knowledge.quest(id).isPresent();
            default -> true;
        };
        if (exists) {
            return true;
        }
        String what = kind.name().toLowerCase(Locale.ROOT) + " " + id;
        String input = staleInput(kind);
        if (kind == EntityKind.NPC) {
            out.add("WARN " + where + ": " + what + " is not a named or placed NPC in the server WZ");
        } else if (input != null) {
            out.add("WARN " + where + ": " + what + " is not in the knowledge file, which is older than " + input + "; " + EXPORT);
        } else {
            out.add("ERROR " + where + ": " + what + " does not exist in the server WZ");
        }
        return false;
    }

    private String staleInput(EntityKind kind) {
        for (String input : stale) {
            if (ALL_INPUTS.equals(input)) {
                return input;
            }
            for (String prefix : TABLE_INPUTS.getOrDefault(kind, List.of())) {
                if (input.startsWith(prefix)) {
                    return input;
                }
            }
        }
        return null;
    }

    /**
     * A WARN when players cannot get the item in the open ({@link WorldKnowledge#obtainable}), naming why: only
     * script-spawned droppers, only quest-gated rows, droppers that spawn only on instanced maps or never, or no source.
     */
    public void obtainable(int itemId, String where, List<String> out) {
        if (!rows || knowledge.obtainable(itemId)) {
            return;
        }
        String item = "item " + label(EntityKind.ITEM, itemId);
        List<DropSource> sources = knowledge.dropSources(itemId); // no global or shop source, or it would be obtainable
        Set<Integer> scripted = new TreeSet<>();
        Set<String> quests = new LinkedHashSet<>();
        Set<Integer> droppers = new TreeSet<>();
        for (DropSource source : sources) {
            droppers.add(source.mobId());
            if (source.questId() != 0) {
                quests.add(label(EntityKind.QUEST, source.questId()) + " (" + label(EntityKind.MOB, source.mobId()) + ")");
            } else if (!knowledge.scriptSpawns(source.mobId()).isEmpty()) {
                scripted.add(source.mobId());
            }
        }
        if (!scripted.isEmpty()) {
            out.add("WARN " + where + ": " + item + " drops only from " + mobs(scripted) + ", which only a script spawns ("
                    + scriptSites(scripted) + "); nobody can farm it in the open");
        } else if (!quests.isEmpty()) {
            out.add("WARN " + where + ": " + item + " only drops while a Quest.wz quest is active: " + String.join(", ", quests));
        } else if (!droppers.isEmpty()) {
            boolean placed = droppers.stream().anyMatch(mob -> !knowledge.spawnMaps(mob).isEmpty());
            out.add("WARN " + where + ": " + item + " drops only from " + mobs(droppers)
                    + (placed ? ", which spawn only on event, PQ or other instanced maps" : ", which never spawn"));
        } else {
            out.add("WARN " + where + ": " + item + ": no monster drops it and no NPC shop sells it"
                    + (seed ? " in the Liquibase seed data" : "") + " (quest rewards, NPC scripts and reactors are not checked)");
        }
    }

    /**
     * A WARN when a persona's {@code knows:} entry (plan 7.7) is neither "prices" nor a map, region, PQ, mob or item the
     * knowledge layer knows by alias or exact name: that entry adds nothing to the Resident's past.
     */
    public void personaKnows(String text, String where, List<String> out) {
        if (!PastSynthesis.PRICES.equals(text) && PastSynthesis.knows(knowledge, text).isEmpty()) {
            out.add("WARN " + where + ": knows '" + text + "' is no place, monster or item known by that name (aliases.yaml or the"
                    + " exact name); it is skipped");
        }
    }

    /** A WARN when the mob never spawns, or only appears through a script (players cannot meet it in the open). */
    public void spawns(int mobId, String where, List<String> out) {
        String mob = "mob " + label(EntityKind.MOB, mobId);
        boolean scripted = !knowledge.scriptSpawns(mobId).isEmpty();
        if (knowledge.spawnMaps(mobId).isEmpty() && !scripted) {
            out.add("WARN " + where + ": " + mob + " never spawns: no map places it and no script spawns it"
                    + (seed ? " (DB-placed life is not checked offline)" : ""));
        } else if (scripted && !knowledge.wild(mobId)) {
            out.add("WARN " + where + ": " + mob + " only appears through a script (" + scriptSites(Set.of(mobId)) + ")");
        }
    }

    /** A WARN when citizens are sent to train on a map players cannot share with them: instanced, empty, or with no way in. */
    public void trainingField(int mapId, String where, List<String> out) {
        MapFact map = knowledge.map(mapId).orElse(null);
        if (map == null) {
            return;
        }
        String label = label(EntityKind.MAP, mapId);
        if (map.forcedReturn() != 0) {
            out.add("WARN " + where + ": map " + label + " sends players back to " + label(EntityKind.MAP, map.forcedReturn())
                    + " when they leave or log out (instanced or private)");
        } else if (map.mobs().isEmpty()) {
            out.add("WARN " + where + ": map " + label + " has no monsters in the server WZ");
        } else if (!entrances.contains(mapId)) {
            out.add("WARN " + where + ": no portal or travel leg leads to map " + label
                    + " (only an NPC or a computed warp can); players cannot walk in on the citizens training there");
        }
    }

    /** A WARN when the aliases.yaml PQ named {@code pq} starts at its NPC's map, not at {@code mapId}; unknown PQs pass. */
    public void lobby(String pq, int mapId, String where, List<String> out) {
        Optional<NameIndex.Candidate> best = knowledge.names().resolveSlot(pq, List.of(EntityKind.PQ)).best();
        if (best.isEmpty() || !"alias".equals(best.get().how())) {
            return;
        }
        Entity entity = best.get().entity();
        if (entity.npc() != 0 && !knowledge.npcMaps(entity.npc()).contains(mapId)) {
            out.add("WARN " + where + ": the PQ starts at " + label(EntityKind.MAP, entity.ids().getInt(0)) + " (NPC "
                    + label(EntityKind.NPC, entity.npc()) + "), not at " + label(EntityKind.MAP, mapId));
        }
    }

    /**
     * WARNs for dead gear: an equip whose level is above every level some holder reaches (its band max), and an outfit slot
     * without a 0 whose lowest level is. Gear a band grows into is fine: the wardrobe wears the first entry the game allows.
     */
    public void gear(String where, List<Integer> equips, Map<String, List<Integer>> outfit, List<Holder> holders,
                     List<String> out) {
        for (int equip : equips) {
            int level = reqLevel(equip);
            List<Holder> below = below(holders, level);
            if (!below.isEmpty()) {
                out.add("WARN " + where + ": equip " + label(EntityKind.ITEM, equip) + " needs level " + level + ", above "
                        + holders(below) + "; it is carried, never worn");
            }
        }
        for (Map.Entry<String, List<Integer>> slot : outfit.entrySet()) {
            if (slot.getValue().isEmpty() || slot.getValue().contains(0)) {
                continue;
            }
            int lowest = slot.getValue().stream().mapToInt(this::reqLevel).min().orElse(0);
            List<Holder> below = below(holders, lowest);
            if (!below.isEmpty()) {
                out.add("WARN " + where + " outfit " + slot.getKey() + ": nothing in the pool is wearable below level " + lowest
                        + ", above " + holders(below));
            }
        }
    }

    /**
     * A friendship gear pool (living-world rewards.gear): every id an equipment piece that is not cash; a WARN naming
     * the jobs no piece is made for, since those players are never offered the quest.
     */
    public void friendGear(String where, List<Integer> pool, List<String> out) {
        int covered = 0;
        for (int itemId : pool) {
            if (!require(EntityKind.ITEM, itemId, where, out)) {
                continue;
            }
            ItemFact item = knowledge.item(itemId).orElseThrow();
            if (!item.equip()) {
                out.add("ERROR " + where + ": " + label(EntityKind.ITEM, itemId) + " is not equipment");
            } else if (item.cash()) {
                out.add("ERROR " + where + ": " + label(EntityKind.ITEM, itemId) + " is a cash item (it takes no scrolls)");
            } else {
                covered |= item.reqJob() == 0 ? 0x1F : item.reqJob();
            }
        }
        List<String> missing = new ArrayList<>();
        String[] jobs = {"warriors", "magicians", "bowmen", "thieves", "pirates"};
        for (int bit = 0; bit < jobs.length; bit++) {
            if ((covered & (1 << bit)) == 0) {
                missing.add(jobs[bit]);
            }
        }
        if (!missing.isEmpty() && missing.size() < jobs.length) {
            out.add("WARN " + where + ": nothing for " + String.join(", ", missing) + "; they are never offered the quest");
        }
    }

    /**
     * A giver's own friendship piece (living-world rewards.gear with scrolls): non-cash equipment, and a WARN when it is
     * not made for the giver's class ({@code giverJobMask}, a reqJob bit; -1 unknown, 0 a beginner).
     */
    public void giversGear(String where, int itemId, String giver, int giverJobMask, List<String> out) {
        if (!require(EntityKind.ITEM, itemId, where, out)) {
            return;
        }
        ItemFact item = knowledge.item(itemId).orElseThrow();
        if (!item.equip()) {
            out.add("ERROR " + where + ": " + label(EntityKind.ITEM, itemId) + " is not equipment");
        } else if (item.cash()) {
            out.add("ERROR " + where + ": " + label(EntityKind.ITEM, itemId) + " is a cash item (it takes no scrolls)");
        } else if (giverJobMask >= 0 && item.reqJob() != 0 && (item.reqJob() & giverJobMask) == 0) {
            out.add("WARN " + where + ": " + label(EntityKind.ITEM, itemId) + " is not made for " + giver + "'s class");
        }
    }

    private int reqLevel(int itemId) {
        return knowledge.item(itemId).map(ItemFact::reqLevel).orElse(0);
    }

    private static List<Holder> below(List<Holder> holders, int level) {
        return level <= 0 ? List.of() : holders.stream().filter(holder -> holder.maxLevel() < level).toList();
    }

    private static String holders(List<Holder> holders) {
        Holder first = holders.get(0);
        return first.who() + " (levels " + first.minLevel() + "-" + first.maxLevel() + ")"
                + (holders.size() > 1 ? " and " + (holders.size() - 1) + " more" : "");
    }

    private String mobs(Set<Integer> mobIds) {
        List<String> labels = new ArrayList<>();
        for (int mobId : mobIds) {
            labels.add(label(EntityKind.MOB, mobId));
        }
        return labels.size() == 1 ? labels.get(0)
                : String.join(", ", labels.subList(0, labels.size() - 1)) + " and " + labels.get(labels.size() - 1);
    }

    /** "NPC 9220004 Happy at 209080000 ..." per distinct (NPC, script) that spawns the mobs. */
    private String scriptSites(Set<Integer> mobIds) {
        Set<String> sites = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        for (int mobId : mobIds) {
            for (ScriptSpawn spawn : knowledge.scriptSpawns(mobId)) {
                if (!seen.add(spawn.viaNpc() + "|" + spawn.script())) {
                    continue;
                }
                List<Integer> maps = !spawn.maps().isEmpty() ? spawn.maps()
                        : spawn.viaNpc() != 0 ? knowledge.npcMaps(spawn.viaNpc()) : List.of();
                String who = spawn.viaNpc() != 0 ? "NPC " + label(EntityKind.NPC, spawn.viaNpc()) : spawn.script();
                sites.add(maps.isEmpty() ? who : who + " at " + label(EntityKind.MAP, maps.get(0)));
            }
        }
        return String.join(", ", sites);
    }
}
