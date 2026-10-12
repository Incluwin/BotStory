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
 * drives the alive ones through the fight, and at the clear hands them back to following their owner. A member that
 * died, or left the event, waits outside for its owner; when no human stands in the fight any more, or after the clear
 * once its anchor left, a member walks out by the stock ways (P8: for a boss whose owner takes the party out, Zakum, a
 * member that follows its owner does so before the clear too, and the host hears when the owner of one down outside
 * leaves). The run ends when every member is out or the event is gone; a failed tick ends it at once and hands back
 * whoever is still in it. {@link #tick} runs on the Agent scheduler
 * under the session's lock. A SELF_LED member (one that leads itself, with no owner to walk up with or follow) walks to
 * the spot the host names beside the entry NPC, and wherever the others would follow their owner again it is stopped.
 * A member its host withdraws before Start is taken off the entry NPC's list by the next tick and refused; one the
 * Start took in anyway is never adopted. A self-led member is adopted only while its anchor is a player of the event.
 * A member its host has no room for on the list at its turn (AgentBossRunHost.Rule.roomFor) is refused FULL.
 *
 * <p>P12 G7: a party-instance boss (Latanica, Neo City, Papulatus) has no expedition and no sign-up. Its run is keyed
 * by the party's event, opens STARTING when the party goes in, and admits each companion the portal took in as
 * REGISTERED ({@link #admit}); the rest is the same.</p>
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
    /**
     * The run's event: an expedition's from the first member adopted in it (null until then), a party-instance run's
     * from its open.
     */
    private volatile EventInstanceManager event;
    private long startedAtMs;
    private int partySize;
    /** A member went in: the fight was handed someone, so the end tells it. */
    private boolean anyAdopted;
    private boolean cleared;
    /** Since when no human stands in the event; -1 while one does. */
    private long noHumanSinceMs = -1L;
    private boolean exitNpcPrepared;

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

    /** A party-instance run (P12 G7): keyed by the party's {@code event}, STARTING at once, with no expedition. */
    AgentBossRunSession(long sessionId, AgentBossRunDefinition definition, EventInstanceManager event,
                        AgentBossFight fight, long nowMs) {
        if (definition == null || event == null || fight == null) {
            throw new IllegalArgumentException("a party boss run needs a definition, an event and a fight");
        }
        this.sessionId = sessionId;
        this.definition = definition;
        this.expedition = null;
        this.event = event;
        this.fight = fight;
        this.createdAtMs = nowMs;
        this.startedAtMs = nowMs;
        this.phase = Phase.STARTING;
    }

    public long sessionId() {
        return sessionId;
    }

    public AgentBossRunDefinition definition() {
        return definition;
    }

    /** The run's expedition; null for a party-instance run. */
    public Expedition expedition() {
        return expedition;
    }

    /** The run's event: a party-instance run's from its open, an expedition's once a member went in; else null. */
    public EventInstanceManager event() {
        return event;
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

    /** Enlists one more FOLLOWER member; see {@link #addMember(int, int, long, AgentBossRunMember.Kind)}. */
    AgentBossRunMember addMember(int agentId, int enlistedBy, long nowMs) {
        return addMember(agentId, enlistedBy, nowMs, AgentBossRunMember.Kind.FOLLOWER);
    }

    /**
     * Enlists one more member of {@code kind} with the next ordinal and indexes it; null once the list is no longer
     * the run's to fill (the run started or ended) or when the Agent is already a member.
     */
    synchronized AgentBossRunMember addMember(int agentId, int enlistedBy, long nowMs, AgentBossRunMember.Kind kind) {
        if (phase != Phase.REGISTERING || member(agentId) != null) {
            return null;
        }
        AgentBossRunMember member = new AgentBossRunMember(agentId, enlistedBy, members.size(), nowMs, kind);
        members.add(member);
        AgentBossRunRegistry.index(this, agentId);
        return member;
    }

    /**
     * P12 G7: admits one companion the party's portal took into a party-instance run's event as REGISTERED, with the
     * next ordinal, and indexes it; the next tick adopts it once it stands on the battle map. Null for an expedition
     * run, once the fight is past its clear, or when the Agent is already a member.
     */
    synchronized AgentBossRunMember admit(int agentId, int enlistedBy, long nowMs, AgentBossRunMember.Kind kind) {
        if (expedition != null || (phase != Phase.STARTING && phase != Phase.FIGHTING) || member(agentId) != null) {
            return null;
        }
        AgentBossRunMember member = new AgentBossRunMember(agentId, enlistedBy, members.size(), nowMs, kind);
        member.registered();
        members.add(member);
        AgentBossRunRegistry.index(this, agentId);
        return member;
    }

    synchronized void tick(AgentBossRunPorts ports, AgentBossRunHost.Rule host, long nowMs) {
        try {
            switch (phase) {
                case REGISTERING -> tickRegistering(ports, host, nowMs);
                case STARTING, FIGHTING, POST_CLEAR -> tickStarted(ports, host, nowMs);
                case ENDED -> {
                }
            }
        } finally {
            AgentBossRunRegistry.recount();
        }
    }

    private void tickRegistering(AgentBossRunPorts ports, AgentBossRunHost.Rule host, long nowMs) {
        // A Stop or the registration timeout drops the expedition from its channel but leaves it registering.
        if (!ports.stillTheChannels(expedition)) {
            end(ports, host, "disbanded", nowMs);
            return;
        }
        if (!expedition.isRegistering()) {
            phase = Phase.STARTING;
            startedAtMs = nowMs;
            log.info("Boss run starting: session={} boss={} registered={}", sessionId, definition.key(),
                    count(AgentBossRunMember.Phase.REGISTERED));
            return;
        }
        // Withdrawn members go first, so a listed one's own leave below is never taken for the leader's ban. One with
        // no runtime cannot be taken off the list here; the Start warps only members standing on the recruit map.
        for (AgentBossRunMember member : members) {
            AgentBossRunMember.Phase memberPhase = member.phase();
            if (!member.withdrawn() || (memberPhase != AgentBossRunMember.Phase.WAITING
                    && memberPhase != AgentBossRunMember.Phase.REGISTERED)) {
                continue;
            }
            Character agent = memberPhase == AgentBossRunMember.Phase.REGISTERED ? ports.agent(member.agentId()) : null;
            if (agent != null) {
                ports.leaveList(expedition, agent);
            }
            refuse(host, member, AgentBossRunHost.Refusal.WITHDRAWN);
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
        long deadline = AgentBossRunService.signUpDeadlineMs(expedition);
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
            // A self-led member has no owner to walk up with: it walks to the spot the host names, on every tick
            // while it is away from it, the way the GM expedition lobby moves its members.
            Point spot = host.standAt(member.seat(definition.key(), sessionId), agent.getMap(), member.ordinal());
            if (member.kind() == AgentBossRunMember.Kind.SELF_LED && spot != null
                    && !near(agent, spot, spotReachPx())) {
                ports.navigate(agentId, spot, true);
            }
            // Only one conversation per tick; a member that cannot reach her signs up from where it stands.
            if (turnTaken || (!inReach(ports, agent) && !atFarSpot(ports, agent, spot)
                    && nowMs - member.onMapSinceMs() < walkTimeoutMs())) {
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

    /**
     * The member stands at its spot, and the spot lies beyond the entry NPC's reach (a long list lines members up
     * further away): it takes its turn from there instead of waiting out the walk timeout.
     */
    private boolean atFarSpot(AgentBossRunPorts ports, Character agent, Point spot) {
        if (spot == null) {
            return false;
        }
        Point npc = ports.npcPosition(agent, definition.entryNpcId());
        long reach = npcReachPx();
        return npc != null && spot.distanceSq(npc) > reach * reach && near(agent, spot, spotReachPx());
    }

    /**
     * The member's turn: the host's room for it on the list as it is now (no room refuses it FULL), tie it to the run,
     * then one conversation with the entry NPC.
     */
    private void signUp(AgentBossRunPorts ports, AgentBossRunHost.Rule host, AgentBossRunMember member,
                        Character agent) {
        AgentBossRunHost.Seat seat = member.seat(definition.key(), sessionId);
        if (!host.roomFor(seat, expedition.getMembers().size(), expedition.getMaxSize())) {
            refuse(host, member, AgentBossRunHost.Refusal.FULL);
            return;
        }
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
     * nobody went in, and stops the fight at the clear. Deaths and departures are seen before the clear, so a member
     * that left is never handed back as if it were still fighting; the run ends once the event is gone or every
     * member is out.
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
                    end(ports, host, "nobody-inside", nowMs);
                }
                return;
            }
            phase = Phase.FIGHTING;
            log.info("Boss run fighting: session={} boss={} inside={} partySize={}", sessionId, definition.key(),
                    count(AgentBossRunMember.Phase.INSIDE), partySize);
        }
        watchMembers(ports, host, nowMs);
        if (event.isEventDisposed()) {
            // A timeout, or the last player out: the stock event is gone, and with it whoever was still in it.
            for (AgentBossRunMember member : members) {
                if (inTheRun(member)) {
                    handBack(ports, host, member, ports.agent(member.agentId()) != null);
                } else if (member.phase() == AgentBossRunMember.Phase.REENTERING) {
                    letGo(ports, member);
                    memberOut(host, member, AgentBossRunHost.Out.DIED);
                }
            }
            end(ports, host, "event-disposed", nowMs);
            return;
        }
        if (phase == Phase.FIGHTING && event.isEventCleared()) {
            clear(ports);
            return;
        }
        if (phase == Phase.POST_CLEAR || definition.ownerLeavingTakesParty()) {
            followAnchorsOut(ports, host, nowMs);
        }
        watchHumans(ports, nowMs);
        tickLeaving(ports, nowMs);
        tickReentering(ports, host, nowMs);
        if (phase == Phase.FIGHTING) {
            tickFight(ports, nowMs);
        }
        if (count(AgentBossRunMember.Phase.INSIDE) + count(AgentBossRunMember.Phase.LEAVING)
                + count(AgentBossRunMember.Phase.REGISTERED) + count(AgentBossRunMember.Phase.REENTERING) == 0) {
            end(ports, host, "members-out", nowMs);
        }
    }

    /** INSIDE or LEAVING: adopted and not yet out of the event. */
    private static boolean inTheRun(AgentBossRunMember member) {
        AgentBossRunMember.Phase memberPhase = member.phase();
        return memberPhase == AgentBossRunMember.Phase.INSIDE || memberPhase == AgentBossRunMember.Phase.LEAVING;
    }

    /**
     * Deaths and departures of the adopted members. A death counts once, however many ticks see it; a member alive
     * outside the event (the stock respawn, an exit, the event's own warp) is out of the run and follows its owner
     * again; one raised where it fell (alive in the fight on two ticks in a row, see AgentBossRunMember.upAgain)
     * fights on; one whose runtime is gone (parked at a logout) is out with no call.
     */
    private void watchMembers(AgentBossRunPorts ports, AgentBossRunHost.Rule host, long nowMs) {
        for (AgentBossRunMember member : members) {
            if (!inTheRun(member)) {
                continue;
            }
            Character agent = ports.agent(member.agentId());
            if (agent == null) {
                memberOut(host, member, AgentBossRunHost.Out.GONE);
            } else if (!agent.isAlive()) {
                if (member.died()) {
                    // its bag still answers: the vitals line leaves a dead member out and its ammo check stops
                    log.info("Boss run member down: session={} boss={} member={} deaths={}{}", sessionId,
                            definition.key(), member.agentId(), member.deaths(), ports.supplies(agent));
                }
            } else if (agent.getEventInstance() != event) {
                if (member.seenDead() && !cleared
                        && definition.deathMode() == AgentBossRunDefinition.DeathMode.REENTER) {
                    reentering(ports, host, member, nowMs);
                } else {
                    handBack(ports, host, member, true);
                }
            } else {
                member.upAgain();
            }
        }
    }

    /**
     * P12 G6: a member of a re-enter run (Scarga, Horntail) died and came to outside. It stays in the run, REENTERING:
     * it is freed from the battle walls, and the host walks it back to the recruit NPC.
     */
    private void reentering(AgentBossRunPorts ports, AgentBossRunHost.Rule host, AgentBossRunMember member,
                            long nowMs) {
        ports.clearBoundary(member.agentId());
        member.reentering(nowMs);
        log.info("Boss run member re-entering: session={} boss={} member={} deaths={}", sessionId, definition.key(),
                member.agentId(), member.deaths());
        host.reentering(member.seat(definition.key(), sessionId));
    }

    /**
     * P12 G6, each tick: a REENTERING member standing alive in the event again is back inside. One whose runtime is
     * gone is GONE; one whose anchor is no player of the event any more, or still outside after REENTER_TIMEOUT_MS, or
     * the boss already fell, gives up and follows its owner (out DIED). P17: any other one that stands alive beside the
     * recruit NPC (AgentBossDeathWalk walked it there) talks to it, at most every REENTER_TALK_EVERY_MS, as a
     * registered player does to get back in (Aldol: eim.registerPlayer while canJoin is 1).
     */
    private void tickReentering(AgentBossRunPorts ports, AgentBossRunHost.Rule host, long nowMs) {
        for (AgentBossRunMember member : members) {
            if (member.phase() != AgentBossRunMember.Phase.REENTERING) {
                continue;
            }
            Character agent = ports.agent(member.agentId());
            if (agent == null) {
                memberOut(host, member, AgentBossRunHost.Out.GONE);
            } else if (agent.isAlive() && agent.getEventInstance() == event) {
                member.backInside();
                log.info("Boss run member back inside: session={} boss={} member={} map={}", sessionId,
                        definition.key(), member.agentId(), agent.getMapId());
            } else if (cleared || event.getPlayerById(member.anchorId()) == null
                    || nowMs - member.reenteringSinceMs() >= reenterTimeoutMs()) {
                log.info("Boss run member gives up re-entering: session={} boss={} member={} cleared={}", sessionId,
                        definition.key(), member.agentId(), cleared);
                letGo(ports, member);
                memberOut(host, member, AgentBossRunHost.Out.DIED);
            } else {
                talkBackIn(ports, member, agent, nowMs);
            }
        }
    }

    /** P17: a REENTERING member alive beside the recruit NPC talks to it, at most every REENTER_TALK_EVERY_MS. */
    private void talkBackIn(AgentBossRunPorts ports, AgentBossRunMember member, Character agent, long nowMs) {
        if (!agent.isAlive() || agent.getMapId() != definition.recruitMapId() || definition.entryNpcId() <= 0
                || member.reenterTalkedAtMs() > 0 && nowMs - member.reenterTalkedAtMs() < reenterTalkEveryMs()) {
            return;
        }
        Point npc = ports.npcPosition(agent, definition.entryNpcId());
        if (npc == null || !near(agent, npc, exitReachPx())) {
            return;
        }
        member.reenterTalked(nowMs);
        log.info("Boss run member talks back in: session={} boss={} member={} npc={}", sessionId, definition.key(),
                member.agentId(), definition.entryNpcId());
        ports.runNpc(agent, definition.entryNpcId());
    }

    /**
     * The member is out of the event: it died out, left for want of a human or with its anchor, or simply left. A
     * loaded one is freed from the battle walls and let go (see {@link #letGo}); one with no runtime is GONE.
     */
    private void handBack(AgentBossRunPorts ports, AgentBossRunHost.Rule host, AgentBossRunMember member,
                          boolean loaded) {
        if (!loaded) {
            memberOut(host, member, AgentBossRunHost.Out.GONE);
            return;
        }
        int agentId = member.agentId();
        ports.clearBoundary(agentId);
        letGo(ports, member);
        memberOut(host, member, outOf(member));
    }

    /**
     * A follower follows its owner again; a self-led member has no owner to follow, so it is stopped where it stands
     * and the host takes it from there.
     */
    private static void letGo(AgentBossRunPorts ports, AgentBossRunMember member) {
        if (member.kind() == AgentBossRunMember.Kind.SELF_LED) {
            ports.stop(member.agentId());
        } else {
            ports.follow(member.agentId());
        }
    }

    /** How a loaded member left: it died out, it left for the reason it was leaving, or it simply left. */
    private static AgentBossRunHost.Out outOf(AgentBossRunMember member) {
        return member.seenDead() ? AgentBossRunHost.Out.DIED
                : member.leavingFor() != null ? member.leavingFor() : AgentBossRunHost.Out.EXITED;
    }

    /**
     * Decision 9: with no human standing in the event (alive and logged in; one changing maps stands, a dead one or
     * an Agent does not) for HUMAN_GONE_GRACE_MS, every alive member still fighting heads out to wait outside.
     */
    private void watchHumans(AgentBossRunPorts ports, long nowMs) {
        if (standingHumans(ports) > 0) {
            noHumanSinceMs = -1L;
            return;
        }
        if (noHumanSinceMs < 0L) {
            noHumanSinceMs = nowMs;
            log.info("Boss run has no standing human: session={} boss={}", sessionId, definition.key());
        }
        if (nowMs - noHumanSinceMs < humanGoneGraceMs()) {
            return;
        }
        for (AgentBossRunMember member : members) {
            if (member.phase() == AgentBossRunMember.Phase.INSIDE && alive(ports, member)) {
                leave(ports, member, AgentBossRunHost.Out.NO_HUMAN_LEFT, nowMs);
            }
        }
    }

    private int standingHumans(AgentBossRunPorts ports) {
        int standing = 0;
        for (Character player : event.getPlayers()) {
            if (player != null && !ports.headless(player) && player.isAlive() && player.isLoggedinWorld()) {
                standing++;
            }
        }
        return standing;
    }

    /**
     * After the clear a member stays while its anchor (its owner, or a handoff's heir) is in the event. An anchor that
     * walked out is followed at once. One that logged out is waited for HUMAN_GONE_GRACE_MS from the first tick that
     * misses it: its logout leaves the event before companion-ops hands the member to an heir (handTo), and a member
     * already LEAVING could not be handed over. P8: before the clear, for a boss whose owner takes the party out
     * (ownerLeavingTakesParty: Zakum), the same rule walks out each member that follows its owner, OWNER_LEFT. A dead
     * anchor still lying in the event is in it; a member that leads itself (a bench ally) stays while any human stands
     * (watchHumans). The Start registers and warps the members one at a time while this runs on its own timer, so
     * before the clear an anchor not yet seen in the event is waited for until START_GRACE_MS after Start. A member
     * that follows its owner and went down outside (out DIED) waits for its anchor; when the same rule finds that
     * anchor gone before the clear, the host hears it once (AgentBossRunHost.Rule.ownerLeftWhileDown).
     */
    private void followAnchorsOut(AgentBossRunPorts ports, AgentBossRunHost.Rule host, long nowMs) {
        boolean beforeClear = !cleared;
        for (AgentBossRunMember member : members) {
            boolean downOutside = beforeClear && member.downOutside();
            if (!downOutside && (member.phase() != AgentBossRunMember.Phase.INSIDE
                    || beforeClear && member.kind() != AgentBossRunMember.Kind.FOLLOWER)) {
                continue;
            }
            int anchor = member.anchorId();
            if (event.getPlayerById(anchor) != null) {
                member.anchorHere();
                continue;
            }
            if (!downOutside && !alive(ports, member)) {
                continue;
            }
            if (beforeClear && !member.anchorSeen() && nowMs - startedAtMs < startGraceMs()) {
                continue;
            }
            boolean anchorLeft = ports.online(anchor) || nowMs - member.anchorGoneSince(nowMs) >= humanGoneGraceMs();
            // A handTo that landed during this tick has the last word.
            if (!anchorLeft || member.anchorId() != anchor) {
                continue;
            }
            if (!downOutside) {
                leave(ports, member, beforeClear ? AgentBossRunHost.Out.OWNER_LEFT : AgentBossRunHost.Out.EXITED,
                        nowMs);
            } else if (member.ownerLeftWhileDown()) {
                log.info("Boss run member down outside, its anchor left: session={} boss={} member={} anchor={}",
                        sessionId, definition.key(), member.agentId(), anchor);
                host.ownerLeftWhileDown(member.seat(definition.key(), sessionId));
            }
        }
    }

    private static boolean alive(AgentBossRunPorts ports, AgentBossRunMember member) {
        Character agent = ports.agent(member.agentId());
        return agent != null && agent.isAlive();
    }

    /**
     * The member heads out; before the clear the fight drove it, so it is stopped and freed from the battle walls.
     * The exit NPC's script is prepared once, for the first member that will talk to it (see {@link #walkOut}); a boss
     * whose way out is a portal (P14: Neo City) has none.
     */
    private void leave(AgentBossRunPorts ports, AgentBossRunMember member, AgentBossRunHost.Out reason, long nowMs) {
        int agentId = member.agentId();
        if (!cleared) {
            ports.stop(agentId);
            ports.clearBoundary(agentId);
        }
        if ((!cleared || definition.exitNpcAfterClear()) && !exitNpcPrepared && definition.exitNpcId() > 0) {
            exitNpcPrepared = true;
            ports.prepareScripts(definition.exitNpcId());
        }
        member.leaving(reason, nowMs);
        log.info("Boss run member leaving: session={} boss={} member={} reason={} anchor={}", sessionId,
                definition.key(), agentId, reason, member.anchorId());
    }

    /** Walks each leaving member still in the event out by the stock ways; a failed step is logged and retried. */
    private void tickLeaving(AgentBossRunPorts ports, long nowMs) {
        for (AgentBossRunMember member : members) {
            if (member.phase() != AgentBossRunMember.Phase.LEAVING) {
                continue;
            }
            Character agent = ports.agent(member.agentId());
            if (agent == null || !agent.isAlive() || agent.getEventInstance() != event) {
                continue;
            }
            try {
                walkOut(ports, member, agent, nowMs);
            } catch (RuntimeException failure) {
                log.warn("Boss run exit step failed: session={} boss={} member={}", sessionId, definition.key(),
                        member.agentId(), failure);
            }
        }
    }

    /**
     * One step out. In the reward room the member takes the stock exit portal; on the battle map before the clear it
     * talks to the exit NPC, whose yes then warps only the talker to the recruit map. After the clear that NPC warps
     * the whole team into the reward room, so a member still on the battle map then, one on any other map of the
     * event, and one still inside after EXIT_TIMEOUT_MS are moved straight to the recruit map. A boss with no reward
     * room (exitNpcAfterClear, Zakum's Amon) keeps the exit NPC as the way out after the clear too: its yes then still
     * warps only the talker.
     */
    private void walkOut(AgentBossRunPorts ports, AgentBossRunMember member, Character agent, long nowMs) {
        int agentId = member.agentId();
        int mapId = agent.getMapId();
        boolean clearedNow = cleared || event.isEventCleared();
        if (nowMs - member.leavingSinceMs() >= exitTimeoutMs()) {
            moveOut(ports, agent);
        } else if (mapId == definition.clearMapId()) {
            Point portal = ports.portalPosition(agent, definition.rewardExitPortalId());
            if (portal == null) {
                moveOut(ports, agent);
            } else if (near(agent, portal, exitReachPx())) {
                ports.stop(agentId);
                ports.enterPortal(agent, definition.rewardExitPortalId());
            } else {
                ports.navigate(agentId, portal, true);
            }
        } else if (mapId == definition.battleMapId() && (!clearedNow || definition.exitNpcAfterClear())) {
            Point npc = ports.npcPosition(agent, definition.exitNpcId());
            if (npc == null) {
                moveOut(ports, agent);
            } else if (near(agent, npc, exitReachPx())) {
                ports.stop(agentId);
                ports.runNpc(agent, definition.exitNpcId());
            } else {
                ports.navigate(agentId, npc, true);
            }
        } else {
            moveOut(ports, agent);
        }
    }

    private void moveOut(AgentBossRunPorts ports, Character agent) {
        log.info("Boss run member moved out: session={} boss={} member={} from={}", sessionId, definition.key(),
                agent.getId(), agent.getMapId());
        ports.moveOut(agent, definition.recruitMapId());
    }

    private static boolean near(Character agent, Point spot, long reachPx) {
        Point position = agent.getPosition();
        return position != null && position.distanceSq(spot) <= reachPx * reachPx;
    }

    /**
     * A logout handoff: the INSIDE member stays with {@code humanId} (the heir) from now on. False when the Agent is no
     * INSIDE member of this run. After the clear a member whose anchor logged out stays INSIDE for HUMAN_GONE_GRACE_MS
     * (see followAnchorsOut), so this call still finds it. Takes no lock (the phase and the anchor are volatile), so a
     * host thread never waits on a tick; a member that turns LEAVING meanwhile just leaves.
     */
    boolean handTo(int agentId, int humanId) {
        AgentBossRunMember member = member(agentId);
        if (member == null || member.phase() != AgentBossRunMember.Phase.INSIDE) {
            return false;
        }
        int from = member.anchorId();
        member.anchorTo(humanId);
        log.info("Boss run member handed over: session={} boss={} member={} from={} to={}", sessionId,
                definition.key(), agentId, from, humanId);
        return true;
    }

    /**
     * Takes a WAITING or REGISTERED member back before Start (its owner logged out, say): the next registering tick
     * takes it off the entry NPC's list when it is on it and refuses it WITHDRAWN. False once the run left
     * REGISTERING, or when the Agent is no WAITING or REGISTERED member of it. Takes no lock, like handTo, so a host
     * thread never waits on a tick; a member whose sign-up finishes meanwhile is taken off the list on the next tick.
     */
    boolean withdraw(int agentId) {
        AgentBossRunMember member = member(agentId);
        if (phase != Phase.REGISTERING || member == null) {
            return false;
        }
        AgentBossRunMember.Phase memberPhase = member.phase();
        if (memberPhase != AgentBossRunMember.Phase.WAITING && memberPhase != AgentBossRunMember.Phase.REGISTERED) {
            return false;
        }
        member.withdraw();
        log.info("Boss run member withdrawn: session={} boss={} member={} phase={}", sessionId, definition.key(),
                agentId, memberPhase);
        return true;
    }

    /**
     * Adopts a signed-up member the Start took into this expedition's event on the battle map. It is tied to the run
     * again first: an owner's logout before Start let go of its tie, and the list still took it in once it was loaded
     * again. A member the host will not tie again missed the start and is never adopted. Neither is a member its host
     * withdrew (a withdraw that landed just before Start; it misses the start at once), nor a self-led member while
     * its anchor is no player of the event: with nobody of its own in the fight, it misses the start after the grace.
     */
    private void adoptOrMiss(AgentBossRunPorts ports, AgentBossRunHost.Rule host, AgentBossRunMember member,
                             boolean graceOver, long nowMs) {
        int agentId = member.agentId();
        Character agent = ports.agent(agentId);
        EventInstanceManager memberEvent = agent == null ? null : agent.getEventInstance();
        boolean inThisEvent = memberEvent != null
                && (expedition != null ? memberEvent.getExpedition() == expedition : memberEvent == event);
        if (member.withdrawn()) {
            log.info("Boss run adoption refused: session={} boss={} member={} (withdrawn)", sessionId,
                    definition.key(), agentId);
            missedStart(ports, host, member, agent, inThisEvent);
            return;
        }
        boolean anchorIn = member.kind() != AgentBossRunMember.Kind.SELF_LED
                || (inThisEvent && memberEvent.getPlayerById(member.anchorId()) != null);
        if (inThisEvent && anchorIn && agent.getMapId() == definition.battleMapId()) {
            if (!host.seat(member.seat(definition.key(), sessionId))) {
                log.info("Boss run adoption refused: session={} boss={} member={} (tied to another run)", sessionId,
                        definition.key(), agentId);
                memberOut(host, member, AgentBossRunHost.Out.MISSED_START);
                return;
            }
            if (ports.prepareAdoption(agentId, "entering " + definition.key(), nowMs)) {
                if (!anyAdopted) {
                    anyAdopted = true;
                    event = memberEvent;
                    partySize = memberEvent.getPlayerCount();
                }
                fight.adopt(agentId, member.ordinal());
                member.adopted();
                host.entered(member.seat(definition.key(), sessionId));
                log.info("Boss run member inside: session={} boss={} member={} ordinal={}", sessionId,
                        definition.key(), agentId, member.ordinal());
                return;
            }
            log.info("Boss run adoption waits: session={} boss={} member={} (another activity would not stop)",
                    sessionId, definition.key(), agentId);
        }
        if (graceOver) {
            if (inThisEvent && !anchorIn) {
                log.info("Boss run adoption refused: session={} boss={} member={} (anchor {} is not in the event)",
                        sessionId, definition.key(), agentId, member.anchorId());
            }
            missedStart(ports, host, member, agent, inThisEvent);
        }
    }

    /**
     * The member missed the start. A self-led one standing alive in this run's event is moved to the recruit map
     * first, since nobody leads it out of a fight it is not part of; a follower stays with its owner as before, and a
     * dead one is left to the stock respawn.
     */
    private void missedStart(AgentBossRunPorts ports, AgentBossRunHost.Rule host, AgentBossRunMember member,
                             Character agent, boolean inThisEvent) {
        if (member.kind() == AgentBossRunMember.Kind.SELF_LED && inThisEvent && agent.isAlive()) {
            bestEffort("moveOut", member.agentId(), () -> moveOut(ports, agent));
        }
        memberOut(host, member, AgentBossRunHost.Out.MISSED_START);
    }

    /**
     * The boss fell: the members inside stop fighting, and the followers follow their owner again (a self-led member
     * stays stopped); the fight is not ticked after.
     */
    private void clear(AgentBossRunPorts ports) {
        cleared = true;
        for (AgentBossRunMember member : members) {
            if (member.phase() != AgentBossRunMember.Phase.INSIDE) {
                continue;
            }
            int agentId = member.agentId();
            ports.stop(agentId);
            ports.clearBoundary(agentId);
            if (member.kind() == AgentBossRunMember.Kind.FOLLOWER) {
                ports.follow(agentId);
            }
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
            // P12 G5: a stunned, seduced or confused member does what its state makes it do, on its own tick
            if (agent != null && agent.isAlive() && agent.getMapId() == definition.battleMapId()
                    && agent.getEventInstance() == event && !AgentBossDisabledState.disabled(agent)) {
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

    /**
     * Ends the run once: a member still in it is handed back, the run leaves the registry, then the host hears how it
     * went. Only an error ends a run with a member still INSIDE or LEAVING.
     */
    synchronized void end(AgentBossRunPorts ports, AgentBossRunHost.Rule host, String reason, long nowMs) {
        if (phase == Phase.ENDED) {
            return;
        }
        handBackStranded(ports, host);
        boolean started = phase != Phase.REGISTERING;
        phase = Phase.ENDED;
        AgentBossRunRegistry.remove(this);
        if (anyAdopted) {
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

    /**
     * Best effort for a member still INSIDE or LEAVING when the run ends (a failed tick): with no runtime it is GONE;
     * otherwise it is freed from the battle walls, moved to the recruit map while it stands alive in the event (a dead
     * one is left to the respawn), and let go (a follower follows its owner again, a self-led member is stopped). Each
     * step is tried on its own, so a failure stops neither the next step nor the next member; the host hears
     * memberOut before runEnded.
     */
    private void handBackStranded(AgentBossRunPorts ports, AgentBossRunHost.Rule host) {
        for (AgentBossRunMember member : members) {
            if (member.phase() == AgentBossRunMember.Phase.REENTERING) {
                // outside the event already: it follows its owner again (a self-led one is stopped)
                bestEffort("letGo", member.agentId(), () -> letGo(ports, member));
                memberOut(host, member, AgentBossRunHost.Out.DIED);
                continue;
            }
            if (!inTheRun(member)) {
                continue;
            }
            int agentId = member.agentId();
            try {
                Character agent = ports.agent(agentId);
                if (agent == null) {
                    memberOut(host, member, AgentBossRunHost.Out.GONE);
                    continue;
                }
                bestEffort("clearBoundary", agentId, () -> ports.clearBoundary(agentId));
                bestEffort("moveOut", agentId, () -> {
                    if (event != null && agent.isAlive() && agent.getEventInstance() == event) {
                        moveOut(ports, agent);
                    }
                });
                bestEffort(member.kind() == AgentBossRunMember.Kind.SELF_LED ? "stop" : "follow", agentId,
                        () -> letGo(ports, member));
                memberOut(host, member, outOf(member));
            } catch (RuntimeException failure) {
                log.warn("Boss run hand-back failed: session={} boss={} member={}", sessionId, definition.key(),
                        agentId, failure);
            }
        }
    }

    private void bestEffort(String step, int agentId, Runnable call) {
        try {
            call.run();
        } catch (RuntimeException failure) {
            log.warn("Boss run hand-back step failed: session={} boss={} member={} step={}", sessionId,
                    definition.key(), agentId, step, failure);
        }
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

    static long humanGoneGraceMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.expedition.boss.AgentBossRunSession.HUMAN_GONE_GRACE_MS");
    }

    static int exitReachPx() {
        return config.AgentTuning.intValue(
                "server.agents.capabilities.expedition.boss.AgentBossRunSession.EXIT_REACH_PX");
    }

    static long exitTimeoutMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.expedition.boss.AgentBossRunSession.EXIT_TIMEOUT_MS");
    }

    static long reenterTalkEveryMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.expedition.boss.AgentBossRunSession.REENTER_TALK_EVERY_MS");
    }

    static long reenterTimeoutMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.expedition.boss.AgentBossRunSession.REENTER_TIMEOUT_MS");
    }

    static int spotReachPx() {
        return config.AgentTuning.intValue(
                "server.agents.capabilities.expedition.boss.AgentBossRunSession.SPOT_REACH_PX");
    }
}
