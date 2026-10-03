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
 * run counts it as one of its extras), keeps the party-quest standard by itself; one told to wait or sent somewhere,
 * on its way, away on an errand, on a live bench slice, in a run or with the kill switch off has no duty. The Agent
 * decides when and where it goes; this only says what it says about it: setting off and back
 * (an ACTIVE one; a held one goes and comes back quietly, back to the spot it waits at), and, at most once per
 * COMPANION_RESTOCK_ASK_EVERY_MS, that it cannot afford its potions or that a boss's gate has no shop.
 *
 * <p>Memory only: {@link #standing} answers on any thread, the callbacks on the Agent tick and only hand their lines to
 * companion-ops; it never reads the store or writes a row.</p>
 */
public final class CompanionRestock implements AgentSupplyReadiness.Rule {
    private final LivingWorld world;
    private final LongSupplier clock;
    /** "characterId intent" -> when that companion last said that line on its own. */
    private final Map<String, Long> askedAt = new ConcurrentHashMap<>();

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
            return AgentSupplyReadiness.Activity.PARTY_QUEST;
        }
        if (world.commitments().isCommitted(agentCharacterId) || world.roster().keepsOffFollow(at.personaId())) {
            return null; // tied to a run, on its way, or away on an errand
        }
        if (!AgentSupplyReadiness.carriedBy(AgentRuntimeRegistry.findByAgentCharacterId(agentCharacterId),
                at.ownerId())) {
            return null; // told to wait, sent somewhere or hunting on its own: its owner's order stands
        }
        return AgentSupplyReadiness.Activity.PARTY_QUEST;
    }

    @Override
    public void started(int agentCharacterId, AgentSupplyReadiness.Activity activity, long etaMs) {
        CompanionSettingsApplier.Bound at = world.roster().settings().boundTo(agentCharacterId);
        if (at == null || held(at)) {
            return; // a held companion goes quietly: its owner is inside the run
        }
        world.executor().submit("companion.restock.go " + agentCharacterId,
                () -> say(agentCharacterId, CompanionRestockLines.GO));
    }

    @Override
    public void refused(int agentCharacterId, AgentSupplyReadiness.Activity activity,
                        AgentSupplyReadiness.Refusal why, AgentSupplyReadiness.Shortfall shortfall, int missingMesos) {
        if (why == AgentSupplyReadiness.Refusal.CANNOT_AFFORD && mayAsk(agentCharacterId, CompanionRestockLines.BROKE,
                world.config().COMPANION_RESTOCK_ASK_EVERY_MS)) {
            world.executor().submit("companion.restock.broke " + agentCharacterId,
                    () -> say(agentCharacterId, CompanionRestockLines.BROKE));
        }
    }

    @Override
    public void finished(int agentCharacterId, AgentSupplyReadiness.Activity activity,
                         AgentSupplyReadiness.Outcome outcome) {
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
                    () -> say(agentCharacterId, CompanionRestockLines.BACK));
        } else if (outcome == AgentSupplyReadiness.Outcome.SHORT_MESOS
                && mayAsk(agentCharacterId, CompanionRestockLines.BROKE, world.config().COMPANION_RESTOCK_ASK_EVERY_MS)) {
            world.executor().submit("companion.restock.broke " + agentCharacterId,
                    () -> say(agentCharacterId, CompanionRestockLines.BROKE));
        }
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
     * standard says so, once per COMPANION_RESTOCK_ASK_EVERY_MS. Entry is never held up.
     */
    void bossGate(int ownerId, int mapId) {
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
            if (!AgentSupplyReadiness.shortfall(bot, AgentSupplyReadiness.Activity.PARTY_QUEST).none()
                    && mayAsk(id, CompanionRestockLines.NO_SHOP, world.config().COMPANION_RESTOCK_ASK_EVERY_MS)) {
                CompanionRestockLines.say(world, bot, CompanionRestockLines.NO_SHOP);
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** True while the companion waits outside a run for its owner (its HOLD seat). */
    private boolean held(CompanionSettingsApplier.Bound at) {
        CompanionSlots.Reservation seat = world.slots().reservation(at.personaId());
        return seat != null && seat.kind() == CompanionSlots.Kind.HOLD && seat.ownerId() == at.ownerId();
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
        Character bot = AgentRuntimeIdentityRuntime.bot(AgentRuntimeRegistry.findByAgentCharacterId(agentCharacterId));
        CompanionRestockLines.say(world, bot, intent);
    }
}
