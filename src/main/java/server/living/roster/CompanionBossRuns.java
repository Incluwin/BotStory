package server.living.roster;

import client.Character;
import config.LivingWorldConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.boss.AgentBossRunCatalog;
import server.agents.capabilities.expedition.boss.AgentBossRunHost;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.MemberResult;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.Out;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.Refusal;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.RunEnded;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.Seat;
import server.agents.capabilities.expedition.boss.AgentBossRunMember;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.agents.capabilities.expedition.boss.AgentBossRunSession;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.CompanionRunRow;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * The living world's side of boss runs with companions (P7, flow 4; Mu Young's Easy Balrog): the host the Agent
 * layer's boss runs report to (AgentBossRunHost). A companion is tied to its owner's run key
 * ("boss:easy_balrog:<session>:<owner>", Commitments) at its seat, just before it signs up, so bench, swaps, errands
 * and the owner's audits leave it alone until the run ends. One that did not get onto the entry NPC's list, or missed
 * the start, is let go at once and says why; one that went down in the fight stays tied to the run and waits outside
 * for its owner; when no human stands in the fight any more, one of an owner's companions says they regroup outside.
 * As the run ends every commitment to it ends, each companion that went in gets a record (companion_performance,
 * activity the boss's key), the potion line is logged, and a cleared run gets one gg. The owner's part of a run is
 * kept in memory here (CompanionBossRun); the audit lets go of one whose run vanished without an end. It writes no
 * roster state. Also the Easy Balrog fight's server-authority rule (EasyBalrogEncounterService) and, by key, who
 * agrees to a companion seeing a run through once its owner logs off in it (InstanceLogoutHandoff).
 */
public final class CompanionBossRuns implements AgentBossRunHost.Rule, InstanceLogoutHandoff.Delegation {
    private static final Logger log = LoggerFactory.getLogger(CompanionBossRuns.class);
    /** The start of every boss run's commitment key (CompanionBossRun.key); a party quest's start with "pq:". */
    static final String KEY_PREFIX = "boss:";

    /** What the living world reads of the Agent layer's open boss runs (AgentBossRunRegistry): memory, any thread. */
    interface OpenRuns {
        /** True while boss run {@code sessionId} is open. */
        boolean open(long sessionId);

        /** True while a member {@code enlistedBy} enlisted in run {@code sessionId} still waits to sign up. */
        boolean waiting(long sessionId, int enlistedBy);

        /**
         * True while boss run {@code sessionId} still takes sign-ups: the run is REGISTERING and its expedition is
         * registering (the leader has not pressed Start).
         */
        boolean registering(long sessionId);

        /** True when a companion of {@code event}'s run signed up for this fight (AgentBossRunRegistry.signedUpIn). */
        boolean signedUpIn(EventInstanceManager event);

        OpenRuns LIVE = new OpenRuns() {
            @Override
            public boolean open(long sessionId) {
                return AgentBossRunRegistry.byId(sessionId) != null;
            }

            @Override
            public boolean waiting(long sessionId, int enlistedBy) {
                AgentBossRunSession session = AgentBossRunRegistry.byId(sessionId);
                if (session == null) {
                    return false;
                }
                for (AgentBossRunMember member : session.members()) {
                    if (member.enlistedBy() == enlistedBy && member.phase() == AgentBossRunMember.Phase.WAITING) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public boolean registering(long sessionId) {
                AgentBossRunSession session = AgentBossRunRegistry.byId(sessionId);
                return session != null && session.phase() == AgentBossRunSession.Phase.REGISTERING
                        && session.expedition().isRegistering();
            }

            @Override
            public boolean signedUpIn(EventInstanceManager event) {
                return AgentBossRunRegistry.signedUpIn(event);
            }
        };
    }

    private final LivingWorld world;
    private final OpenRuns open;
    /** A companion's HP and MP potions ({HP, MP}) as it carries them now; null when it is not online. */
    private final IntFunction<int[]> potions;
    /** Run key -> an owner's part of an open boss run: from its first seat (or report) to the run's end. */
    private final Map<String, CompanionBossRun> runs = new ConcurrentHashMap<>();

    public CompanionBossRuns(LivingWorld world) {
        this(world, OpenRuns.LIVE, CompanionPartyQuestService::potionsOf);
    }

    CompanionBossRuns(LivingWorld world, OpenRuns open, IntFunction<int[]> potions) {
        this.world = world;
        this.open = open;
        this.potions = potions;
    }

    /**
     * Logout delegation by key: a boss run's companion ("boss:") asks {@code boss}, a party quest's ("pq:") and any
     * other asks {@code pq}, as before (InstanceLogoutHandoff holds one delegation).
     */
    public static InstanceLogoutHandoff.Delegation byKey(InstanceLogoutHandoff.Delegation boss,
                                                         InstanceLogoutHandoff.Delegation pq) {
        return (companion, heir, key) -> {
            InstanceLogoutHandoff.Delegation asked = key != null && key.startsWith(KEY_PREFIX) ? boss : pq;
            return asked != null && asked.delegate(companion, heir, key);
        };
    }

    // ---- the host rule (AgentBossRunHost), on the run's tick -------------------------------------

    /**
     * Ties the companion to its owner's run key, from memory and at once (Commitments is synchronized and memory only);
     * false when it is tied to another run (the run refuses it BUSY).
     */
    @Override
    public boolean seat(Seat seat) {
        Commitments commitments = world.commitments();
        if (commitments == null) {
            return false;
        }
        CompanionBossRun run = run(seat);
        return commitments.commit(run.key, run.ownerId, seat.agentCharacterId());
    }

    @Override
    public void registered(Seat seat) {
        world.executor().submit("boss.registered " + seat.agentCharacterId(), () -> signedUp(seat));
    }

    @Override
    public void refused(Seat seat, Refusal refusal) {
        world.executor().submit("boss.refused " + seat.agentCharacterId(), () -> notListed(seat, refusal));
    }

    @Override
    public void memberOut(Seat seat, Out out) {
        world.executor().submit("boss.member_out " + seat.agentCharacterId(), () -> out(seat, out));
    }

    @Override
    public void runEnded(RunEnded ended) {
        world.executor().submit("boss.run_ended " + ended.sessionId(), () -> finish(ended));
    }

    // ---- on companion-ops -----------------------------------------------------------------------

    /**
     * The companion is on the entry NPC's list: its persona and its potions are noted for its record and the potion
     * line, and once none of its owner's companions waits to sign up any more (the list still open), one of them says
     * they are all set.
     */
    private void signedUp(Seat seat) {
        int id = seat.agentCharacterId();
        CompanionBossRun run = run(seat);
        run.signedUp.add(id);
        Persona persona = world.residents().personaOfCharacterId(id);
        if (persona != null) {
            run.personaByCompanion.put(id, persona.id());
        }
        int[] carried = potions.apply(id);
        if (carried != null) {
            run.potionsAtSignUp.put(id, carried);
        }
        allSignedUp(run, id);
    }

    /**
     * The companion did not get onto the list, or the leader took it off: it is let go and says why. When it was the
     * last one of its owner's still waiting and the list is still open, one already on the list says they are all set.
     */
    private void notListed(Seat seat, Refusal refusal) {
        int id = seat.agentCharacterId();
        CompanionBossRun run = runs.get(key(seat));
        if (run != null) {
            run.signedUp.remove(id);
        }
        release(key(seat), id);
        BossRunLines.say(world, bot(id), BossRunLines.refusal(refusal), null, 0);
        if (run != null) {
            allSignedUp(run, 0);
        }
    }

    /**
     * Once per owner, while the entry NPC's list is still open, when none of their companions waits to sign up any
     * more and one is on the list: {@code speaker} (or, when it is 0 or not loaded, another on the list) says they are
     * all signed up. After Start it says nothing: the owner is already on the way into the fight.
     */
    private void allSignedUp(CompanionBossRun run, int speaker) {
        if (run.signedUpSaid || run.signedUp.isEmpty() || !open.registering(run.sessionId)
                || open.waiting(run.sessionId, run.ownerId)) {
            return;
        }
        Character bot = speaker == 0 ? null : bot(speaker);
        for (Iterator<Integer> listed = run.signedUp.iterator(); bot == null && listed.hasNext(); ) {
            bot = bot(listed.next());
        }
        if (bot != null) {
            run.signedUpSaid = true;
            BossRunLines.say(world, bot, BossRunLines.SIGNED_UP, null, 0);
        }
    }

    /**
     * The companion left the run. A missed start lets it go with a line; a death keeps it tied to the run (it waits
     * outside for its owner and follows them out) with a line; no human left is one line per owner and the run's end
     * lets them go; a plain exit says nothing; a runtime that went away (parked at its owner's logout) is let go.
     */
    private void out(Seat seat, Out out) {
        int id = seat.agentCharacterId();
        switch (out) {
            case MISSED_START -> {
                release(key(seat), id);
                BossRunLines.say(world, bot(id), BossRunLines.MISSED, null, 0);
            }
            case DIED -> BossRunLines.say(world, bot(id), BossRunLines.DOWN, null, 0);
            case NO_HUMAN_LEFT -> {
                CompanionBossRun run = run(seat);
                if (!run.regroupSaid) {
                    run.regroupSaid = true;
                    BossRunLines.say(world, bot(id), BossRunLines.REGROUP, null, 0);
                }
            }
            case EXITED -> {
            }
            case GONE -> release(key(seat), id);
        }
    }

    /**
     * The run is over: every owner's commitments to it end (the party changes and walk-outs that waited go through),
     * and each owner's part of it is taken out, once. A run that started then writes one record per companion that
     * went in (an owner's part taken out here: a second end of the same run writes nothing), logs the potion line, and
     * when it cleared each owner online hears one gg.
     */
    void finish(RunEnded ended) {
        Set<Integer> owners = new LinkedHashSet<>();
        for (MemberResult member : ended.members()) {
            owners.add(member.enlistedBy());
        }
        for (CompanionBossRun run : runs.values()) {
            if (run.sessionId == ended.sessionId() && run.bossKey.equals(ended.bossKey())) {
                owners.add(run.ownerId);
            }
        }
        Commitments commitments = world.commitments();
        Map<Integer, CompanionBossRun> taken = new LinkedHashMap<>();
        for (int ownerId : owners) {
            String key = CompanionBossRun.key(ended.bossKey(), ended.sessionId(), ownerId);
            if (commitments != null) {
                commitments.releaseKey(key);
            }
            CompanionBossRun run = runs.remove(key);
            if (run != null) {
                taken.put(ownerId, run);
            }
        }
        if (!ended.started() || taken.isEmpty()) {
            return;
        }
        List<MemberResult> wentIn = ended.members().stream()
                .filter(member -> member.wentIn() && taken.containsKey(member.enlistedBy())).toList();
        record(ended, wentIn, taken);
        logPotions(ended, wentIn, taken);
        if (ended.cleared()) {
            for (CompanionBossRun run : taken.values()) {
                sayGg(run, wentIn);
            }
        }
    }

    /** One companion_performance row per companion that went in, its owner the one who enlisted it. */
    private void record(RunEnded ended, List<MemberResult> wentIn, Map<Integer, CompanionBossRun> taken) {
        int durationS = (int) Math.max(0L, (ended.endedAtMs() - ended.startedAtMs()) / 1000L);
        for (MemberResult member : wentIn) {
            String personaId = personaId(taken.get(member.enlistedBy()), member.agentId());
            if (personaId == null) {
                log.debug("boss run {} member {} has no persona; no record", ended.sessionId(), member.agentId());
                continue;
            }
            world.store().saveCompanionRun(new CompanionRunRow(member.enlistedBy(), member.agentId(), personaId,
                    ended.bossKey(), outcome(member, ended.cleared()), durationS, member.deaths(), ended.partySize()));
        }
    }

    /**
     * A companion's outcome: cleared when it was in the fight as the boss fell (lying dead there too), tapped out when
     * it went down before that (it waited outside while the run went on), or left before a clear the run went on to;
     * else failed with the run (everyone down, the time ran out, the event ended).
     */
    static String outcome(MemberResult member, boolean runCleared) {
        if (member.inAtClear()) {
            return "cleared";
        }
        if (member.out() == Out.DIED || runCleared && (member.out() == Out.EXITED || member.out() == Out.GONE)) {
            return "tapped_out";
        }
        return "failed";
    }

    /** 'PQ potions: session= pq=<boss> outcome= companions=[...]': each companion that went in, at sign-up and now. */
    private void logPotions(RunEnded ended, List<MemberResult> wentIn, Map<Integer, CompanionBossRun> taken) {
        if (wentIn.isEmpty()) {
            return;
        }
        List<String> companions = new ArrayList<>();
        for (MemberResult member : wentIn) {
            int id = member.agentId();
            CompanionBossRun run = taken.get(member.enlistedBy());
            companions.add(CompanionRunRecorder.potionPart(personaId(run, id) + "(" + id + ")",
                    run.potionsAtSignUp.get(id), potions.apply(id)));
        }
        log.info(CompanionRunRecorder.potionLine(Long.toString(ended.sessionId()), ended.bossKey(),
                ended.cleared() ? "cleared" : "failed", companions));
    }

    /** One gg for the owner, when online, from a companion of theirs that went in (one in at the clear first). */
    private void sayGg(CompanionBossRun run, List<MemberResult> wentIn) {
        if (run.ggSaid || world.roster().onlineCharacter(run.ownerId) == null) {
            return;
        }
        List<MemberResult> theirs = wentIn.stream().filter(member -> member.enlistedBy() == run.ownerId)
                .sorted((a, b) -> Boolean.compare(b.inAtClear(), a.inAtClear())).toList();
        for (MemberResult member : theirs) {
            Character bot = bot(member.agentId());
            if (bot != null) {
                run.ggSaid = true;
                BossRunLines.say(world, bot, BossRunLines.GG, null, 0);
                return;
            }
        }
    }

    /**
     * On companion-ops, from the audit: an owner's part of a run whose session is gone without ending it (no runEnded
     * came; the Agent layer holds no run of that id any more) is let go once it has been gone
     * {@link CompanionPartyQuestService#LOST_RUN_AFTER_MS}, with no record.
     */
    public void sweepLostRuns(long nowMs) {
        for (CompanionBossRun run : List.copyOf(runs.values())) {
            if (open.open(run.sessionId)) {
                run.goneSinceMs = 0L;
            } else if (run.goneSinceMs == 0L) {
                run.goneSinceMs = Math.max(1L, nowMs);
            } else if (nowMs - run.goneSinceMs >= CompanionPartyQuestService.LOST_RUN_AFTER_MS
                    && runs.remove(run.key, run)) {
                log.warn("companion boss run {} lost its session; letting its companions go", run.key);
                Commitments commitments = world.commitments();
                if (commitments != null) {
                    commitments.releaseKey(run.key);
                }
            }
        }
    }

    // ---- the fight's server authority and the logout delegation ---------------------------------

    /**
     * Easy Balrog's server-authority rule (EasyBalrogEncounterService), read once as the fight starts on the starting
     * thread, from memory: true while the switch lists easy_balrog, COMPANION_BOSS_SERVER_AUTHORITY is on, and a
     * companion signed up for this very fight is a player of {@code battleMap}'s event. The server then moves Balrog
     * for the whole fight and its attacks reach the companions (the Poison Golem mechanism).
     */
    public boolean forcesServerAuthority(MapleMap battleMap) {
        LivingWorldConfig config = world.config();
        if (battleMap == null || config == null || !config.COMPANION_BOSS_SERVER_AUTHORITY
                || !config.companionBossEnabled(AgentBossRunCatalog.EASY_BALROG.key())) {
            return false;
        }
        return open.signedUpIn(battleMap.getEventInstance());
    }

    /**
     * Decision 8b for a boss run: not agreed. A boss run's companion leaves with its owner at a logout (the park lets
     * it go, and the run counts it gone).
     */
    @Override
    public boolean delegate(Character companion, Character heir, String key) {
        return false;
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static String key(Seat seat) {
        return CompanionBossRun.key(seat.bossKey(), seat.sessionId(), seat.enlistedBy());
    }

    /** The seat's owner's part of its run, kept from the first seat or report of it (memory only, any thread). */
    private CompanionBossRun run(Seat seat) {
        return runs.computeIfAbsent(key(seat),
                key -> new CompanionBossRun(seat.bossKey(), seat.sessionId(), seat.enlistedBy()));
    }

    /** The companion's commitment ends when it is tied to {@code key}; another run's is left alone. */
    private void release(String key, int characterId) {
        Commitments commitments = world.commitments();
        if (commitments != null && key.equals(commitments.keyOf(characterId))) {
            commitments.releaseMember(characterId);
        }
    }

    private String personaId(CompanionBossRun run, int characterId) {
        String known = run == null ? null : run.personaByCompanion.get(characterId);
        if (known != null) {
            return known;
        }
        Persona persona = world.residents().personaOfCharacterId(characterId);
        return persona == null ? null : persona.id();
    }

    private static Character bot(int characterId) {
        return AgentRuntimeIdentityRuntime.bot(AgentRuntimeRegistry.findByAgentCharacterId(characterId));
    }
}
