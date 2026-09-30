package server.living.roster;

import client.Character;
import client.Job;
import constants.game.ExpTable;
import server.ItemInformationProvider;
import server.living.LivingWorld;
import server.living.bond.BondService;
import server.living.config.Content.BondStage;
import server.living.config.Content.Persona;
import server.living.resident.ResidentSpots;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.Bond;
import server.living.store.LivingStore.HaulItem;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * One companion as a Crew window row or a companion card shows it (docs/BOT_MANAGEMENT_PLAN.md, "CompanionView").
 * Built from the live character when the companion is loaded; otherwise from its roster row plus one read of
 * its characters row (level and job). {@code where} is the map it stands on when loaded, else its camp;
 * HP and MP are known only when it is loaded. {@code travelMs} is how long a companion called from its camp still needs
 * (0 when it is not on the way).
 *
 * <p>P3: {@code expPercent} (-1 while it is not loaded), {@code camp} (its camp's name, loaded or not), the FARM_ITEM
 * progress ({@code farmed} of {@code farmGoal} {@code farmItem}), the haul ({@code haulMeso}, {@code haulStacks}), its
 * {@code wages} ("paid up for ~2 h" or "working off the haul"), {@code bond} (null when none is kept), {@code guest}
 * (a citizen guest in the owner's party, not a roster row) and {@code committed} (tied to a run: bench, swap and errands
 * wait until it ends). {@link CompanionViews} keeps what comes from the database per owner, so a menu renders with no
 * database read; {@link #of} reads it all at once, for a caller without the cache. P3b: {@code jobId} (the Job id
 * {@code job} names, -1 while unknown), by which the Fighting style page offers support.</p>
 */
public record CompanionView(String personaId,
                            String name,
                            RosterState state,
                            boolean loaded,
                            int level,
                            String job,
                            int jobId,
                            String where,
                            int hp,
                            int maxHp,
                            String duty,
                            int bankedExpPercent,
                            long travelMs,
                            int mp,
                            int maxMp,
                            int expPercent,
                            String camp,
                            int farmed,
                            int farmGoal,
                            String farmItem,
                            long haulMeso,
                            int haulStacks,
                            String wages,
                            BondStage bond,
                            boolean guest,
                            boolean committed) {
    /** Enum words that are two words in the game's job names; the rest are title-cased as they are. */
    private static final Map<String, String> JOB_WORDS = Map.ofEntries(
            Map.entry("FP", "F/P"), Map.entry("IL", "I/L"),
            Map.entry("WHITEKNIGHT", "White Knight"), Map.entry("DRAGONKNIGHT", "Dragon Knight"),
            Map.entry("DARKKNIGHT", "Dark Knight"), Map.entry("NIGHTLORD", "Night Lord"),
            Map.entry("CHIEFBANDIT", "Chief Bandit"), Map.entry("MAPLELEAF", "Maple Leaf"),
            Map.entry("GM", "GM"), Map.entry("SUPERGM", "GM"),
            Map.entry("DAWNWARRIOR", "Dawn Warrior"), Map.entry("BLAZEWIZARD", "Blaze Wizard"),
            Map.entry("WINDARCHER", "Wind Archer"), Map.entry("NIGHTWALKER", "Night Walker"),
            Map.entry("THUNDERBREAKER", "Thunder Breaker"));

    /** {@code placeNames} names a map id (the camp of a companion that is not loaded). */
    public static CompanionView of(LivingWorld world, RosterEntry entry, IntFunction<String> placeNames) {
        return live(world, Stored.read(world, entry, placeNames, CompanionView::itemName),
                world.roster().travelMsLeft(entry.characterId(), entry.personaId()));
    }

    /**
     * The view now: the live character's numbers when the companion is loaded, else {@code stored}'s (its level and job
     * are read once, the first time it is shown unloaded). Reads no roster row: {@code travelMs} is given.
     */
    static CompanionView live(LivingWorld world, Stored stored, long travelMs) {
        RosterEntry entry = stored.entry;
        Character bot = world.roster().companion(entry);
        boolean committed = committed(world, stored.characterId);
        String wages = wages(entry.wagesPaidUntilMs());
        if (bot != null) {
            int level = bot.getLevel();
            return new CompanionView(entry.personaId(), stored.name, entry.state(), true, level, jobName(bot.getJob().getId()),
                    bot.getJob().getId(), mapName(bot), bot.getHp(), bot.getCurrentMaxHp(), stored.duty,
                    bankedPercent(entry.accruedExp(), level), travelMs, bot.getMp(), bot.getCurrentMaxMp(),
                    expPercent(bot.getExp(), level), stored.camp, stored.farmed, stored.farmGoal, stored.farmItem,
                    entry.haulMeso(), stored.haulStacks, wages, stored.bond, false, committed);
        }
        int[] levelJob = stored.levelJob(world);
        return new CompanionView(entry.personaId(), stored.name, entry.state(), false, levelJob[0], jobName(levelJob[1]),
                levelJob[1], stored.camp, 0, 0, stored.duty, bankedPercent(entry.accruedExp(), levelJob[0]), travelMs, 0, 0, -1,
                stored.camp, stored.farmed, stored.farmGoal, stored.farmItem, entry.haulMeso(), stored.haulStacks, wages,
                stored.bond, false, committed);
    }

    /** A citizen guest in the owner's party (a GUEST seat charged to them): its own live numbers, marked guest. */
    static CompanionView guest(Character citizen, String key) {
        int level = citizen.getLevel();
        return new CompanionView(key, citizen.getName(), RosterState.ACTIVE, true, level, jobName(citizen.getJob().getId()),
                citizen.getJob().getId(), mapName(citizen), citizen.getHp(), citizen.getCurrentMaxHp(), "", 0, 0L, citizen.getMp(), citizen.getCurrentMaxMp(),
                expPercent(citizen.getExp(), level), "", 0, 0, "", 0L, 0, "", null, true, false);
    }

    public int hpPercent() {
        return maxHp <= 0 ? 0 : (int) Math.round(100.0d * hp / maxHp);
    }

    public int mpPercent() {
        return maxMp <= 0 ? 0 : (int) Math.round(100.0d * mp / maxMp);
    }

    /** In the owner's party: out with them, on its way to them (TRAVELLING), or a guest. */
    public boolean inParty() {
        return guest || CompanionSeatState.partyMember(state);
    }

    /** The time left on the way as a clock, "0:40" or "1:05"; empty when it is not on the way. */
    public String eta() {
        if (travelMs <= 0L) {
            return "";
        }
        long seconds = (travelMs + 999L) / 1000L;
        return seconds / 60L + ":" + String.format(Locale.ROOT, "%02d", seconds % 60L);
    }

    /** FARM_ITEM progress, "143/200 Stolen Fence" ("143 Stolen Fence" with no count asked); empty for other duties. */
    public String progress() {
        if (farmItem.isEmpty()) {
            return "";
        }
        return farmed + (farmGoal > 0 ? "/" + farmGoal : "") + " " + farmItem;
    }

    /** "3,120 meso, 4 stacks", "3,120 meso" or "4 stacks"; empty when it carries nothing (or owes upkeep). */
    public String haul() {
        String meso = haulMeso > 0 ? String.format(Locale.ROOT, "%,d meso", haulMeso) : "";
        String stacks = haulStacks > 0 ? haulStacks + (haulStacks == 1 ? " stack" : " stacks") : "";
        return meso.isEmpty() || stacks.isEmpty() ? meso + stacks : meso + ", " + stacks;
    }

    /** Banked bench EXP as a share of the next level; 100 once it would level the companion (it is never more). */
    static int bankedPercent(long exp, int level) {
        int needed = level <= 0 ? 0 : ExpTable.getExpNeededForLevel(level);
        return needed <= 0 || exp <= 0 ? 0 : (int) Math.min(100L, exp * 100L / needed);
    }

    /** EXP toward the next level, 0-99; -1 while the level is unknown. */
    static int expPercent(long exp, int level) {
        int needed = level <= 0 ? 0 : ExpTable.getExpNeededForLevel(level);
        return needed <= 0 ? -1 : (int) Math.max(0L, Math.min(99L, exp * 100L / needed));
    }

    /** "Fighter", "F/P Wizard", "Dawn Warrior", "Maple Leaf Brigadier"; empty when unknown. */
    public static String jobName(int jobId) {
        Job job = jobId < 0 ? null : Job.getById(jobId);
        if (job == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String word : job.name().replaceAll("[0-9]", "").split("_")) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(JOB_WORDS.getOrDefault(word, word.charAt(0) + word.substring(1).toLowerCase(Locale.ROOT)));
        }
        return out.toString();
    }

    /** A camp's name as the game shows it; "camp" when the map has none. */
    public static String placeName(int mapId) {
        String name = MapFactory.loadPlaceName(mapId);
        return name == null || name.isEmpty() ? "camp" : name;
    }

    /** An item's name as the game shows it; "item <id>" when it has none. */
    public static String itemName(int itemId) {
        String name = itemId == 0 ? null : ItemInformationProvider.getInstance().getName(itemId);
        return name == null ? "item " + itemId : name;
    }

    private static String mapName(Character chr) {
        String name = chr.getMap() == null ? null : chr.getMap().getMapName();
        return name == null ? "" : name;
    }

    private static String wages(long paidUntilMs) {
        long left = paidUntilMs - System.currentTimeMillis();
        return left > 0L ? "paid up for " + paidFor(left) : "working off the haul";
    }

    /** How long wages paid ahead last: "~40 min", "~2 h", "~1 day 4 h", "~7 days" (to the nearest hour past one). */
    static String paidFor(long ms) {
        if (ms < 3_600_000L) {
            return "~" + Math.max(1L, Math.round(ms / 60_000.0d)) + " min";
        }
        long hours = Math.round(ms / 3_600_000.0d);
        if (hours < 24) {
            return "~" + hours + " h";
        }
        long days = hours / 24;
        long rest = hours % 24;
        return "~" + days + (days == 1 ? " day" : " days") + (rest == 0 ? "" : " " + rest + " h");
    }

    private static boolean committed(LivingWorld world, int characterId) {
        Commitments commitments = world.commitments();
        return commitments != null && characterId != 0 && commitments.isCommitted(characterId);
    }

    /**
     * What a view needs from the database for one roster row, read at once: the persona's name, its duty and camp, the
     * FARM_ITEM progress and the haul in its bag, and its bond with the owner. Its level and job (a characters row) are
     * read the first time it is shown unloaded, then kept.
     */
    static final class Stored {
        final RosterEntry entry;
        final String name;
        final int characterId;
        final String duty;
        final String camp;
        final int farmed;
        final int farmGoal;
        final String farmItem;
        final int haulStacks;
        final BondStage bond;
        private volatile int[] levelJob;

        private Stored(RosterEntry entry, String name, int characterId, String duty, String camp, int farmed, int farmGoal,
                       String farmItem, int haulStacks, BondStage bond) {
            this.entry = entry;
            this.name = name;
            this.characterId = characterId;
            this.duty = duty;
            this.camp = camp;
            this.farmed = farmed;
            this.farmGoal = farmGoal;
            this.farmItem = farmItem;
            this.haulStacks = haulStacks;
            this.bond = bond;
        }

        static Stored read(LivingWorld world, RosterEntry entry, IntFunction<String> placeNames, IntFunction<String> itemNames) {
            Persona persona = world.content().persona(entry.personaId());
            String name = persona == null ? entry.personaId() : persona.name();
            int camp = entry.campMapId() != 0 || persona == null ? entry.campMapId() : ResidentSpots.townMapId(persona.homeTown());
            List<HaulItem> haul = world.store().haul(entry.characterId(), entry.personaId());
            boolean farming = entry.assignment() == Assignment.FARM_ITEM && entry.assignmentItem() > 0;
            int farmed = 0;
            for (HaulItem item : haul == null ? List.<HaulItem>of() : haul) {
                farmed += farming && item.itemId() == entry.assignmentItem() ? item.quantity() : 0;
            }
            BondService bonds = world.bonds();
            Bond bond = bonds == null ? null : bonds.peek(entry.characterId(), entry.personaId());
            String duty = world.roster().describeAssignment(entry); // flow 12's rest is the card's own line
            return new Stored(entry, name, world.residents().characterIdOf(entry.personaId()),
                    duty, camp == 0 ? "" : placeNames.apply(camp), farmed,
                    farming ? Math.max(0, entry.assignmentCount()) : 0, farming ? itemNames.apply(entry.assignmentItem()) : "",
                    haul == null ? 0 : haul.size(), bond == null ? null : bond.stage());
        }

        /** {level, job id} of its characters row ({0, -1} when it has none yet), read once. */
        int[] levelJob(LivingWorld world) {
            int[] known = levelJob;
            if (known == null) {
                int[] read = characterId == 0 ? null : world.store().characterLevelAndJob(characterId);
                known = read == null || read.length < 2 ? new int[] {0, -1} : read;
                levelJob = known;
            }
            return known;
        }
    }
}
