package server.living.roster;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Companions restock their own potions (2026-10-02, docs/BOT_MANAGEMENT_PLAN.md "Companion restock"): the living
 * world's answer to the Agent layer's readiness seam (AgentSupplyReadiness.Rule). Every companion its owner carries
 * along, out with them (ACTIVE, led by them and following them) or waiting outside a run for them (HOLD, even while the
 * run counts it as one of its extras), keeps the party-quest standard (that of the desk it stands at) by itself; one told to wait or sent somewhere,
 * on its way, away on an errand, on a live bench slice, in a run or with the kill switch off has no duty. The Agent
 * decides when and where it goes; this only says what it says about it: setting off and back
 * (an ACTIVE one; a held one goes and comes back quietly, back to the spot it waits at), and, at most once per
 * COMPANION_RESTOCK_ASK_EVERY_MS, that it cannot afford its potions or that a boss's gate has no shop. In a boss's
 * entry NPC's room (Mu Young's, while COMPANION_BOSS_ENABLED_TYPES lists Easy Balrog) it keeps that boss's standard,
 * says about when it will be back as it sets off, and says when no shop is in reach (P7, user decision 7). A trip for
 * projectiles alone (its potions are fine; 2026-10-07) says the same things in its ammo lines, naming what it shoots;
 * a trip short of both says the potion lines. Since no shop is in reach of her room, it keeps that standard on the
 * walk down too, at the store of the boss's approach map (Ant Tunnel Park's 24 Hr Mobile Store), a field the rule
 * opens to its trip while it is short, at the boss's level and in its owner's party (fix L7).
 *
 * <p>Memory only: {@link #standing} answers on any thread, the callbacks on the Agent tick and only hand their lines to
 * companion-ops; it never reads the store or writes a row.</p>
 */
public final class CompanionRestock implements AgentSupplyReadiness.Rule {
    private final LivingWorld world;
    private final LongSupplier clock;
    /** "characterId intent" -> when that companion last said that line on its own. */
    private final Map<String, Long> askedAt = new ConcurrentHashMap<>();
    /** Character id -> what it shoots, while its trip under way is one for projectiles alone. */
    private final Map<Integer, String> ammoTrips = new ConcurrentHashMap<>();

    public CompanionRestock(LivingWorld world) {
        this(world, System::currentTimeMillis);
    }

    CompanionRestock(LivingWorld world, LongSupplier clock) {
        this.world = world;
        this.clock = clock;
    }

    /** Installs the readiness rule (LivingWorld.start). */
    public void install() {
        AgentSupplyReadiness.install(this);
    }

    /** Takes it out again (LivingWorld.stop): no Agent keeps a standing duty. */
    public static void uninstall() {
        AgentSupplyReadiness.install(null);
    }

    // ---- the seam (memory only) -----------------------------------------------------------

    @Override
    public AgentSupplyReadiness.Activity standing(int agentCharacterId) {
        if (!world.config().COMPANION_RESTOCK_ENABLED) {
            return null;
        }
        CompanionSettingsApplier.Bound at = world.roster().settings().boundTo(agentCharacterId);
        if (at == null || AgentPartyQuestEngagementRegistry.forMember(agentCharacterId) != null
                || AgentRuntimeRegistry.leaderIdForAgentCharacter(agentCharacterId) != at.ownerId()) {
            return null; // not a companion, in a run, or leading itself (a bench slice)
        }
        if (held(at)) {
            // waiting outside its owner's run: the run commits its extras too, and it restocks meanwhile
            return deskActivity(agentCharacterId);
        }
        if (world.commitments().isCommitted(agentCharacterId) || world.roster().keepsOffFollow(at.personaId())) {
            return null; // tied to a run, on its way, or away on an errand
        }
        if (!AgentSupplyReadiness.carriedBy(AgentRuntimeRegistry.findByAgentCharacterId(agentCharacterId),
                at.ownerId())) {
            return null; // told to wait, sent somewhere or hunting on its own: its owner's order stands
        }
        AgentSupplyReadiness.Activity onTheWay = onTheWay(agentCharacterId, at.ownerId());
        return onTheWay != null ? onTheWay : deskActivity(agentCharacterId);
    }

    /**
     * The boss's standard a companion out with its owner keeps on that boss's approach map with a store
     * (CompanionBossRuns.approachKeyFor: Ant Tunnel Park on the walk down to Easy Balrog, while the switch lists it,
     * at the boss's level and in its owner's party); null anywhere else (fix L7).
     */
    private AgentSupplyReadiness.Activity onTheWay(int agentCharacterId, int ownerId) {
        CompanionBossRuns bossRuns = world.bossRuns();
        String key = bossRuns == null ? null : bossRuns.approachKeyFor(bot(agentCharacterId), ownerId);
        return key == null ? null : AgentSupplyReadiness.Activity.partyQuest(key);
    }

    /**
     * The standard of the party quest whose desk the companion stands at (PqEntrance.keyAt: Lakelis's in Kerning
     * City, Tory's in Henesys Park, ...), so the desk and the companion agree on what to carry in; else that of the
     * boss whose entry NPC's room it stands in while the switch lists it (CompanionBossRuns.restockKeyAt: Easy
     * Balrog's in Mu Young's); the default anywhere else.
     */
    private AgentSupplyReadiness.Activity deskActivity(int agentCharacterId) {
        Character bot = bot(agentCharacterId);
        if (bot == null) {
            return AgentSupplyReadiness.Activity.PARTY_QUEST;
        }
        String key = PqEntrance.keyAt(bot.getMapId());
        CompanionBossRuns bossRuns = world.bossRuns();
        if (key == null && bossRuns != null) {
            key = bossRuns.restockKeyAt(bot.getMapId());
        }
        return AgentSupplyReadiness.Activity.partyQuest(key);
    }

    /**
     * Never from a boss's gate (COMPANION_RESTOCK_BOSS_MAPS), nor from a boss's entry NPC room that holds its trips
     * while the switch lists that boss (CompanionBossRuns.holdsTripsAt: Adobis's room for Zakum, P8, where no shop is in
     * reach and no route leads back in): it keeps its no-shop line, and the companion stays put however short it is (a
     * critical request only asks the party). Memory only.
     */
    @Override
    public boolean restocksFrom(int agentCharacterId, int mapId) {
        CompanionBossRuns bossRuns = world.bossRuns();
        return !world.config().companionRestockBossMap(mapId) && (bossRuns == null || !bossRuns.holdsTripsAt(mapId));
    }

    /**
     * A boss's approach map with a store (Ant Tunnel Park, a hunting field, on the walk down to Easy Balrog) is open to
     * a companion's trip for that boss's standard only: while it keeps that standard there ({@link #standing}: out with
     * its owner, at the boss's level, in its owner's party, the switch listing the boss), stands on {@code mapId}, and
     * is short of it. Memory only (fix L7).
     */
    @Override
    public boolean restocksOnField(int agentCharacterId, int mapId, AgentSupplyReadiness.Activity activity) {
        CompanionSettingsApplier.Bound at = world.roster().settings().boundTo(agentCharacterId);
        if (activity == null || at == null || !activity.equals(standing(agentCharacterId))
                || !activity.equals(onTheWay(agentCharacterId, at.ownerId()))) {
            return false; // no duty, a desk's or the default one, or held outside a run
        }
        Character bot = bot(agentCharacterId);
        return bot != null && bot.getMapId() == mapId && !AgentSupplyReadiness.shortfall(bot, activity).none();
    }

    /**
     * It set off: an ACTIVE companion says so, from a boss's entrance with about when it will be back (the
     * owner may be signing it up meanwhile: user decision 7).
     */
    @Override
    public void started(int agentCharacterId, AgentSupplyReadiness.Activity activity, long etaMs) {
        CompanionSettingsApplier.Bound at = world.roster().settings().boundTo(agentCharacterId);
        if (at == null || held(at)) {
            return; // a held companion goes quietly: its owner is inside the run
        }
        Character bot = bot(agentCharacterId);
        if (bot != null && ammoOnly(AgentSupplyReadiness.shortfall(bot, activity))) {
            String word = CompanionRestockLines.ammoWord(bot);
            ammoTrips.put(agentCharacterId, word);
            if (boss(activity)) {
                int minutes = (int) DeskLines.minutes(etaMs / 1000.0d);
                world.executor().submit("companion.restock.boss_go_ammo " + agentCharacterId,
                        () -> BossRunLines.say(world, bot(agentCharacterId), BossRunLines.GO_AMMO, null, minutes, null,
                                word));
                return;
            }
            world.executor().submit("companion.restock.go_ammo " + agentCharacterId,
                    () -> say(agentCharacterId, CompanionRestockLines.GO_AMMO, word));
            return;
        }
        ammoTrips.remove(agentCharacterId);
        if (boss(activity)) {
            int minutes = (int) DeskLines.minutes(etaMs / 1000.0d);
            world.executor().submit("companion.restock.boss_go " + agentCharacterId,
                    () -> BossRunLines.say(world, bot(agentCharacterId), BossRunLines.GO, null, minutes));
            return;
        }
        world.executor().submit("companion.restock.go " + agentCharacterId,
                () -> say(agentCharacterId, CompanionRestockLines.GO));
    }

    /**
     * It did not set off: too broke says so, and short at a boss's entrance with no shop in reach (an ACTIVE companion)
     * says there is none out here; each at most once per COMPANION_RESTOCK_ASK_EVERY_MS, in its ammo words when only
     * projectiles are short (one memory per news: a potion and an ammo broke line never both within it).
     */
    @Override
    public void refused(int agentCharacterId, AgentSupplyReadiness.Activity activity,
                        AgentSupplyReadiness.Refusal why, AgentSupplyReadiness.Shortfall shortfall, int missingMesos) {
        String word = ammoOnly(shortfall) ? CompanionRestockLines.ammoWord(bot(agentCharacterId)) : null;
        if (why == AgentSupplyReadiness.Refusal.CANNOT_AFFORD && mayAsk(agentCharacterId, CompanionRestockLines.BROKE,
                world.config().COMPANION_RESTOCK_ASK_EVERY_MS)) {
            world.executor().submit("companion.restock.broke " + agentCharacterId,
                    () -> say(agentCharacterId, word != null ? CompanionRestockLines.BROKE_AMMO
                            : CompanionRestockLines.BROKE, word));
        } else if (why == AgentSupplyReadiness.Refusal.NO_SUPPLIER && boss(activity) && unheld(agentCharacterId)
                && mayAsk(agentCharacterId, CompanionRestockLines.NO_SHOP,
                world.config().COMPANION_RESTOCK_ASK_EVERY_MS)) {
            world.executor().submit("companion.restock.no_shop " + agentCharacterId,
                    () -> say(agentCharacterId, word != null ? CompanionRestockLines.NO_SHOP_AMMO
                            : CompanionRestockLines.NO_SHOP, word));
        }
    }

    /**
     * The trip is over: an ACTIVE companion says it is back (READY), or too broke (SHORT_MESOS, once per
     * COMPANION_RESTOCK_ASK_EVERY_MS) in its ammo words when the trip set off for projectiles alone or ends short of
     * them alone ({@link #ammoWordAtTheEnd}); a held one walks back to its spot.
     */
    @Override
    public void finished(int agentCharacterId, AgentSupplyReadiness.Activity activity,
                         AgentSupplyReadiness.Outcome outcome) {
        String word = ammoTrips.remove(agentCharacterId); // first: a trip that ends unheard leaves no word behind
        CompanionSettingsApplier.Bound at = world.roster().settings().boundTo(agentCharacterId);
        if (at == null) {
            return;
        }
        if (held(at)) {
            world.executor().submit("companion.restock.after " + agentCharacterId, () -> afterRestock(agentCharacterId));
            return;
        }
        if (outcome == AgentSupplyReadiness.Outcome.READY) {
            world.executor().submit("companion.restock.back " + agentCharacterId,
                    () -> say(agentCharacterId, word != null ? CompanionRestockLines.BACK_AMMO
                            : CompanionRestockLines.BACK, word));
        } else if (outcome == AgentSupplyReadiness.Outcome.SHORT_MESOS
                && mayAsk(agentCharacterId, CompanionRestockLines.BROKE, world.config().COMPANION_RESTOCK_ASK_EVERY_MS)) {
            String broke = word != null ? word : ammoWordAtTheEnd(agentCharacterId, activity);
            world.executor().submit("companion.restock.broke " + agentCharacterId,
                    () -> say(agentCharacterId, broke != null ? CompanionRestockLines.BROKE_AMMO
                            : CompanionRestockLines.BROKE, broke));
        }
    }

    /**
     * The ammo word for a trip that set off short of potions and projectiles and ends short of projectiles alone (it
     * bought the potions; its stars were left unaffordable), so its broke line is about what is still short; null
     * otherwise. A READY trip is short of nothing, so its back line keeps the word it set off with. Reads the bag: on
     * the Agent tick, where the rule's callbacks run.
     */
    private static String ammoWordAtTheEnd(int agentCharacterId, AgentSupplyReadiness.Activity activity) {
        Character bot = bot(agentCharacterId);
        return bot != null && ammoOnly(AgentSupplyReadiness.shortfall(bot, activity))
                ? CompanionRestockLines.ammoWord(bot) : null;
    }

    // ---- on companion-ops -------------------------------------------------------------------

    /**
     * A held companion's trip is over: still held, and back on the map it waits on, it walks to the spot it waits at
     * (RosterService.hold recorded it). It never follows from here: the rejoin does that.
     */
    void afterRestock(int agentCharacterId) {
        CompanionSettingsApplier.Bound at = world.roster().settings().boundTo(agentCharacterId);
        if (at == null || !held(at) || !world.roster().keepsOffFollow(at.personaId())) {
            return;
        }
        RosterService.HoldSpot spot = world.roster().holdSpot(at.personaId());
        AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(agentCharacterId);
        Character bot = runtime == null ? null : AgentRuntimeIdentityRuntime.bot(runtime);
        if (spot == null || spot.point() == null || bot == null || bot.getMapId() != spot.mapId()) {
            return;
        }
        AgentMovementCommandRuntime.moveTo(runtime, spot.point(), false);
    }

    /**
     * The owner came into a party boss's gate map ({@code mapId}, COMPANION_RESTOCK_BOSS_MAPS), from which no shop
     * is reachable: each of their companions with them (on that map, or following them in) that is short of the
     * standard says so (in its ammo words when only projectiles are short), once per COMPANION_RESTOCK_ASK_EVERY_MS.
     * Entry is never held up.
     */
    void bossGate(int ownerId, int mapId) {
        bossGate(ownerId, mapId, AgentSupplyReadiness.Activity.PARTY_QUEST);
    }

    /**
     * {@link #bossGate(int, int)} measured against {@code activity}'s standard: a boss's entry NPC room that holds its
     * companions' trips (CompanionBossRuns.ownerArrived, Adobis's room for Zakum, P8), where none can set off for the
     * boss's standard and so never hears its own no-shop refusal.
     */
    void bossGate(int ownerId, int mapId, AgentSupplyReadiness.Activity activity) {
        Character owner = world.roster().onlineCharacter(ownerId);
        Party party = owner == null ? null : owner.getParty();
        if (party == null) {
            return;
        }
        for (PartyCharacter member : party.getMembers()) {
            int id = member.getId();
            AgentRuntimeEntry runtime = member.isBot() ? AgentRuntimeRegistry.findByAgentCharacterId(id) : null;
            Character bot = runtime == null ? null : AgentRuntimeIdentityRuntime.bot(runtime);
            CompanionSettingsApplier.Bound at = bot == null ? null : world.roster().settings().boundTo(id);
            if (at == null || at.ownerId() != ownerId || held(at) || standing(id) == null
                    || bot.getMapId() != mapId && !AgentModeStateRuntime.following(runtime)) {
                continue;
            }
            AgentSupplyReadiness.Shortfall lacking = AgentSupplyReadiness.shortfall(bot, activity);
            if (!lacking.none()
                    && mayAsk(id, CompanionRestockLines.NO_SHOP, world.config().COMPANION_RESTOCK_ASK_EVERY_MS)) {
                if (lacking.potionsNone()) {
                    CompanionRestockLines.say(world, bot, CompanionRestockLines.NO_SHOP_AMMO,
                            CompanionRestockLines.ammoWord(bot));
                } else {
                    CompanionRestockLines.say(world, bot, CompanionRestockLines.NO_SHOP);
                }
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** True while the companion waits outside a run for its owner (its HOLD seat). */
    private boolean held(CompanionSettingsApplier.Bound at) {
        CompanionSlots.Reservation seat = world.slots().reservation(at.personaId());
        return seat != null && seat.kind() == CompanionSlots.Kind.HOLD && seat.ownerId() == at.ownerId();
    }

    /** True for a companion that is not waiting outside a run for its owner. */
    private boolean unheld(int agentCharacterId) {
        CompanionSettingsApplier.Bound at = world.roster().settings().boundTo(agentCharacterId);
        return at != null && !held(at);
    }

    /** True when only projectiles are short: the trip's lines are its ammo lines. */
    private static boolean ammoOnly(AgentSupplyReadiness.Shortfall shortfall) {
        return shortfall != null && shortfall.potionsNone() && shortfall.ammo() > 0;
    }

    /** True for a boss's activity (its key a boss run's, such as easy_balrog): it kept that boss's standard. */
    private static boolean boss(AgentSupplyReadiness.Activity activity) {
        return activity != null && BossEntrance.of(activity.partyQuestKey()) != null;
    }

    /**
     * True, and remembered, when the companion has not said {@code intent} within {@code everyMs}. One memory for each
     * line, whoever says it: the Lakelis desk asks here too before its broke line (every MANA_ASK_EVERY_MS), so the
     * desk and the companion's own refusal never both say it.
     */
    boolean mayAsk(int agentCharacterId, String intent, long everyMs) {
        long now = clock.getAsLong();
        long every = everyMs;
        boolean[] fresh = {false};
        askedAt.compute(agentCharacterId + " " + intent, (key, last) -> {
            if (last != null && now - last < every) {
                return last;
            }
            fresh[0] = true;
            return now;
        });
        return fresh[0];
    }

    private void say(int agentCharacterId, String intent) {
        CompanionRestockLines.say(world, bot(agentCharacterId), intent);
    }

    private void say(int agentCharacterId, String intent, String ammoWord) {
        CompanionRestockLines.say(world, bot(agentCharacterId), intent, ammoWord);
    }

    private static Character bot(int agentCharacterId) {
        return AgentRuntimeIdentityRuntime.bot(AgentRuntimeRegistry.findByAgentCharacterId(agentCharacterId));
    }
}
