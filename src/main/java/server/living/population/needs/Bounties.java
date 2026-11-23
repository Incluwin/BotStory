package server.living.population.needs;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.life.Monster;
import server.living.population.ChatterEngine;
import server.living.population.CitizenSession;
import server.living.population.Population;
import server.living.population.records.FieldBossKills;
import server.living.population.records.FieldBosses;
import server.maps.MapleMap;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Citizens' bounties (docs/WORLD_SYSTEMS_PLAN.md N6.2), {@link NeedKind#BOUNTY} rows of the needs board paid from the
 * citizen's own purse (one NEED_FULFILLED transfer: no new meso).
 * <ul>
 * <li>A boss bounty ({@code detail "boss:<key>"}, quantity 1, {@code itemId} the boss's mob) comes from a citizen that
 * died beside a catalogued field boss, or that trains on the map one stands on. It pays its claimant when the boss
 * dies with the claimant's party at BOUNTY_SHARE_PERCENT of its damage or more.</li>
 * <li>A hunt bounty ({@code detail "hunt:<item>"}, quantity BOUNTY_HUNT_KILLS, {@code itemId} the monster) asks for
 * kills of a monster that drops what the citizen sells and is too strong for it; a kill counts only when the claimant
 * dealt the most damage to it (its loot's owner), so only the claimant's kills count.</li>
 * </ul>
 * A player claims one at a board for BOUNTY_CLAIM_MINUTES. Kills are read on the killing thread from memory and
 * counted and paid on the living executor. Behind population.BOUNTIES_ENABLED (and NEEDS_ENABLED).
 */
public final class Bounties {
    private static final Logger log = LoggerFactory.getLogger(Bounties.class);
    static final String BOSS = "boss:";
    static final String HUNT = "hunt:";
    /** A hunt target is a monster this many levels above the citizen or more (it cannot farm it)... */
    static final int TOO_STRONG_ABOVE = 6;
    /** ...and at most this many: a monster a human a little ahead of it would hunt. */
    static final int HUNT_ABOVE_MAX = 30;

    /** A monster to hunt: the item it drops that the citizen wants, the monster, its level and a map it lives on. */
    public record Hunt(int itemId, int mobId, int mobLevel, int mapId) {
    }

    /** What bounties read of the world; tests bring their own. */
    interface World {
        FieldBosses bosses();

        /** A monster that drops an item the citizen of {@code level} sells, too strong for it, or null. */
        Hunt huntFor(int level, Random random);

        /** The monster's name ("Stumpy"), or null. */
        String mobName(int mobId);

        /** Runs {@code work} on the living executor (counting and paying write rows). */
        void later(String what, Runnable work);

        /** The player online anywhere in the world, or null. */
        Character online(int characterId);
    }

    private final Population population;
    private final NeedLedger ledger;
    private final Random random;
    private World world;
    /** Mob id -> the open hunt bounties on it that someone holds: the per-kill check reads this only. */
    private volatile Map<Integer, Set<Integer>> hunts = Map.of();
    /** Boss mob id -> the open boss bounties on it that someone holds. */
    private volatile Map<Integer, Set<Integer>> bosses = Map.of();

    Bounties(Population population, NeedLedger ledger, World world) {
        this.population = population;
        this.ledger = ledger;
        this.world = world;
        this.random = new Random(population.config().SEED ^ 0xb0b7L);
    }

    public boolean on() {
        return population.config().BOUNTIES_ENABLED && ledger.on();
    }

    /** Tests: the world bounties read. */
    void world(World replacement) {
        this.world = replacement;
    }

    private World world() {
        World current = world;
        if (current == null) {
            current = new LiveWorld(population);
            world = current;
        }
        return current;
    }

    static boolean boss(Need need) {
        return need.kind() == NeedKind.BOUNTY && need.detail().startsWith(BOSS);
    }

    static boolean hunt(Need need) {
        return need.kind() == NeedKind.BOUNTY && need.detail().startsWith(HUNT);
    }

    /** The boss key of a boss bounty ("field.stumpy"), else null. */
    static String bossKey(Need need) {
        return boss(need) ? need.detail().substring(BOSS.length()) : null;
    }

    // ---- words --------------------------------------------------------------------------------

    /** The monster as a player says it: "stumpy". */
    String target(Need need) {
        String name = world().mobName(need.itemId());
        return name == null ? "monster " + need.itemId() : name.toLowerCase(java.util.Locale.ROOT);
    }

    /** "a bounty on stumpy", "12 more jr. necki". */
    String what(Need need) {
        if (boss(need)) {
            return "a bounty on " + target(need);
        }
        return need.remaining() + " more " + target(need);
    }

    /** What it is for: the boss ("stumpy"), or the item a hunt is after. */
    String forWords(Need need) {
        if (boss(need)) {
            return target(need);
        }
        return ledger.itemName(NeedLedger.parseInt(need.detail().substring(HUNT.length())));
    }

    // ---- posting --------------------------------------------------------------------------------

    /**
     * At a derivation (under the citizen's lock): a citizen with no bounty open may pin one, BOUNTY_CHANCE a time: on a
     * boss that stands where it trains now, else a hunt of a monster it cannot farm.
     */
    void derive(CitizenSession session, Character agent, long nowMs) {
        if (!on() || session == null || agent == null || hasOpen(session.characterId)
                || random.nextDouble() >= population.config().BOUNTY_CHANCE) {
            return;
        }
        FieldBosses.Boss boss = null;
        for (FieldBosses.Boss one : world().bosses().onMap(agent.getMapId())) {
            if (one.fits(agent.getLevel())) {
                boss = one;
            }
        }
        if (boss != null) {
            postBoss(session, agent, boss, nowMs);
            return;
        }
        Hunt hunt = world().huntFor(agent.getLevel(), random);
        if (hunt != null) {
            postHunt(session, agent, hunt, nowMs);
        }
    }

    /** It died beside a catalogued field boss (PopulationHooks.died): it pins a bounty on it, when it has none open. */
    public void diedBeside(CitizenSession session, MapleMap map, long nowMs) {
        if (!on() || session == null || map == null) {
            return;
        }
        Character agent = session.agent();
        if (agent == null) {
            return;
        }
        FieldBosses.Boss boss = null;
        for (Monster monster : map.getAllMonsters()) {
            FieldBosses.Boss one = monster.isAlive() ? world().bosses().ofMob(monster.getId()) : null;
            if (one != null) {
                boss = one;
            }
        }
        if (boss == null) {
            return;
        }
        FieldBosses.Boss killer = boss;
        world().later("bounty.post " + session.characterId, () -> {
            synchronized (ledger.lock(session.characterId)) {
                if (!hasOpen(session.characterId)) {
                    postBoss(session, agent, killer, nowMs);
                }
            }
        });
    }

    private boolean hasOpen(int citizenId) {
        for (Need need : ledger.of(citizenId)) {
            if (need.kind() == NeedKind.BOUNTY && need.isOpen()) {
                return true;
            }
        }
        return false;
    }

    private int openBounties() {
        int open = 0;
        for (Need need : ledger.open()) {
            open += need.kind() == NeedKind.BOUNTY ? 1 : 0;
        }
        return open;
    }

    /** The price it can stand behind: {@code wanted} capped at BOUNTY_PURSE_SHARE of its purse; 0 below BOUNTY_MIN_OFFER. */
    long affordable(int citizenId, Character agent, long wanted) {
        long purse = ledger.purse(citizenId, agent);
        long cap = (long) (Math.max(0L, purse) * population.config().BOUNTY_PURSE_SHARE);
        long offer = Math.min(wanted, cap);
        return offer < population.config().BOUNTY_MIN_OFFER ? 0L : offer;
    }

    Need postBoss(CitizenSession session, Character agent, FieldBosses.Boss boss, long nowMs) {
        if (openBounties() >= population.config().BOUNTIES_OPEN_MAX) {
            return null;
        }
        long offer = affordable(session.characterId, agent, boss.level() * population.config().BOUNTY_BOSS_PER_LEVEL);
        if (offer <= 0L) {
            return null;
        }
        Need need = ledger.post(session.characterId, session, boss.maps().get(0), new NeedDerivation.Proposal(
                NeedKind.BOUNTY, boss.mobs().get(0), 1, offer, BOSS + boss.key()), ledger.deadlineOf(NeedKind.BOUNTY, nowMs),
                nowMs);
        if (need != null) {
            posted(session, need);
        }
        return need;
    }

    Need postHunt(CitizenSession session, Character agent, Hunt hunt, long nowMs) {
        if (openBounties() >= population.config().BOUNTIES_OPEN_MAX) {
            return null;
        }
        int kills = population.config().BOUNTY_HUNT_KILLS;
        long wanted = (long) kills * hunt.mobLevel() * population.config().BOUNTY_HUNT_PER_LEVEL;
        long total = affordable(session.characterId, agent, wanted);
        long each = total / kills;
        if (each <= 0L) {
            return null;
        }
        Need need = ledger.post(session.characterId, session, hunt.mapId(), new NeedDerivation.Proposal(NeedKind.BOUNTY,
                hunt.mobId(), kills, each, HUNT + hunt.itemId()), ledger.deadlineOf(NeedKind.BOUNTY, nowMs), nowMs);
        if (need != null) {
            posted(session, need);
        }
        return need;
    }

    /** Its journal, and a word on its map when it is about ("putting a bounty on stumpy at the board"). */
    private void posted(CitizenSession session, Need need) {
        session.journal.add("needs", "pinned " + what(need) + " (" + server.living.population.Market.formatPrice(
                need.pay(need.quantity())) + ")");
        if (session.virtual || session.agent() == null) {
            return;
        }
        ChatterEngine.Event event = ChatterEngine.Event.of(boss(need) ? "need.bounty.boss" : "need.bounty.hunt",
                session.mapId, session.channel).self(session);
        slots(need, event);
        String line = population.chatter().compose(event);
        if (line != null) {
            population.chatter().say(session, line);
        }
    }

    void slots(Need need, ChatterEngine.Event event) {
        event.item(target(need))
                .slot("what", what(need))
                .slot("count", String.valueOf(need.remaining()))
                .slot("price", server.living.population.Market.formatPrice(need.offer()))
                .slot("total", server.living.population.Market.formatPrice(need.pay(need.quantity())))
                .slot("for", forWords(need));
    }

    // ---- kills --------------------------------------------------------------------------------

    /** The minute: which held bounties the kill checks look for. */
    void minute(long nowMs) {
        Map<Integer, Set<Integer>> huntIndex = new HashMap<>();
        Map<Integer, Set<Integer>> bossIndex = new HashMap<>();
        if (on()) {
            for (Need need : ledger.open()) {
                if (need.kind() != NeedKind.BOUNTY || need.holder(nowMs) == 0) {
                    continue;
                }
                index(need, huntIndex, bossIndex);
            }
        }
        hunts = Map.copyOf(huntIndex);
        bosses = Map.copyOf(bossIndex);
    }

    private void index(Need need, Map<Integer, Set<Integer>> huntIndex, Map<Integer, Set<Integer>> bossIndex) {
        if (hunt(need)) {
            huntIndex.computeIfAbsent(need.itemId(), id -> new HashSet<>()).add(need.id());
        } else if (boss(need)) {
            FieldBosses.Boss boss = world().bosses().byKey(bossKey(need));
            for (int mob : boss == null ? List.of(need.itemId()) : boss.mobs()) {
                bossIndex.computeIfAbsent(mob, id -> new HashSet<>()).add(need.id());
            }
        }
    }

    /** Whether an online human holds a boss bounty on {@code mobId} now (no rolled kill takes it from them, N6.3). */
    public boolean held(int mobId, long nowMs) {
        Set<Integer> ids = bosses.get(mobId);
        if (ids == null || !on()) {
            return false;
        }
        for (int needId : ids) {
            Need need = ledger.need(needId);
            int holder = need == null ? 0 : need.holder(nowMs);
            if (holder != 0 && world().online(holder) != null) {
                return true;
            }
        }
        return false;
    }

    /** A claim was taken: its kills count from now, not from the next minute. */
    void claimed(Need need) {
        Map<Integer, Set<Integer>> huntIndex = copy(hunts);
        Map<Integer, Set<Integer>> bossIndex = copy(bosses);
        index(need, huntIndex, bossIndex);
        hunts = Map.copyOf(huntIndex);
        bosses = Map.copyOf(bossIndex);
    }

    private static Map<Integer, Set<Integer>> copy(Map<Integer, Set<Integer>> index) {
        Map<Integer, Set<Integer>> out = new HashMap<>();
        index.forEach((mob, ids) -> out.put(mob, new HashSet<>(ids)));
        return out;
    }

    /**
     * Any monster's death (PopulationHooks.mobKilled, the killing thread, memory only): a held hunt bounty on it counts
     * the kill when its claimant dealt the most damage; nothing else is read for a monster no bounty names.
     */
    public void mobKilled(Monster monster, long nowMs) {
        Set<Integer> held = monster == null ? null : hunts.get(monster.getId());
        if (held == null || !on()) {
            return;
        }
        int top = monster.getHighestDamagerId();
        for (int needId : held) {
            Need need = ledger.need(needId);
            if (need != null && hunt(need) && top != 0 && need.holder(nowMs) == top) {
                world().later("bounty.kill " + needId, () -> counted(needId, top, System.currentTimeMillis()));
            }
        }
    }

    /**
     * A field boss died ({@link FieldBossKills} listener, the killing thread): each held boss bounty on it pays when
     * its claimant's party (the claimant and its party's members, wherever they stood) dealt BOUNTY_SHARE_PERCENT of the
     * boss's damage.
     */
    public void bossKilled(FieldBossKills.Kill kill) {
        Set<Integer> held = bosses.get(kill.mobId());
        if (held == null || !on()) {
            return;
        }
        for (int needId : held) {
            Need need = ledger.need(needId);
            int claimant = need == null ? 0 : need.holder(kill.killedAtMs());
            if (claimant == 0 || !kill.boss().key().equals(bossKey(need))) {
                continue;
            }
            Character player = world().online(claimant);
            Set<Integer> side = party(player, claimant);
            double share = kill.share(side);
            if (share * 100.0d >= population.config().BOUNTY_SHARE_PERCENT && player != null) {
                world().later("bounty.boss " + needId, () -> ledger.payBounty(player, needId, 1, share,
                        System.currentTimeMillis()));
            } else {
                log.info("bounty {} on {}: claimant {}'s party dealt {}% (needs {}%)", needId, kill.boss().key(),
                        claimant, Math.round(share * 100.0d), population.config().BOUNTY_SHARE_PERCENT);
            }
        }
    }

    /** The claimant and its party's members. */
    static Set<Integer> party(Character player, int claimant) {
        Set<Integer> side = new HashSet<>();
        side.add(claimant);
        Party party = player == null ? null : player.getParty();
        if (party != null) {
            for (PartyCharacter member : party.getMembers()) {
                side.add(member.getId());
            }
        }
        return side;
    }

    /** On the executor: one more kill of the claimant's; the last one pays. */
    void counted(int needId, int claimant, long nowMs) {
        Character player = world().online(claimant);
        if (player != null) {
            ledger.payBounty(player, needId, 1, 1.0d, nowMs);
        }
    }

    // ---- the server ---------------------------------------------------------------------------

    private static final class LiveWorld implements World {
        private final Population population;
        private volatile FieldBosses bosses;

        LiveWorld(Population population) {
            this.population = population;
        }

        @Override
        public FieldBosses bosses() {
            FieldBosses current = bosses;
            if (current == null) {
                current = FieldBosses.load(java.nio.file.Path.of(population.config().CONTENT_DIR));
                bosses = current;
            }
            return current;
        }

        /**
         * An Etc good of the price basket (what citizens gather and sell) whose wild, non-boss dropper is
         * TOO_STRONG_ABOVE..HUNT_ABOVE_MAX levels over the citizen and lives on an open field.
         */
        @Override
        public Hunt huntFor(int level, Random random) {
            server.knowledge.WorldKnowledge k = server.knowledge.WorldKnowledge.current();
            if (k == null) {
                return null;
            }
            List<server.living.population.PopulationContent.Good> basket = new java.util.ArrayList<>(
                    population.content().basket());
            java.util.Collections.shuffle(basket, random);
            for (server.living.population.PopulationContent.Good good : basket) {
                if (good.itemId() / 1_000_000 != 4) {
                    continue;
                }
                for (server.knowledge.DropSource source : k.dropSources(good.itemId())) {
                    if (source.global() || source.boss() || source.questId() != 0 || source.maps().isEmpty()
                            || !k.wild(source.mobId())) {
                        continue;
                    }
                    int mobLevel = k.mob(source.mobId()).map(server.knowledge.MobFact::level).orElse(0);
                    if (mobLevel < level + TOO_STRONG_ABOVE || mobLevel > level + HUNT_ABOVE_MAX) {
                        continue;
                    }
                    for (int i = 0; i < source.maps().size(); i++) {
                        int mapId = source.maps().getInt(i);
                        if (!k.isTown(mapId) && k.fieldCapacity(mapId) > 0) {
                            return new Hunt(good.itemId(), source.mobId(), mobLevel, mapId);
                        }
                    }
                }
            }
            return null;
        }

        @Override
        public String mobName(int mobId) {
            try {
                return server.life.MonsterInformationProvider.getInstance().getMobNameFromId(mobId);
            } catch (RuntimeException failure) {
                return null;
            }
        }

        @Override
        public void later(String what, Runnable work) {
            population.world().executor().submit(what, work);
        }

        @Override
        public Character online(int characterId) {
            net.server.world.World game = population.gameWorld();
            return game == null ? null : game.getPlayerStorage().getCharacterById(characterId);
        }
    }
}
