package client.command.commands.gm1;

import client.Character;
import server.knowledge.DropSearch;
import server.knowledge.DropSource;
import server.knowledge.ItemFact;
import server.knowledge.MapFact;
import server.knowledge.MobFact;
import server.knowledge.WorldKnowledge;
import server.living.LivingWorld;
import server.living.resident.LivingTalk;
import server.living.resident.LivingTalk.Option;
import server.mods.BotStoryWzEquips;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The drop browser behind {@code @whodrops} and {@code @mobdrops}: Wiz the Librarian's window lists the matches, an item
 * lists the monsters that drop it and a monster its drop table, every row a link to the other side, with Back to the
 * screen before. Chances are the asking player's (their drop and boss drop rates, drop cards). Built on
 * {@link LivingTalk}, so it keeps the client's one-window rules; the data is {@link DropSearch} over the knowledge
 * snapshot (live drop_data included).
 */
final class DropBrowser {
    /** Wiz the Librarian (Ludibrium library): the window's portrait. */
    static final int NPC = 2040052;

    private static final int SPAWN_MAPS_SHOWN = 3;

    private DropBrowser() {
    }

    /** The browser can open: the living world answers its windows and the knowledge snapshot is loaded. */
    static boolean available() {
        return LivingWorld.get() != null && WorldKnowledge.current().ready();
    }

    /** {@code @whodrops <name>}: the matching items, or the one item's droppers. */
    static void searchItems(Character chr, String query) {
        if (!mayOpen(chr)) {
            return;
        }
        DropSearch.Hits hits = DropSearch.items(WorldKnowledge.current(), query);
        if (hits.total() == 0) {
            chr.dropMessage(5, "No monster drops an item called \"" + query.trim() + "\".");
        } else if (hits.total() == 1) {
            item(chr, hits.ids().get(0), null);
        } else {
            itemResults(chr, query.trim(), hits);
        }
    }

    /** {@code @mobdrops <name>}: the matching monsters, or the one monster's drop table. */
    static void searchMobs(Character chr, String query) {
        if (!mayOpen(chr)) {
            return;
        }
        DropSearch.Hits hits = DropSearch.mobs(WorldKnowledge.current(), query);
        if (hits.total() == 0) {
            chr.dropMessage(5, "No monster is called \"" + query.trim() + "\".");
        } else if (hits.total() == 1) {
            mob(chr, hits.ids().get(0), null);
        } else {
            mobResults(chr, "Monsters matching \"" + DropSearch.plain(query.trim()) + "\"", hits);
        }
    }

    /** {@code @mobdrops} with no name: the monsters that spawn on the player's map. */
    static void mapMobs(Character chr, List<Integer> mobIds) {
        if (!mayOpen(chr)) {
            return;
        }
        if (mobIds.isEmpty()) {
            chr.dropMessage(5, "No monsters spawn here. Use @mobdrops <monster name>.");
        } else if (mobIds.size() == 1) {
            mob(chr, mobIds.get(0), null);
        } else {
            mobResults(chr, "Monsters on this map", new DropSearch.Hits(List.copyOf(mobIds), mobIds.size()));
        }
    }

    // ---- screens -------------------------------------------------------------------------

    private static void itemResults(Character chr, String query, DropSearch.Hits hits) {
        WorldKnowledge knowledge = WorldKnowledge.current();
        List<Option> rows = new ArrayList<>();
        for (int itemId : hits.ids()) {
            String name = knowledge.item(itemId).map(ItemFact::name).orElse("Item " + itemId);
            int droppers = knowledge.dropSources(itemId).size();
            rows.add(new Option(DropSearch.itemLabel(itemId, name, drawable(itemId)) + " - " + droppers
                    + (droppers == 1 ? " source" : " sources"),
                    () -> item(chr, itemId, () -> itemResults(chr, query, hits))));
        }
        LivingTalk.open(chr, NPC).menu("#eItems matching \"" + DropSearch.plain(query) + "\"#n" + shown(hits), rows).show();
    }

    private static void mobResults(Character chr, String title, DropSearch.Hits hits) {
        WorldKnowledge knowledge = WorldKnowledge.current();
        List<Option> rows = new ArrayList<>();
        for (int mobId : hits.ids()) {
            MobFact mob = knowledge.mob(mobId).orElse(null);
            String label = mob == null ? "Monster " + mobId
                    : "#b" + DropSearch.plain(mob.name()) + "#k (Lv " + mob.level() + (mob.boss() ? ", boss" : "")
                    + (knowledge.wild(mobId) ? "" : ", event") + ")"; // event and PQ copies share the open mob's name
            rows.add(new Option(label, () -> mob(chr, mobId, () -> mobResults(chr, title, hits))));
        }
        LivingTalk.open(chr, NPC).menu("#e" + title + "#n" + shown(hits), rows).show();
    }

    /** Who drops the item: each monster (a link to its table) at the player's chance, then the global drops. */
    private static void item(Character chr, int itemId, Runnable back) {
        WorldKnowledge knowledge = WorldKnowledge.current();
        String name = knowledge.item(itemId).map(ItemFact::name).orElse("Item " + itemId);
        List<Option> rows = new ArrayList<>();
        List<String> globals = new ArrayList<>();
        for (DropSource source : knowledge.dropSources(itemId)) {
            String odds = DropSearch.odds(DropSearch.chance(source, chr.getDropRate(), chr.getBossDropRate(),
                    chr.getCardRate(itemId)));
            if (source.global()) {
                globals.add("Any monster" + continent(source.continent()) + ": " + odds);
                continue;
            }
            MobFact mob = knowledge.mob(source.mobId()).orElse(null);
            String mobName = mob == null ? "Monster " + source.mobId() : DropSearch.plain(mob.name()) + " (Lv " + mob.level() + ")";
            rows.add(new Option("#b" + mobName + "#k " + odds + (source.questId() > 0 ? " #r(quest)#k" : ""),
                    () -> mob(chr, source.mobId(), () -> item(chr, itemId, back))));
        }
        for (String global : globals) {
            rows.add(Option.heading(global));
        }
        String text = "#e" + DropSearch.itemLabel(itemId, name, drawable(itemId)) + "#n\r\n" + rates(chr)
                + (rows.isEmpty() ? "\r\nNo monster drops it." : "");
        LivingTalk.open(chr, NPC).menu(text, rows).back(back).show();
    }

    /** A monster's drop table: where it lives, its mesos, then its drops by section (each a link to who else drops it). */
    private static void mob(Character chr, int mobId, Runnable back) {
        WorldKnowledge knowledge = WorldKnowledge.current();
        MobFact mob = knowledge.mob(mobId).orElse(null);
        StringBuilder text = new StringBuilder("#e");
        if (mob == null) {
            text.append("Monster ").append(mobId).append("#n\r\n");
        } else {
            text.append(DropSearch.plain(mob.name())).append("#n (Lv ").append(mob.level())
                    .append(mob.boss() ? ", boss" : "").append(")\r\nHP ").append(String.format(Locale.ROOT, "%,d", mob.maxHp()))
                    .append(", EXP ").append(String.format(Locale.ROOT, "%,d", (long) mob.exp() * chr.getExpRate()))
                    .append("\r\n");
        }
        text.append(seenAt(knowledge, mobId)).append(rates(chr));

        Map<DropSearch.Section, List<Option>> sections = new EnumMap<>(DropSearch.Section.class);
        for (DropSource source : knowledge.mobDrops(mobId)) {
            double chance = DropSearch.chance(source, chr.getDropRate(), chr.getBossDropRate(),
                    chr.getCardRate(source.itemId()));
            if (source.itemId() == 0) {
                text.append("\r\nMesos ").append(String.format(Locale.ROOT, "%,d-%,d", (long) source.minQuantity() * chr.getMesoRate(),
                        (long) source.maxQuantity() * chr.getMesoRate())).append(" at ").append(DropSearch.odds(chance));
                continue;
            }
            int itemId = source.itemId();
            String name = knowledge.item(itemId).map(ItemFact::name).orElse("Item " + itemId);
            sections.computeIfAbsent(DropSearch.section(source), section -> new ArrayList<>())
                    .add(new Option(DropSearch.itemLabel(itemId, name, drawable(itemId)) + " " + DropSearch.odds(chance),
                            () -> item(chr, itemId, () -> mob(chr, mobId, back))));
        }
        List<Option> rows = new ArrayList<>();
        sections.forEach((section, options) -> {
            rows.add(Option.heading("#e" + section.title() + "#n"));
            rows.addAll(options);
        });
        if (rows.isEmpty()) {
            text.append("\r\nIt drops nothing.");
        }
        LivingTalk.open(chr, NPC).menu(text.toString(), rows).back(back).show();
    }

    // ---- helpers -------------------------------------------------------------------------

    /** A window may open now; otherwise the player hears why (an open dialog or script window). */
    private static boolean mayOpen(Character chr) {
        if (LivingTalk.mayShow(chr)) {
            return true;
        }
        chr.dropMessage(5, "Close the open dialog first, then try again.");
        return false;
    }

    /**
     * The client can draw the item's icon and tooltip: not an equip only BotStory's WZ files have while botstory_wz is
     * off, and not a weapon id the client has no weapon type for (post-Big Bang weapon prefixes close the client on
     * {@code #z}). Cash weapons (170) are drawn; players carry them.
     */
    static boolean drawable(int itemId) {
        if (itemId / 1_000_000 != 1) {
            return true;
        }
        if (BotStoryWzEquips.withheld(itemId)) {
            return false;
        }
        int prefix = itemId / 10_000;
        if (prefix >= 121 && prefix <= 169) {
            // the categories ItemInformationProvider.getWeaponType gives a type: 30-49 except 34, 35, 36 and 39
            int category = prefix % 100;
            return category >= 30 && category <= 49 && category != 34 && category != 35 && category != 36
                    && category != 39;
        }
        return true;
    }

    private static String shown(DropSearch.Hits hits) {
        return hits.total() > hits.ids().size()
                ? "\r\nThe first " + hits.ids().size() + " of " + hits.total() + ": narrow your search."
                : "";
    }

    private static String rates(Character chr) {
        return "At your rates (drop x" + chr.getDropRate() + ", boss drop x" + chr.getBossDropRate() + ")";
    }

    /** "Seen at: <three maps> (and N more)", or that it only comes from events, party quests or scripts. */
    private static String seenAt(WorldKnowledge knowledge, int mobId) {
        List<Integer> maps = knowledge.spawnMaps(mobId);
        if (maps.isEmpty()) {
            return "Only appears in events, party quests or scripts.\r\n";
        }
        StringBuilder out = new StringBuilder("Seen at: ");
        for (int i = 0; i < maps.size() && i < SPAWN_MAPS_SHOWN; i++) {
            out.append(i > 0 ? ", " : "").append(DropSearch.plain(knowledge.map(maps.get(i)).map(MapFact::name)
                    .orElse("map " + maps.get(i))));
        }
        if (maps.size() > SPAWN_MAPS_SHOWN) {
            out.append(" (and ").append(maps.size() - SPAWN_MAPS_SHOWN).append(" more)");
        }
        return out.append("\r\n").toString();
    }

    private static String continent(int continent) {
        return switch (continent) {
            case -1 -> "";
            case 0 -> " on Maple Island";
            case 1 -> " on Victoria Island";
            case 2 -> " in Ossyria";
            case 5 -> " in the World Tour";
            case 6 -> " in Masteria";
            case 8 -> " in Zipangu";
            default -> " on continent " + continent;
        };
    }
}
