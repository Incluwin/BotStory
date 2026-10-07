package server.agents.capabilities.expedition.boss;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.expedition.AgentBossFight;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One boss run: the adopted companions of one expedition. While the expedition registers, each enlisted member walks
 * up to the entry NPC and signs up through her own window, one conversation per tick, the way the GM expedition lobby
 * signs its members up; a member still at the store, or off the recruit map, waits until the list is about to close.
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
                case STARTING, FIGHTING, POST_CLEAR, ENDED -> {
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
        List<AgentBossRunHost.MemberResult> results = members.stream().map(AgentBossRunMember::result).toList();
        log.info("Boss run ended: session={} boss={} reason={} started={} members={}", sessionId, definition.key(),
                reason, started, results.size());
        host.runEnded(new AgentBossRunHost.RunEnded(sessionId, definition.key(), started, false, reason, 0L, nowMs,
                0, results));
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
}
