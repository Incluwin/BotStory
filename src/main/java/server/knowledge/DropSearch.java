package server.knowledge;

import constants.id.ItemId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The data side of the drop browser ({@code @whodrops} / {@code @mobdrops}): name search over the knowledge
 * snapshot, the chance a player rolls, and NPC-window text that cannot break the client's markup. The windows
 * themselves are {@code client.command.commands.gm1.DropBrowser}.
 *
 * <p>From the community "Drop Search" design (2026-10-25 intake): rank exact, then prefix, then substring; chances
 * at the asking player's own rates, as {@code MapleMap.dropItemsFromMonsterOnMap} rolls them; a '#' in a WZ name never
 * reaches the window ("Box of Parts #1" would corrupt the rest of its line).</p>
 */
public final class DropSearch {
    /** Search results listed at most (ten menu windows); the header says how many matched in all. */
    public static final int MAX_RESULTS = 100;

    private static final double CHANCE_SCALE = 1_000_000d;

    private DropSearch() {
    }

    /** The first {@link #MAX_RESULTS} matches in rank order, and how many matched in all. */
    public record Hits(List<Integer> ids, int total) {
    }

    /** The drop table's sections, in the order the mob window lists them. */
    public enum Section {
        EQUIP("Equipment"), USE("Use"), ETC("Etc"), QUEST("Quest items (while the quest is active)"), CARD("Monster cards");

        private final String title;

        Section(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    /** Named items some monster drops (a drop_data row or a global drop): exact name, then prefix, then substring; then id. */
    public static Hits items(WorldKnowledge knowledge, String query) {
        String wanted = NameIndex.normalize(query);
        List<Ranked> found = new ArrayList<>();
        if (!wanted.isEmpty()) {
            Names names = names(knowledge);
            for (int i = 0; i < names.itemIds.length; i++) {
                int rank = rank(names.itemNames[i], wanted);
                if (rank >= 0) {
                    found.add(new Ranked(names.itemIds[i], rank, 0, 0));
                }
            }
        }
        found.sort(Comparator.comparingInt(Ranked::rank).thenComparingInt(Ranked::id));
        return hits(found);
    }

    /**
     * Named monsters: exact name, then prefix, then substring; within a rank the ones players meet in the open first
     * (event and party-quest copies share their names), then by level, then id.
     */
    public static Hits mobs(WorldKnowledge knowledge, String query) {
        String wanted = NameIndex.normalize(query);
        List<Ranked> found = new ArrayList<>();
        if (!wanted.isEmpty()) {
            Names names = names(knowledge);
            for (int i = 0; i < names.mobs.length; i++) {
                MobFact mob = names.mobs[i];
                int rank = rank(names.mobNames[i], wanted);
                if (rank >= 0) {
                    found.add(new Ranked(mob.id(), rank, knowledge.wild(mob.id()) ? 0 : 1, mob.level()));
                }
            }
        }
        found.sort(Comparator.comparingInt(Ranked::rank).thenComparingInt(Ranked::open)
                .thenComparingInt(Ranked::level).thenComparingInt(Ranked::id));
        return hits(found);
    }

    /**
     * Chance per kill (0..1) as {@code MapleMap} rolls it for this player: a drop_data row at the player's drop rate
     * (the boss drop rate for a boss) times their drop-card rate; a global row flat, as the map rolls it.
     */
    public static double chance(DropSource source, int dropRate, int bossDropRate, float cardRate) {
        if (source.global()) {
            return Math.min(1d, source.chance() / CHANCE_SCALE);
        }
        double rolled = (double) source.chance() * (source.boss() ? bossDropRate : dropRate) * cardRate;
        return Math.min(1d, rolled / CHANCE_SCALE);
    }

    /** "1/2,500 (0.04%)": the precision grows as the chance shrinks, so a rare drop never reads 0.0%. */
    public static String odds(double chance) {
        if (chance <= 0) {
            return "never";
        }
        if (chance >= 1) {
            return "1/1 (100%)";
        }
        long oneIn = Math.max(1, Math.round(1 / chance));
        double percent = chance * 100;
        String digits = percent >= 10 ? "%.1f" : percent >= 1 ? "%.2f" : percent >= 0.01 ? "%.3f" : "%.4f";
        return String.format(Locale.ROOT, "1/%,d (" + digits + "%%)", oneIn, percent);
    }

    /** The section a drop is listed under in a mob's table. */
    public static Section section(DropSource source) {
        if (ItemId.isMonsterCard(source.itemId())) {
            return Section.CARD;
        }
        if (source.questId() > 0) {
            return Section.QUEST;
        }
        return switch (source.itemId() / 1_000_000) {
            case 1 -> Section.EQUIP;
            case 2 -> Section.USE;
            default -> Section.ETC;
        };
    }

    /** Text for the NPC window with every '#' removed: one would start a markup code and corrupt the rest of its line. */
    public static String plain(String text) {
        return text == null ? "" : text.replace("#", "");
    }

    /**
     * An item for an NPC-window line: its icon and its name, both hovered into the item's own tooltip, when the client
     * can draw the item ({@code #i<id>:#} arms the icon's hover, {@code #z<id>#} the name's); else the plain name.
     */
    public static String itemLabel(int itemId, String name, boolean drawable) {
        if (!drawable) {
            return plain(name);
        }
        return "#i" + itemId + ":# #b#z" + itemId + "##k";
    }

    private record Ranked(int id, int rank, int open, int level) {
    }

    /** The searchable names of one knowledge snapshot, normalized once: a search then only compares strings. */
    private record Names(WorldKnowledge source, int[] itemIds, String[] itemNames, MobFact[] mobs, String[] mobNames) {
    }

    private static volatile Names names;

    private static Names names(WorldKnowledge knowledge) {
        Names current = names;
        if (current != null && current.source() == knowledge) {
            return current;
        }
        List<ItemFact> dropped = new ArrayList<>();
        for (ItemFact item : knowledge.items()) {
            if (!item.unnamed() && !knowledge.dropSources(item.id()).isEmpty()) {
                dropped.add(item);
            }
        }
        int[] itemIds = new int[dropped.size()];
        String[] itemNames = new String[dropped.size()];
        for (int i = 0; i < itemIds.length; i++) {
            itemIds[i] = dropped.get(i).id();
            itemNames[i] = NameIndex.normalize(dropped.get(i).name());
        }
        MobFact[] mobs = knowledge.mobs().toArray(new MobFact[0]);
        String[] mobNames = new String[mobs.length];
        for (int i = 0; i < mobs.length; i++) {
            mobNames[i] = NameIndex.normalize(mobs[i].name());
        }
        Names built = new Names(knowledge, itemIds, itemNames, mobs, mobNames);
        names = built; // a new snapshot (a drop reload) builds its own; racing builders make equal ones
        return built;
    }

    /** 0 exact, 1 prefix, 2 substring, -1 no match; both normalized. */
    private static int rank(String name, String wanted) {
        if (name.isEmpty()) {
            return -1;
        }
        if (name.equals(wanted)) {
            return 0;
        }
        if (name.startsWith(wanted)) {
            return 1;
        }
        return name.contains(wanted) ? 2 : -1;
    }

    private static Hits hits(List<Ranked> found) {
        List<Integer> ids = new ArrayList<>(Math.min(found.size(), MAX_RESULTS));
        for (int i = 0; i < found.size() && i < MAX_RESULTS; i++) {
            ids.add(found.get(i).id());
        }
        return new Hits(List.copyOf(ids), found.size());
    }
}
