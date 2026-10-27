package server.living.habits;

import client.Character;
import client.SkillFactory;
import client.inventory.InventoryType;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.StatEffect;
import server.agents.capabilities.expedition.boss.AgentBossField;
import server.agents.capabilities.looting.AgentLootPolicy;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.population.Population;
import server.living.roster.CompanionSettings;
import server.living.store.LivingStore;
import server.maps.MapItem;
import server.maps.MapleMap;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Companions pick up their owner's habits (docs/BOT_CONCEPT_PLAN.md F2.2-F2.4, living_world.OWNER_HABITS_ENABLED).
 *
 * <p>Watching. Only a human who owns companions is watched, and only while online: their habits are read from
 * living_owner_habit at login (on companion-ops) and the engine hooks (LivingWorldHooks) feed them from memory, cheaply:
 * a potion drunk (HP% and MP% at the drink), a drop picked up, an item dropped, a drop left near them until it expired
 * while their bag had room, an item sold to a shop, a skill used and the damage it landed, a training stint on a field
 * (from entering it to leaving: EXP, kills, deaths). Drinks, drops and stints inside boss content (an event instance, a
 * field boss on the map) are not watched: what is learned there is never used there either.</p>
 *
 * <p>Learning. Once a minute (riding the contracts timer) each watched owner's drinks past the death window are kept,
 * what is learned is read again ({@link Learned}, the seams' memory) and a changed document is written behind; at logout
 * it is written and dropped. What is learned passes the outcome gates of {@link OwnerHabits}: only the best of what is
 * observed.</p>
 *
 * <p>Using (each through the seam it changes). A companion whose owner never set its potion percentages drinks at the
 * owner's median HP point (never below OWNER_HABIT_HP_FLOOR) and MP point, except in boss content
 * (CompanionSettingsApplier); LIKE_OWNER loot skips the categories the owner always leaves, never a quest item or what
 * their quests need (AgentLootPolicy); a bench slice vendors what the owner sells at its resupply instead of hauling it
 * (BenchSessions); "Your call" weighs the owner's best maps for its level band (BenchDutyChooser); a companion of the
 * owner's job practices the skills that land the owner's damage a little more (AgentCombatPriorHint). Each learned
 * habit is mentioned once by a companion out with its owner (owner-habits.yaml), and the card's "Learned from you"
 * page shows them all, with "forget my habits" (decision D7).</p>
 */
public final class OwnerHabitBook {
    private static final Logger log = LoggerFactory.getLogger(OwnerHabitBook.class);
    /** How close to a drop that expired the owner must stand for it to count as left. */
    static final int LEFT_NEAR_PX = 400;

    /** What is learned of one owner, read again each minute: memory for the seams. */
    public record Learned(Integer hpPercent, Integer mpPercent, Set<Integer> skippedCategories, Set<Integer> soldItems,
                          Map<Integer, Set<Integer>> favoriteSkills) {
        static final Learned NOTHING = new Learned(null, null, Set.of(), Set.of(), Map.of());
    }

    /** An owner's training stint on one field, from the moment they entered it. */
    private static final class Stint {
        final int mapId;
        final int level;
        final long exp;
        final long startedAtMs;
        volatile int kills;
        volatile int deaths;

        Stint(int mapId, int level, long exp, long startedAtMs) {
            this.mapId = mapId;
            this.level = level;
            this.exp = exp;
            this.startedAtMs = startedAtMs;
        }
    }

    private final LivingWorld world;
    private final OwnerHabitLines lines;
    private final Random random = new Random();
    /** owner id -> their habits, while online and read. */
    private final Map<Integer, OwnerHabits> watched = new ConcurrentHashMap<>();
    /** owner id -> what is learned of them (read at load, each tick and at forget). */
    private final Map<Integer, Learned> learned = new ConcurrentHashMap<>();
    /** owner id -> their open training stint. */
    private final Map<Integer, Stint> stints = new ConcurrentHashMap<>();
    /** owner ids whose read is on its way. */
    private final Set<Integer> loading = ConcurrentHashMap.newKeySet();
    /** True for an item the stock data flags a quest item (a test gives its own). */
    java.util.function.IntPredicate questItem = itemId -> ItemInformationProvider.getInstance().isQuestItem(itemId);
    /** What an NPC shop pays for one of an item (a test gives its own). */
    java.util.function.IntUnaryOperator unitPrice = itemId -> ItemInformationProvider.getInstance().getPrice(itemId, 1);

    public OwnerHabitBook(LivingWorld world) {
        this(world, OwnerHabitLines.load(Path.of(world.config().CONTENT_DIR)));
    }

    OwnerHabitBook(LivingWorld world, OwnerHabitLines lines) {
        this.world = world;
        this.lines = lines;
    }

    private boolean on() {
        return world.config().OWNER_HABITS_ENABLED;
    }

    private OwnerHabits.Rules rules() {
        return OwnerHabits.Rules.of(world.config());
    }

    /** The habits of {@code chr} when it is a watched human; an owner not read yet is read (this observation is lost). */
    private OwnerHabits of(Character chr) {
        if (chr == null || !on() || AgentPresence.isAgent(chr) || !world.roster().isOwner(chr.getId())) {
            return null;
        }
        OwnerHabits habits = watched.get(chr.getId());
        if (habits == null) {
            load(chr.getId());
        }
        return habits;
    }

    /** The habits kept of {@code ownerId} while watched, else null (the tests' view). */
    OwnerHabits watched(int ownerId) {
        return watched.get(ownerId);
    }

    /** True while {@code chr} is a watched human (the attack hook sums its damage only then). */
    public boolean watches(Character chr) {
        return of(chr) != null;
    }

    // ---- lifecycle (companion-ops) ---------------------------------------------------------------

    /** Reads an owner's habits on companion-ops, once; a failed read is tried again at their next observation. */
    void load(int ownerId) {
        if (!loading.add(ownerId)) {
            return;
        }
        world.executor().submit("owner_habits.load " + ownerId, () -> {
            try {
                OwnerHabits habits = OwnerHabits.parse(world.store().ownerHabits(ownerId));
                watched.putIfAbsent(ownerId, habits);
                learned.put(ownerId, learn(watched.get(ownerId)));
            } catch (LivingStore.ReadFailed unreadable) {
                // logged by the store; read again at the owner's next observation
            } finally {
                loading.remove(ownerId);
            }
        });
    }

    /** A human logged in: an owner's habits are read. */
    public void loggedIn(Character chr) {
        if (chr != null && on() && !AgentPresence.isAgent(chr) && world.roster().isOwner(chr.getId())) {
            load(chr.getId());
        }
    }

    /** A human logged off: their stint is booked, their drinks kept, their habits written (on companion-ops) and dropped. */
    public void loggedOut(Character chr) {
        OwnerHabits habits = chr == null ? null : watched.remove(chr.getId());
        if (habits == null) {
            return;
        }
        closeStint(chr, habits, System.currentTimeMillis());
        habits.keepAllDrinks();
        learned.remove(chr.getId());
        int ownerId = chr.getId();
        world.executor().submit("owner_habits.save " + ownerId, () -> save(ownerId, habits));
    }

    /** A character was deleted: its habits go with it. */
    public void deleted(int characterId) {
        watched.remove(characterId);
        learned.remove(characterId);
        stints.remove(characterId);
        world.executor().submit("owner_habits.delete " + characterId, () -> world.store().deleteOwnerHabits(characterId));
    }

    /** Once a minute on companion-ops: drinks past the death window are kept, learned values read, lines said, writes. */
    public void tick() {
        if (!on()) {
            return;
        }
        long now = System.currentTimeMillis();
        int window = world.config().OWNER_HABIT_DEATH_WINDOW_MS;
        for (Map.Entry<Integer, OwnerHabits> entry : watched.entrySet()) {
            OwnerHabits habits = entry.getValue();
            habits.keepDrinks(now, window);
            learned.put(entry.getKey(), learn(habits));
            try {
                mention(entry.getKey(), habits);
            } catch (RuntimeException failure) {
                log.warn("owner habit line for {} failed", entry.getKey(), failure);
            }
            save(entry.getKey(), habits);
        }
    }

    private void save(int ownerId, OwnerHabits habits) {
        if (habits.dirty() && world.store().saveOwnerHabits(ownerId, habits.toJson())) {
            habits.clean();
        }
    }

    /** "Forget my habits" (D7), on companion-ops: everything noticed of the owner is gone, in memory and stored. */
    public boolean forget(int ownerId) {
        OwnerHabits habits = watched.get(ownerId);
        if (habits != null) {
            habits.forget();
            habits.clean();
        }
        stints.remove(ownerId);
        learned.put(ownerId, Learned.NOTHING);
        return world.store().deleteOwnerHabits(ownerId);
    }

    Learned learn(OwnerHabits habits) {
        OwnerHabits.Rules rules = rules();
        Map<Integer, Set<Integer>> favorites = new java.util.HashMap<>();
        for (int jobId : habits.jobs()) {
            List<Integer> skills = habits.favoriteSkills(jobId, rules);
            if (!skills.isEmpty()) {
                favorites.put(jobId, Set.copyOf(skills));
            }
        }
        return new Learned(habits.hpPoint(rules), habits.mpPoint(rules),
                Set.copyOf(habits.skippedCategories(rules, lines::neverSkips)), Set.copyOf(habits.soldItems(rules)),
                Map.copyOf(favorites));
    }

    /** What is learned of {@code ownerId} (memory only; nothing while off or not online). */
    public Learned learned(int ownerId) {
        Learned known = on() ? learned.get(ownerId) : null;
        return known == null ? Learned.NOTHING : known;
    }

    // ---- the engine hooks (any thread, memory only) ----------------------------------------------

    /** {@code chr} drinks {@code itemId}: its HP% and MP% now, before the potion works, for the stats it restores. */
    public void potionUsed(Character chr, int itemId) {
        OwnerHabits habits = of(chr);
        if (habits == null || bossContent(chr)) {
            return;
        }
        StatEffect effect = ItemInformationProvider.getInstance().getItemEffect(itemId);
        if (effect == null) {
            return;
        }
        boolean hp = effect.getHp() > 0 || effect.getHpRate() > 0.0d;
        boolean mp = effect.getMp() > 0 || effect.getMpRate() > 0.0d;
        int maxHp = Math.max(1, chr.getCurrentMaxHp());
        int maxMp = Math.max(1, chr.getCurrentMaxMp());
        habits.drank(System.currentTimeMillis(), hp ? chr.getHp() * 100 / maxHp : -1, mp ? chr.getMp() * 100 / maxMp : -1,
                world.config().OWNER_HABIT_DEATH_WINDOW_MS);
    }

    /** {@code chr} picked up {@code itemId} off the map. */
    public void looted(Character chr, int itemId) {
        OwnerHabits habits = itemId <= 0 ? null : of(chr);
        if (habits != null && !bossContent(chr)) {
            habits.lootSeen(itemId, true, price(itemId));
        }
    }

    /** {@code chr} dropped {@code itemId} from its bag: what it picked up of that kind counts as left after all. */
    public void dropped(Character chr, int itemId) {
        OwnerHabits habits = itemId <= 0 ? null : of(chr);
        if (habits != null) {
            habits.dropped(itemId, price(itemId));
        }
    }

    /**
     * Monster drops that expired on {@code map} untouched: each counts as left by every watched owner standing within
     * LEFT_NEAR_PX of it, alive, with room in the bag it would have gone to (a full bag is no choice).
     */
    public void dropsExpired(MapleMap map, Collection<MapItem> drops) {
        if (!on() || map == null || drops.isEmpty() || map.getEventInstance() != null || AgentBossField.near(map)) {
            return;
        }
        List<Character> owners = new ArrayList<>();
        for (Character chr : map.getAllPlayers()) {
            if (chr.isAlive() && of(chr) != null) {
                owners.add(chr);
            }
        }
        if (owners.isEmpty()) {
            return;
        }
        for (MapItem drop : drops) {
            int itemId = drop.getItemId();
            if (drop.getMeso() > 0 || itemId <= 0 || drop.isPlayerDrop() || drop.isPickedUp()) {
                continue;
            }
            InventoryType type = ItemConstants.getInventoryType(itemId);
            for (Character owner : owners) {
                if (owner.getPosition().distance(drop.getPosition()) <= LEFT_NEAR_PX && type != InventoryType.UNDEFINED
                        && !owner.getInventory(type).isFull()) {
                    OwnerHabits habits = watched.get(owner.getId());
                    if (habits != null) {
                        habits.lootSeen(itemId, false, price(itemId));
                    }
                }
            }
        }
    }

    /** {@code chr} sold {@code itemId} to a shop. */
    public void sold(Character chr, int itemId) {
        OwnerHabits habits = of(chr);
        if (habits != null) {
            habits.sold(itemId, System.currentTimeMillis());
        }
    }

    /** {@code chr} used {@code skillId}, landing {@code damage} (0 for a buff). */
    public void skillUsed(Character chr, int skillId, long damage) {
        OwnerHabits habits = skillId <= 0 ? null : of(chr);
        if (habits != null && chr.getJob() != null) {
            habits.skillUsed(chr.getJob().getId(), skillId, damage);
        }
    }

    /** {@code chr} is moving into {@code map}: the stint on the field it leaves is booked, one on a new field begins. */
    public void mapEntered(Character chr, MapleMap map) {
        OwnerHabits habits = of(chr);
        if (habits == null) {
            return;
        }
        long now = System.currentTimeMillis();
        closeStint(chr, habits, now);
        if (map != null && !map.isTown() && map.getEventInstance() == null && !AgentBossField.near(map)) {
            stints.put(chr.getId(), new Stint(map.getId(), chr.getLevel(), chr.getExp(), now));
        }
    }

    /** Monsters killed: a kill for each attacker on an open stint. */
    public void killed(Set<Integer> attackerIds) {
        if (stints.isEmpty()) {
            return;
        }
        for (int attackerId : attackerIds) {
            Stint stint = stints.get(attackerId);
            if (stint != null) {
                stint.kills++;
            }
        }
    }

    /** {@code chr} died: its drinks in the window before it were too late; its stint counts the death. */
    public void died(Character chr) {
        OwnerHabits habits = of(chr);
        if (habits == null) {
            return;
        }
        habits.died();
        Stint stint = stints.get(chr.getId());
        if (stint != null) {
            stint.deaths++;
        }
    }

    private void closeStint(Character chr, OwnerHabits habits, long now) {
        Stint stint = stints.remove(chr.getId());
        if (stint == null) {
            return;
        }
        long exp = server.living.roster.BenchDutyDispatcher.expGained(stint.level, stint.exp, chr.getLevel(), chr.getExp());
        habits.trained(stint.level, stint.mapId, now - stint.startedAtMs, exp, stint.kills, stint.deaths);
    }

    /** Boss content: an event instance (a party quest, an expedition, a boss run) or a map a field boss stands on. */
    public static boolean bossContent(Character chr) {
        return chr == null || chr.getEventInstance() != null || AgentBossField.near(chr);
    }

    private int price(int itemId) {
        try {
            return Math.max(0, unitPrice.applyAsInt(itemId));
        } catch (RuntimeException unknown) {
            return 0;
        }
    }

    // ---- reads for the seams (any thread, memory only) -------------------------------------------

    /**
     * The HP and MP fractions a companion of {@code ownerId} drinks below when its owner never set them, or null when
     * nothing is learned: the owner's median HP point (never below OWNER_HABIT_HP_FLOOR) and MP point, each within the
     * percentages a companion may be set to; a point not learned yet stays the configured one.
     */
    public float[] potionThresholds(int ownerId, float configuredHp, float configuredMp) {
        Learned known = learned(ownerId);
        if (known.hpPercent() == null && known.mpPercent() == null) {
            return null;
        }
        float hp = known.hpPercent() == null ? configuredHp
                : (float) Math.max(world.config().OWNER_HABIT_HP_FLOOR, clamp(known.hpPercent()) / 100.0d);
        float mp = known.mpPercent() == null ? configuredMp : clamp(known.mpPercent()) / 100f;
        return new float[]{hp, mp};
    }

    private static int clamp(int percent) {
        return Math.max(CompanionSettings.MIN_POTION_PERCENT, Math.min(CompanionSettings.MAX_POTION_PERCENT, percent));
    }

    /**
     * True when a companion of {@code ownerId} leaves {@code itemId} like its owner: a category they always leave, and
     * never a quest item (the stock quest flag; what the owner's started quests need is the loot seam's own check).
     */
    public boolean skips(int ownerId, int itemId) {
        Learned known = learned(ownerId);
        return itemId > 0 && known.skippedCategories().contains(OwnerHabits.category(itemId)) && !questItem.test(itemId);
    }

    /** True when the owner sells {@code itemId} at shops: a companion vendors it instead of hauling it. */
    public boolean sells(int ownerId, int itemId) {
        return itemId > 0 && learned(ownerId).soldItems().contains(itemId) && !questItem.test(itemId) && price(itemId) > 0;
    }

    /** True when {@code skillId} lands much of the owner's damage as {@code jobId} (the companion's own job). */
    public boolean favorite(int ownerId, int jobId, int skillId) {
        Set<Integer> skills = learned(ownerId).favoriteSkills().get(jobId);
        return skills != null && skills.contains(skillId);
    }

    /** The owner's best training maps for {@code level}'s band (at most two, the best first); none while not watched. */
    public List<Integer> trainingMaps(int ownerId, int level) {
        OwnerHabits habits = on() ? watched.get(ownerId) : null;
        return habits == null ? List.of() : habits.trainingMaps(level, rules());
    }

    // ---- lines and pages -------------------------------------------------------------------------

    /**
     * One habit a companion out with the owner has not mentioned yet, said now (at most one a minute per owner): the
     * potion point when its potions follow the habit, a skipped category when it loots LIKE_OWNER, a sold item, the
     * owner's best map at its level, a skill of its job that lands the owner's damage.
     */
    private void mention(int ownerId, OwnerHabits habits) {
        Character owner = world.roster().onlineCharacter(ownerId);
        Character bot = owner == null ? null : companionWith(owner);
        String personaId = bot == null ? null : world.directory().personaIdOfCharacter(bot.getId());
        if (personaId == null) {
            return;
        }
        Learned known = learned(ownerId);
        CompanionSettings own = world.roster().settings().settings(ownerId, personaId);
        List<String[]> due = new ArrayList<>(); // {habit key, line kind, placeholder, value}
        if (known.hpPercent() != null && own.potionsAsConfigured()) {
            float[] points = potionThresholds(ownerId, 0f, 0f);
            due.add(new String[]{"potion", "potion", "hp", String.valueOf(Math.round(points[0] * 100))});
        }
        if (own.loot() == AgentLootPolicy.Mode.LIKE_OWNER) {
            for (int category : known.skippedCategories()) {
                due.add(new String[]{"loot:" + category, "loot", "what", categoryName(habits, category)});
            }
        }
        for (int itemId : known.soldItems()) {
            due.add(new String[]{"sell:" + itemId, "sell", "item", itemName(itemId)});
        }
        List<Integer> maps = habits.trainingMaps(bot.getLevel(), rules());
        if (!maps.isEmpty()) {
            due.add(new String[]{"camp:" + bot.getLevel() / 10, "camp", "map", placeName(maps.getFirst())});
        }
        if (bot.getJob() != null) {
            for (int skillId : known.favoriteSkills().getOrDefault(bot.getJob().getId(), Set.of())) {
                due.add(new String[]{"skill:" + skillId, "skill", "skill", skillName(skillId)});
            }
        }
        for (String[] habit : due) {
            if (habits.said(habit[0])) {
                continue;
            }
            String line = lines.line(habit[1], Map.of(habit[2], habit[3]), random);
            if (line != null && habits.say(habit[0])) {
                if (!world.roster().sayInParty(bot, line)) {
                    AgentDialogueTransportRuntime.sayMapNow(bot, line);
                }
                log.info("companion {} mentions habit {} of {}: {}", bot.getName(), habit[0], ownerId, line);
                return;
            }
        }
    }

    /** One of the owner's companions out with them on their map, or null. */
    private Character companionWith(Character owner) {
        for (AgentRuntimeEntry entry : AgentRuntimeRegistry.entriesForLeader(owner.getId())) {
            Character bot = AgentRuntimeIdentityRuntime.bot(entry);
            if (bot != null && bot.getMap() == owner.getMap() && world.directory().ownerOfCharacter(bot.getId()) == owner.getId()) {
                return bot;
            }
        }
        return null;
    }

    /**
     * The "Learned from you" lines of {@code ownerId}, as the companion {@code own} uses them (its settings): empty when
     * nothing is learned (or the owner is not online).
     */
    public List<String> describe(int ownerId, CompanionSettings own, int companionLevel, int companionJobId) {
        List<String> out = new ArrayList<>();
        OwnerHabits habits = on() ? watched.get(ownerId) : null;
        if (habits == null) {
            return out;
        }
        Learned known = learned(ownerId);
        if (known.hpPercent() != null) {
            float[] points = potionThresholds(ownerId, 0f, 0f);
            out.add(own != null && !own.potionsAsConfigured()
                    ? "you drink at about " + known.hpPercent() + "% hp. you set my potions yourself, so i keep to those."
                    : "you drink at about " + known.hpPercent() + "% hp, so i drink below " + Math.round(points[0] * 100)
                    + "% (not in boss fights).");
        }
        if (!known.skippedCategories().isEmpty()) {
            List<String> names = new ArrayList<>();
            for (int category : known.skippedCategories()) {
                names.add(categoryName(habits, category));
            }
            boolean like = own == null || own.loot() == AgentLootPolicy.Mode.LIKE_OWNER;
            out.add("you leave " + join(names) + (like ? ", so i leave them too." : ". (i pick up what you told me to.)"));
        }
        if (!known.soldItems().isEmpty()) {
            List<String> names = new ArrayList<>();
            for (int itemId : known.soldItems()) {
                if (names.size() < 3) {
                    names.add(itemName(itemId));
                }
            }
            int more = known.soldItems().size() - names.size();
            out.add("you sell " + join(names) + (more > 0 ? " and " + more + " more" : "")
                    + " at the store, so i sell mine when i restock on my own.");
        }
        List<Integer> maps = habits.trainingMaps(companionLevel, rules());
        if (!maps.isEmpty()) {
            List<String> names = maps.stream().map(this::placeName).toList();
            out.add("at my level you train best at " + join(names) + ", so i camp there when it's my call.");
        }
        Set<Integer> skills = known.favoriteSkills().getOrDefault(companionJobId, Set.of());
        if (!skills.isEmpty()) {
            out.add("you lean on " + join(skills.stream().map(OwnerHabitBook::skillName).toList())
                    + ", so i practice " + (skills.size() == 1 ? "it" : "them") + " more.");
        }
        return out;
    }

    /** The GM's view (!habits): what is learned of {@code ownerId}, and what it rests on; null while not watched. */
    public List<String> report(int ownerId) {
        OwnerHabits habits = watched.get(ownerId);
        if (habits == null) {
            return null;
        }
        Learned known = learn(habits);
        List<String> out = new ArrayList<>();
        out.add("seen: " + habits.counts());
        out.add("potion point: " + (known.hpPercent() == null ? "-" : known.hpPercent() + "% HP") + " / "
                + (known.mpPercent() == null ? "-" : known.mpPercent() + "% MP"));
        List<String> skipped = new ArrayList<>();
        for (int category : known.skippedCategories()) {
            skipped.add(category + " (" + categoryName(habits, category) + ")");
        }
        out.add("skips: " + (skipped.isEmpty() ? "-" : String.join(", ", skipped)));
        out.add("sells: " + (known.soldItems().isEmpty() ? "-"
                : String.join(", ", known.soldItems().stream().map(this::itemName).toList())));
        for (int band : habits.bands()) {
            List<Integer> maps = habits.trainingMaps(band * 10, rules());
            if (!maps.isEmpty()) {
                out.add("Lv" + band * 10 + "-" + (band * 10 + 9) + " maps: "
                        + String.join(", ", maps.stream().map(this::placeName).toList()));
            }
        }
        known.favoriteSkills().forEach((jobId, skills) -> out.add("job " + jobId + " skills: "
                + String.join(", ", skills.stream().map(OwnerHabitBook::skillName).toList())));
        return out;
    }

    private String categoryName(OwnerHabits habits, int category) {
        String name = lines.category(category);
        if (name != null) {
            return name;
        }
        int example = habits.exampleLeft(category);
        return example > 0 ? "stuff like " + itemName(example) : "that stuff";
    }

    private String itemName(int itemId) {
        String name = ItemInformationProvider.getInstance().getName(itemId);
        return name == null ? "item " + itemId : name;
    }

    private String placeName(int mapId) {
        Population population = Population.get();
        return population == null ? "map " + mapId : population.fields().name(mapId);
    }

    private static String skillName(int skillId) {
        String name = SkillFactory.getSkillName(skillId);
        return name == null ? "skill " + skillId : name;
    }

    private static String join(List<String> names) {
        if (names.size() <= 1) {
            return names.isEmpty() ? "" : names.getFirst();
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
    }
}
