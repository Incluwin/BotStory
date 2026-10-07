package server.agents.capabilities.expedition.boss;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.AgentBossFight;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One boss run: the adopted companions of one expedition. While the expedition registers, each enlisted member walks
 * up to the entry NPC and signs up through her own window, one conversation per tick, the way the GM expedition lobby
 * signs its members up; a member still at the store, or off the recruit map, waits until the list is about to close.
 * After Start the run adopts each signed-up member the stock warp took into the expedition's event on the battle map,
 * drives the alive ones through the fight, and at the clear hands them back to following their owner.
 * {@link #tick} runs on the Agent scheduler under the session's lock.
 */
public final class AgentBossRunSession {
    private static final Logger log = LoggerFactory.getLogger(AgentBossRunSession.class);

    /** Where the run stands. */
    public enum Phase {
        REGISTERING, STARTING, FIGHTING, POST_CLEAR, ENDED
    }

    private final long sessionId;
    private final AgentBossRunDefinition definition;
    private final Expedition expedition;
    private final AgentBossFight fight;
    private final long createdAtMs;
    private final List<AgentBossRunMember> members = new CopyOnWriteArrayList<>();
    private volatile Phase phase = Phase.REGISTERING;
    /** The expedition's event, from the first member adopted in it; null until then. */
    private EventInstanceManager event;
    private long startedAtMs;
    private int partySize;
    private boolean cleared;

    AgentBossRunSession(long sessionId, AgentBossRunDefinition definition, Expedition expedition,
                        AgentBossFight fight, long nowMs) {
        if (definition == null || expedition == null || fight == null) {
            throw new IllegalArgumentException("a boss run needs a definition, an expedition and a fight");
        }
        this.sessionId = sessionId;
        this.definition = definition;
        this.expedition = expedition;
        this.fight = fight;
        this.createdAtMs = nowMs;
    }

    public long sessionId() {
        return sessionId;
    }

    public AgentBossRunDefinition definition() {
        return definition;
    }

    public Expedition expedition() {
        return expedition;
    }

    public long createdAtMs() {
        return createdAtMs;
    }

    public Phase phase() {
        return phase;
    }

    /** Every member in enlist order, refused ones included. */
    public List<AgentBossRunMember> members() {
        return List.copyOf(members);
    }

    public AgentBossRunMember member(int agentId) {
        for (AgentBossRunMember member : members) {
            if (member.agentId() == agentId) {
                return member;
            }
        }
        return null;
    }

    /** Members still WAITING to sign up; lock-free, for the registry's count. */
    int waitingCount() {
        int count = 0;
        for (AgentBossRunMember member : members) {
            if (member.phase() == AgentBossRunMember.Phase.WAITING) {
                count++;
            }
        }
        return count;
    }

    /**
     * Enlists one more member with the next ordinal and indexes it; null once the list is no longer the run's to
     * fill (the run started or ended) or when the Agent is already a member.
     */
    synchronized AgentBossRunMember addMember(int agentId, int enlistedBy, long nowMs) {
        if (phase != Phase.REGISTERING || member(agentId) != null) {
            return null;
        }
        AgentBossRunMember member = new AgentBossRunMember(agentId, enlistedBy, members.size(), nowMs);
        members.add(member);
        AgentBossRunRegistry.index(this, agentId);
        return member;
    }

    synchronized void tick(AgentBossRunPorts ports, AgentBossRunHost.Rule host, long nowMs) {
        try {
            switch (phase) {
                case REGISTERING -> tickRegistering(ports, host, nowMs);
                case STARTING, FIGHTING -> tickStarted(ports, host, nowMs);
                case POST_CLEAR, ENDED -> {
                }
            }
        } finally {
            AgentBossRunRegistry.recount();
        }
    }

    private void tickRegistering(AgentBossRunPorts ports, AgentBossRunHost.Rule host, long nowMs) {
        // A Stop or the registration timeout drops the expedition from its channel but leaves it registering.
        if (!ports.stillTheChannels(expedition)) {
            end(host, "disbanded", nowMs);
            return;
        }
        if (!expedition.isRegistering()) {
            phase = Phase.STARTING;
            startedAtMs = nowMs;
            log.info("Boss run starting: session={} boss={} registered={}", sessionId, definition.key(),
                    count(AgentBossRunMember.Phase.REGISTERED));
            return;
        }
        for (AgentBossRunMember member : members) {
            if (member.phase() != AgentBossRunMember.Phase.REGISTERED) {
                continue;
            }
            Character agent = ports.agent(member.agentId());
            if (agent != null && !expedition.contains(agent)) {
                refuse(host, member, AgentBossRunHost.Refusal.BANNED);
            }
        }
        long deadline = expedition.getStartTime() - registrationMarginMs();
        boolean turnTaken = false;
        for (AgentBossRunMember member : members) {
            if (member.phase() != AgentBossRunMember.Phase.WAITING) {
                continue;
            }
            int agentId = member.agentId();
            if (ports.restocking(agentId, nowMs)) {
                if (nowMs >= deadline) {
                    refuse(host, member, AgentBossRunHost.Refusal.LATE);
                }
                continue;
            }
            if (ports.restockPending(agentId, nowMs) && nowMs - member.enlistedAtMs() < verdictWaitMs()) {
                continue;
            }
            Character agent = ports.agent(agentId);
            if (agent == null || agent.getMapId() != definition.recruitMapId()) {
                member.onMapSince(0L);
                if (nowMs >= deadline) {
                    refuse(host, member, AgentBossRunHost.Refusal.NOT_HERE);
                }
                continue;
            }
            if (member.onMapSinceMs() == 0L) {
                member.onMapSince(nowMs);
            }
            // Only one conversation per tick; a member that cannot reach her signs up from where it stands.
            if (turnTaken || (!inReach(ports, agent) && nowMs - member.onMapSinceMs() < walkTimeoutMs())) {
                continue;
            }
            turnTaken = true;
            signUp(ports, host, member, agent);
        }
    }

    private boolean inReach(AgentBossRunPorts ports, Character agent) {
        Point npc = ports.npcPosition(agent, definition.entryNpcId());
        Point position = agent.getPosition();
        long reach = npcReachPx();
        return npc != null && position != null && position.distanceSq(npc) <= reach * reach;
    }

    /** The member's turn: tie it to the run, then one conversation with the entry NPC. */
    private void signUp(AgentBossRunPorts ports, AgentBossRunHost.Rule host, AgentBossRunMember member,
                        Character agent) {
        AgentBossRunHost.Seat seat = member.seat(definition.key(), sessionId);
        if (!host.seat(seat)) {
            refuse(host, member, AgentBossRunHost.Refusal.BUSY);
            return;
        }
        AgentBossRunHost.Refusal reason = refusalBefore(agent);
        boolean threw = false;
        try {
            ports.runNpc(agent, definition.entryNpcId(), definition.joinSelectionArray());
        } catch (RuntimeException failure) {
            threw = true;
            log.warn("Boss run sign-up conversation failed: session={} member={}", sessionId, agent.getId(), failure);
        }
        if (expedition.contains(agent)) {
            member.registered();
            log.info("Boss run sign-up: session={} boss={} member={} registered", sessionId, definition.key(),
                    agent.getId());
            host.registered(seat);
            return;
        }
        refuse(host, member, threw ? AgentBossRunHost.Refusal.UNKNOWN : reason);
    }

    /** Why the entry NPC would turn the member away, read before it asks; UNKNOWN when nothing visible stops it. */
    private AgentBossRunHost.Refusal refusalBefore(Character agent) {
        ExpeditionType type = expedition.getType();
        if (type != null && (agent.getLevel() < type.getMinLevel() || agent.getLevel() > type.getMaxLevel())) {
            return AgentBossRunHost.Refusal.LEVEL;
        }
        if (!expedition.isRegistering()) {
            return AgentBossRunHost.Refusal.CLOSED;
        }
        if (expedition.getMembers().size() >= expedition.getMaxSize()) {
            return AgentBossRunHost.Refusal.FULL;
        }
        return AgentBossRunHost.Refusal.UNKNOWN;
    }

    private void refuse(AgentBossRunHost.Rule host, AgentBossRunMember member, AgentBossRunHost.Refusal reason) {
        member.refused(reason);
        log.info("Boss run sign-up: session={} boss={} member={} refused={}", sessionId, definition.key(),
                member.agentId(), reason);
        host.refused(member.seat(definition.key(), sessionId), reason);
    }

    /**
     * After Start: a member still waiting is too late; a signed-up member found in the event on the battle map is
     * adopted, one still outside after the grace missed the start. The run fights while anyone is inside, ends when
     * nobody went in, and stops the fight at the clear.
     */
    private void tickStarted(AgentBossRunPorts ports, AgentBossRunHost.Rule host, long nowMs) {
        boolean graceOver = nowMs - startedAtMs >= startGraceMs();
        for (AgentBossRunMember member : members) {
            switch (member.phase()) {
                case WAITING -> refuse(host, member, AgentBossRunHost.Refusal.LATE);
                case REGISTERED -> adoptOrMiss(ports, host, member, graceOver, nowMs);
                default -> {
                }
            }
        }
        if (phase == Phase.STARTING) {
            if (count(AgentBossRunMember.Phase.INSIDE) == 0) {
                if (count(AgentBossRunMember.Phase.REGISTERED) == 0) {
                    end(host, "nobody-inside", nowMs);
                }
                return;
            }
            phase = Phase.FIGHTING;
            log.info("Boss run fighting: session={} boss={} inside={} partySize={}", sessionId, definition.key(),
                    count(AgentBossRunMember.Phase.INSIDE), partySize);
        }
        if (event.isEventCleared()) {
            clear(ports);
            return;
        }
        tickFight(ports, nowMs);
    }

    /** Adopts a signed-up member the Start took into this expedition's event on the battle map. */
    private void adoptOrMiss(AgentBossRunPorts ports, AgentBossRunHost.Rule host, AgentBossRunMember member,
                             boolean graceOver, long nowMs) {
        int agentId = member.agentId();
        Character agent = ports.agent(agentId);
        EventInstanceManager memberEvent = agent == null ? null : agent.getEventInstance();
        if (memberEvent != null && memberEvent.getExpedition() == expedition
                && agent.getMapId() == definition.battleMapId()) {
            if (ports.prepareAdoption(agentId, "entering " + definition.key(), nowMs)) {
                if (event == null) {
                    event = memberEvent;
                    partySize = memberEvent.getPlayerCount();
                }
                fight.adopt(agentId, member.ordinal());
                member.adopted();
                log.info("Boss run member inside: session={} boss={} member={} ordinal={}", sessionId,
                        definition.key(), agentId, member.ordinal());
                return;
            }
            log.info("Boss run adoption waits: session={} boss={} member={} (another activity would not stop)",
                    sessionId, definition.key(), agentId);
        }
        if (graceOver) {
            memberOut(host, member, AgentBossRunHost.Out.MISSED_START);
        }
    }

    /** The boss fell: the members inside stop fighting and follow their owner again; the fight is not ticked after. */
    private void clear(AgentBossRunPorts ports) {
        cleared = true;
        for (AgentBossRunMember member : members) {
            if (member.phase() != AgentBossRunMember.Phase.INSIDE) {
                continue;
            }
            int agentId = member.agentId();
            ports.stop(agentId);
            ports.clearBoundary(agentId);
            ports.follow(agentId);
            member.clearedInside();
        }
        phase = Phase.POST_CLEAR;
        log.info("Boss run cleared: session={} boss={} inside={}", sessionId, definition.key(),
                count(AgentBossRunMember.Phase.INSIDE));
    }

    /** Ticks the fight for the alive members inside that stand on the battle map in the event; a failure is logged. */
    private void tickFight(AgentBossRunPorts ports, long nowMs) {
        List<Character> fighters = new ArrayList<>();
        for (AgentBossRunMember member : members) {
            if (member.phase() != AgentBossRunMember.Phase.INSIDE) {
                continue;
            }
            Character agent = ports.agent(member.agentId());
            if (agent != null && agent.isAlive() && agent.getMapId() == definition.battleMapId()
                    && agent.getEventInstance() == event) {
                fighters.add(agent);
            }
        }
        if (fighters.isEmpty()) {
            return;
        }
        try {
            fight.tick(fighters, event, nowMs);
        } catch (RuntimeException failure) {
            log.warn("Boss run fight tick failed: session={} boss={} fighters={}", sessionId, definition.key(),
                    fighters.size(), failure);
        }
    }

    private void memberOut(AgentBossRunHost.Rule host, AgentBossRunMember member, AgentBossRunHost.Out out) {
        member.leftRun(out);
        log.info("Boss run member out: session={} boss={} member={} out={}", sessionId, definition.key(),
                member.agentId(), out);
        host.memberOut(member.seat(definition.key(), sessionId), out);
    }

    private int count(AgentBossRunMember.Phase wanted) {
        return (int) members.stream().filter(member -> member.phase() == wanted).count();
    }

    /** Ends the run once: it leaves the registry, then the host hears how it went. */
    synchronized void end(AgentBossRunHost.Rule host, String reason, long nowMs) {
        if (phase == Phase.ENDED) {
            return;
        }
        boolean started = phase != Phase.REGISTERING;
        phase = Phase.ENDED;
        AgentBossRunRegistry.remove(this);
        if (event != null) {
            try {
                fight.end(event);
            } catch (RuntimeException failure) {
                log.warn("Boss run fight end failed: session={} boss={}", sessionId, definition.key(), failure);
            }
        }
        List<AgentBossRunHost.MemberResult> results = members.stream().map(AgentBossRunMember::result).toList();
        log.info("Boss run ended: session={} boss={} reason={} started={} cleared={} members={}", sessionId,
                definition.key(), reason, started, cleared, results.size());
        host.runEnded(new AgentBossRunHost.RunEnded(sessionId, definition.key(), started, cleared, reason,
                started ? startedAtMs : 0L, nowMs, partySize, results));
    }

    // ---- tuning (agent-engine.yaml), read at call time ----------------------------------------

    static long registrationMarginMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.expedition.boss.AgentBossRunSession.REGISTRATION_MARGIN_MS");
    }

    static long verdictWaitMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.expedition.boss.AgentBossRunSession.VERDICT_WAIT_MS");
    }

    static int npcReachPx() {
        return config.AgentTuning.intValue(
                "server.agents.capabilities.expedition.boss.AgentBossRunSession.NPC_REACH_PX");
    }

    static long walkTimeoutMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.expedition.boss.AgentBossRunSession.WALK_TIMEOUT_MS");
    }

    static long startGraceMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.expedition.boss.AgentBossRunSession.START_GRACE_MS");
    }
}
