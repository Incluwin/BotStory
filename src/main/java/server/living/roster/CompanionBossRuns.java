package server.living.roster;

import client.Character;
import client.Client;
import config.LivingWorldConfig;
import net.server.channel.Channel;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.boss.AgentBossRunCatalog;
import server.agents.capabilities.expedition.boss.AgentBossRunDefinition;
import server.agents.capabilities.expedition.boss.AgentBossRunHost;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.MemberResult;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.Out;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.Refusal;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.RunEnded;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.Seat;
import server.agents.capabilities.expedition.boss.AgentBossRunMember;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.agents.capabilities.expedition.boss.AgentBossRunService;
import server.agents.capabilities.expedition.boss.AgentBossRunService.Enlisted;
import server.agents.capabilities.expedition.boss.AgentBossRunSession;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.expeditions.Expedition;
import server.integration.AgentPresence;
import server.life.Monster;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.CompanionRunRow;
import server.maps.MapleMap;

import java.awt.Point;
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
 *
 * <p>How a run starts: the entry NPC (Mu Young) offers an owner who creates or joins the expedition "My party will
 * register too.", that pick enlists the owner's party companions in the Agent layer's run, and each one still waiting
 * to sign up walks to its own spot beside her (the follow guard asks {@link #waitsAt}).</p>
 *
 * <p>P7 Part B: an owner's bench allies (CompanionBossAllies) are members of the same run that lead themselves. Her
 * window offers an owner on her list "Call allies from my bench..." ({@link #alliesOffered}) and a picker of their
 * benched companions ({@link #allyPicker}, {@link #allyCall}). They walk to their spot ({@link #standAt}), each report
 * about one goes to them first, they never say a party companion's party line where no human hears it (the signed-up
 * line, gg), and the run's end and the audit send them home. With COMPANION_BOSS_OWNERS_MAX above 1, owners up to it
 * share one expedition ({@link #ownerFits}), each signing up no more than their share of its seats, and no companion
 * takes one of the seats kept free for players at its turn ({@link #roomFor}); an owner who logs
 * off leaves their allies' fight to a human still in it ({@link #ownerLoggedOut}). A monster an ally tops the damage
 * on drops for the human it fights for ({@link #dropOwner}).</p>
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

        /**
         * True when the open run for {@code expedition} holds a companion another owner than {@code ownerId} enlisted
         * (Part A: one owner's party companions per expedition).
         */
        boolean heldByAnother(Expedition expedition, int ownerId);

        /** True while the Agent is a member of an open boss run, in any phase. */
        boolean inRun(int agentId);

        /** The member's ordinal in its run while it waits to sign up and stays with {@code anchorId}; else -1. */
        int waitingOrdinal(int agentId, int anchorId);

        /** Enlists the owner's companions into the run for {@code expedition} (AgentBossRunService.enlist). */
        Enlisted enlist(Character owner, AgentBossRunDefinition definition, Expedition expedition,
                        List<Integer> agentIds, long nowMs);

        /** True while boss run {@code sessionId}'s fight is on, or past its clear (FIGHTING, POST_CLEAR). */
        boolean underway(long sessionId);

        /** True while the Agent is a member of run {@code sessionId} in the fight: not down outside, leaving or out. */
        boolean inside(long sessionId, int agentId);

        /**
         * The logout handoff (AgentBossRunService.handTo): the member stays with {@code heirId} instead of its enlister
         * from now on. False when it is no member of run {@code sessionId} in the fight any more.
         */
        boolean handTo(long sessionId, int agentId, int heirId);

        /**
         * Enlists the owner's bench allies into the run for {@code expedition} as members that lead themselves
         * (AgentBossRunService.enlist, SELF_LED). Refuses them all unless implemented.
         */
        default Enlisted enlistSelfLed(Character owner, AgentBossRunDefinition definition, Expedition expedition,
                                       List<Integer> agentIds, long nowMs) {
            return new Enlisted(0L, List.of(), agentIds);
        }

        /** Takes a WAITING or REGISTERED member of run {@code sessionId} back before Start (AgentBossRunService). */
        default boolean withdraw(long sessionId, int agentId) {
            return false;
        }

        /** The Agent's phase as a member of run {@code sessionId}; null when it is none (or the run is gone). */
        default AgentBossRunMember.Phase phaseOf(long sessionId, int agentId) {
            return null;
        }

        /**
         * The list of {@code expedition} as the seat share reads it ({@link #seatsLeft}) for {@code ownerId}; unless
         * implemented, everyone listed is a human and the owner signs up alone.
         */
        default Seats seats(Expedition expedition, int ownerId) {
            return new Seats(expedition == null ? 0 : expedition.getMembers().size(), 0, 1, 0);
        }

        /** The member's ordinal in run {@code sessionId} (its enlist order, its spot beside the entry NPC); else -1. */
        default int ordinalOf(long sessionId, int agentId) {
            return -1;
        }

        /**
         * P7 Part B: how many owners other than {@code ownerId} enlisted companions in the open run for {@code
         * expedition} (counted as {@link #heldByAnother} counts them: every member, in any phase). Unless implemented,
         * 1 when {@link #heldByAnother}, else 0.
         */
        default int otherOwners(Expedition expedition, int ownerId) {
            return heldByAnother(expedition, ownerId) ? 1 : 0;
        }

        /**
         * P7 Part B: the human the Agent stays with as a member of the open run for {@code expedition}, in any phase:
         * its enlister, or its heir since a logout handoff (AgentBossRunMember.anchorId). 0 when it is no member of
         * that run, or none is open for it; unless implemented, 0.
         */
        default int anchorIn(Expedition expedition, int agentId) {
            return 0;
        }

        /**
         * P7 Part B: true while boss run {@code sessionId} is past its clear (POST_CLEAR: its boss fell). Read on the
         * run's tick as it hands a member back: the run leaves the registry only after its last hand-back, so this
         * still finds it. Unless implemented, false.
         */
        default boolean cleared(long sessionId) {
            return false;
        }

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

            @Override
            public boolean heldByAnother(Expedition expedition, int ownerId) {
                AgentBossRunSession session = AgentBossRunRegistry.forExpedition(expedition);
                if (session == null) {
                    return false;
                }
                for (AgentBossRunMember member : session.members()) {
                    if (member.enlistedBy() != ownerId) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public boolean inRun(int agentId) {
                return AgentBossRunRegistry.forMember(agentId) != null;
            }

            @Override
            public int waitingOrdinal(int agentId, int anchorId) {
                int ordinal = AgentBossRunRegistry.waitingOrdinal(agentId);
                AgentBossRunSession session = ordinal < 0 ? null : AgentBossRunRegistry.forMember(agentId);
                AgentBossRunMember member = session == null ? null : session.member(agentId);
                return member != null && member.anchorId() == anchorId ? ordinal : -1;
            }

            @Override
            public Enlisted enlist(Character owner, AgentBossRunDefinition definition, Expedition expedition,
                                   List<Integer> agentIds, long nowMs) {
                return AgentBossRunService.enlist(owner, definition, expedition, agentIds, nowMs);
            }

            @Override
            public boolean underway(long sessionId) {
                AgentBossRunSession session = AgentBossRunRegistry.byId(sessionId);
                AgentBossRunSession.Phase phase = session == null ? null : session.phase();
                return phase == AgentBossRunSession.Phase.FIGHTING || phase == AgentBossRunSession.Phase.POST_CLEAR;
            }

            @Override
            public boolean inside(long sessionId, int agentId) {
                AgentBossRunSession session = AgentBossRunRegistry.byId(sessionId);
                AgentBossRunMember member = session == null ? null : session.member(agentId);
                return member != null && member.phase() == AgentBossRunMember.Phase.INSIDE;
            }

            @Override
            public boolean handTo(long sessionId, int agentId, int heirId) {
                return AgentBossRunService.handTo(sessionId, agentId, heirId);
            }

            @Override
            public Enlisted enlistSelfLed(Character owner, AgentBossRunDefinition definition, Expedition expedition,
                                          List<Integer> agentIds, long nowMs) {
                return AgentBossRunService.enlist(owner, definition, expedition, agentIds, nowMs,
                        AgentBossRunMember.Kind.SELF_LED);
            }

            @Override
            public boolean withdraw(long sessionId, int agentId) {
                return AgentBossRunService.withdraw(sessionId, agentId);
            }

            @Override
            public AgentBossRunMember.Phase phaseOf(long sessionId, int agentId) {
                AgentBossRunSession session = AgentBossRunRegistry.byId(sessionId);
                AgentBossRunMember member = session == null ? null : session.member(agentId);
                return member == null ? null : member.phase();
            }

            @Override
            public Seats seats(Expedition expedition, int ownerId) {
                AgentBossRunSession session = AgentBossRunRegistry.forExpedition(expedition);
                Set<Integer> owners = new LinkedHashSet<>(List.of(ownerId));
                Set<Integer> listed = new LinkedHashSet<>();
                int bots = 0;
                int held = 0;
                for (AgentBossRunMember member : session == null ? List.<AgentBossRunMember>of() : session.members()) {
                    AgentBossRunMember.Phase phase = member.phase();
                    if (phase != AgentBossRunMember.Phase.WAITING && phase != AgentBossRunMember.Phase.REGISTERED) {
                        continue;
                    }
                    bots++;
                    held += member.enlistedBy() == ownerId ? 1 : 0;
                    owners.add(member.enlistedBy());
                    if (phase == AgentBossRunMember.Phase.REGISTERED) {
                        listed.add(member.agentId());
                    }
                }
                int humans = 0;
                for (int id : expedition == null ? Set.<Integer>of() : expedition.getMembers().keySet()) {
                    humans += listed.contains(id) ? 0 : 1;
                }
                return new Seats(humans, bots, owners.size(), held);
            }

            @Override
            public int ordinalOf(long sessionId, int agentId) {
                AgentBossRunSession session = AgentBossRunRegistry.byId(sessionId);
                AgentBossRunMember member = session == null ? null : session.member(agentId);
                return member == null ? -1 : member.ordinal();
            }

            @Override
            public int otherOwners(Expedition expedition, int ownerId) {
                AgentBossRunSession session = AgentBossRunRegistry.forExpedition(expedition);
                Set<Integer> others = new LinkedHashSet<>();
                for (AgentBossRunMember member : session == null ? List.<AgentBossRunMember>of() : session.members()) {
                    if (member.enlistedBy() != ownerId) {
                        others.add(member.enlistedBy());
                    }
                }
                return others.size();
            }

            @Override
            public int anchorIn(Expedition expedition, int agentId) {
                AgentBossRunSession session = AgentBossRunRegistry.forExpedition(expedition);
                AgentBossRunMember member = session == null ? null : session.member(agentId);
                return member == null ? 0 : member.anchorId();
            }

            @Override
            public boolean cleared(long sessionId) {
                AgentBossRunSession session = AgentBossRunRegistry.byId(sessionId);
                return session != null && session.phase() == AgentBossRunSession.Phase.POST_CLEAR;
            }
        };
    }

    /**
     * An expedition list as the seat share reads it: the players on it who are not companions, the companions on it or
     * still waiting to sign up, the owners signing companions up (the asker included), and the asker's own of those.
     */
    record Seats(int humansListed, int botsListedOrPending, int owners, int held) { }

    /** What signing up asks of a companion's own potion duty (AgentSupplyReadiness): memory only, any thread. */
    interface Supplies {
        /** True while the companion is on a potion trip: gone to a shop, or on its way back from one. */
        boolean restocking(int agentId, long nowMs);

        /**
         * A companion short of {@code bossKey}'s potion and ammo standards (AgentSupplyReadiness.BOSS_STANDARDS,
         * AMMO_STANDARDS) looks at its supplies on its next tick instead of its next check, so one that can reach a
         * shop sets off before it signs up.
         */
        void checkIfShort(int agentId, String bossKey);

        Supplies LIVE = new Supplies() {
            @Override
            public boolean restocking(int agentId, long nowMs) {
                return AgentSupplyReadiness.status(AgentRuntimeRegistry.findByAgentCharacterId(agentId), nowMs)
                        .restocking();
            }

            @Override
            public void checkIfShort(int agentId, String bossKey) {
                AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(agentId);
                Character bot = AgentRuntimeIdentityRuntime.bot(entry);
                if (bot != null && !AgentSupplyReadiness.shortfall(bot,
                        AgentSupplyReadiness.Activity.partyQuest(bossKey)).none()) {
                    AgentSupplyReadiness.checkSoon(entry);
                }
            }
        };
    }

    /** Mu Young's answer when the owner picks "My party will register too." and companions can sign up. */
    public static final String SIGN_UP_REPLY = "Very well. Have them come and speak with me before time runs out.";
    /** Her answer when none of the owner's companions can sign up now (or the living world is not running). */
    public static final String NONE_CAN_SIGN_UP = "None of your companions can sign up right now.";
    /** P7 Part B: her answer when none of the owner's benched companions can be called (or no living world runs). */
    public static final String NO_ALLY_CAN_COME = "None of your companions on the bench can come right now.";
    /** The owner's own party companions in one run (Part A): a full party is the owner and five. */
    static final int MOST_PER_OWNER = CompanionSlots.PARTY_CAP - 1;

    private final LivingWorld world;
    private final OpenRuns open;
    /** A companion's HP and MP potions ({HP, MP}) as it carries them now; null when it is not online. */
    private final IntFunction<int[]> potions;
    private final Supplies supplies;
    /** Run key -> an owner's part of an open boss run: from its enlist (or first seat or report) to the run's end. */
    private final Map<String, CompanionBossRun> runs;
    /** P7 Part B: the owners' bench allies, each on its trip from its call to its unload. */
    private final CompanionBossAllies allies;
    /** P8: the companions' daily boss entries (Zakum), shared with the bench allies' calls. */
    private final CompanionBossQuota quota;

    /** What the owner can sign up at a boss's entry NPC now: the expedition and their companions, in party order. */
    private record Offer(AgentBossRunDefinition definition, Expedition expedition, List<Integer> companions) { }

    public CompanionBossRuns(LivingWorld world) {
        this(world, OpenRuns.LIVE, CompanionPartyQuestService::potionsOf);
    }

    CompanionBossRuns(LivingWorld world, OpenRuns open, IntFunction<int[]> potions) {
        this(world, open, potions, Supplies.LIVE);
    }

    CompanionBossRuns(LivingWorld world, OpenRuns open, IntFunction<int[]> potions, Supplies supplies) {
        this.world = world;
        this.open = open;
        this.potions = potions;
        this.supplies = supplies;
        this.runs = new ConcurrentHashMap<>();
        this.quota = new CompanionBossQuota(open);
        this.allies = new CompanionBossAllies(world, open, runs, quota);
    }

    /**
     * The bench allies a test builds (their game and clock faked) over {@code runs}, the map they keep lines in; the
     * daily entries are theirs ({@link CompanionBossAllies#quota}).
     */
    CompanionBossRuns(LivingWorld world, OpenRuns open, IntFunction<int[]> potions, Supplies supplies,
                      Map<String, CompanionBossRun> runs, CompanionBossAllies allies) {
        this.world = world;
        this.open = open;
        this.potions = potions;
        this.supplies = supplies;
        this.runs = runs;
        this.allies = allies;
        this.quota = allies.quota();
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

    // ---- the entry NPC's offer (I/O), the enlist (companion-ops) and the walk up (Agent tick) ----

    /**
     * Mu Young's Easy window for {@code clicker} (1061014.js, on their I/O thread, from memory): true when she offers
     * "My party will register too.". The switch lists the boss; the clicker is a human with companions; the channel's
     * expedition of the boss takes sign-ups and a human leads it; the owners rule lets the clicker in ({@link
     * #ownerFits}: in Part A, no other owner's companions are on it); and at least one of the clicker's companions can
     * sign up ({@link #signable}). An Agent signing itself up headless never sees it: its window stays the stock one.
     */
    public boolean offers(Character clicker, String bossKey) {
        return offer(clicker, bossKey, System.currentTimeMillis()) != null;
    }

    /**
     * The owner picked "My party will register too." (her script, on their I/O thread): her answer. When companions of
     * theirs can sign up, enlisting them is handed to companion-ops ({@link #registerParty}).
     */
    public String registerFromNpc(Character clicker, String bossKey) {
        Offer offer = offer(clicker, bossKey, System.currentTimeMillis());
        if (offer == null) {
            return NONE_CAN_SIGN_UP;
        }
        int ownerId = clicker.getId();
        String key = offer.definition().key();
        world.executor().submit("boss.register " + ownerId, () -> registerParty(ownerId, key));
        return SIGN_UP_REPLY;
    }

    /**
     * On companion-ops: the owner's companions that can sign up, asked again (five at most, in party order), are
     * enlisted in the run for the expedition, the owner being on her list (they joined it or lead it). Each walks up to
     * her and signs up through her own window on the run's tick; one short of the boss's potion standard looks at its
     * potions at once, so a shop trip comes first. The owner's part of the run is kept from here on. P8: for a boss
     * with a daily entry log (Zakum), one with no entry left today says so and is not enlisted (CompanionBossQuota).
     */
    void registerParty(int ownerId, String bossKey) {
        long now = System.currentTimeMillis();
        Character owner = world.roster().onlineCharacter(ownerId);
        Offer offer = owner == null ? null : offer(owner, bossKey, now);
        if (offer == null || !offer.expedition().contains(owner)) {
            log.info("boss sign-up {} for owner {}: nobody to enlist", bossKey, ownerId);
            return;
        }
        String key = offer.definition().key();
        List<Integer> admitted = new ArrayList<>();
        for (int id : offer.companions()) {
            if (quota.admit(offer.definition(), offer.expedition(), id)) {
                admitted.add(id);
            } else {
                BossRunLines.say(world, bot(id), BossRunLines.QUOTA, null, 0);
            }
        }
        if (admitted.isEmpty()) {
            log.info("boss sign-up {} for owner {}: nobody to enlist, no entry left today", key, ownerId);
            return;
        }
        Enlisted enlisted = open.enlist(owner, offer.definition(), offer.expedition(), admitted, now);
        quota.enlisted(offer.expedition(), enlisted, admitted);
        if (enlisted.sessionId() == 0L || enlisted.accepted().isEmpty()) {
            log.info("boss sign-up {} for owner {}: nobody enlisted, refused {}", key, ownerId, enlisted.refused());
            return;
        }
        CompanionBossRun run = new CompanionBossRun(key, enlisted.sessionId(), ownerId);
        runs.putIfAbsent(run.key, run); // a seat on the run's tick may have made it already
        for (int id : enlisted.accepted()) {
            supplies.checkIfShort(id, key);
        }
    }

    /**
     * On the Agent's tick, from memory: where a companion still waiting to sign up stands while the human it stays
     * with is in the entry NPC's room: its own spot beside her (BossEntrance.spot, by its ordinal in the run), so it
     * walks up as a player would. Null for every other Agent (the fast path: nobody in any run waits), once it is on
     * her list, and while that human is anywhere else.
     */
    public Point waitsAt(int agentCharacterId, Character anchor) {
        int ordinal = anchor == null ? -1 : open.waitingOrdinal(agentCharacterId, anchor.getId());
        BossEntrance entrance = ordinal < 0 ? null : BossEntrance.atRecruitMap(anchor.getMapId());
        return entrance == null ? null : entrance.spot(anchor.getMap(), ordinal);
    }

    /** What {@code clicker} can sign up at {@code bossKey}'s entry NPC now; null: nothing (see {@link #offers}). */
    private Offer offer(Character clicker, String bossKey, long nowMs) {
        AgentBossRunDefinition definition = AgentBossRunCatalog.of(bossKey);
        LivingWorldConfig config = world.config();
        if (clicker == null || definition == null || config == null || !config.companionBossEnabled(definition.key())
                || AgentPresence.isAgent(clicker) || !world.roster().hasCompanions(clicker)) {
            return null;
        }
        Expedition expedition = expeditionOf(clicker, definition);
        Character leader = expedition == null ? null : expedition.getLeader();
        if (leader == null || !expedition.isRegistering() || AgentPresence.isAgent(leader)
                || !ownerFits(config, open, expedition, clicker.getId())) {
            return null;
        }
        List<Integer> companions = signable(clicker, definition, expedition, nowMs);
        return companions.isEmpty() ? null : new Offer(definition, expedition, companions);
    }

    /**
     * The owners rule at a boss's entry NPC (memory, any thread), for her offer and her bench picker alike: with
     * COMPANION_BOSS_OWNERS_MAX 1, Part A's one-owner rule (no other owner's companions in the open run for {@code
     * expedition}); above 1 (P7 Part B), fewer other owners' companions in it than that.
     */
    static boolean ownerFits(LivingWorldConfig config, OpenRuns open, Expedition expedition, int ownerId) {
        int most = config.COMPANION_BOSS_OWNERS_MAX;
        return most <= 1 ? !open.heldByAnother(expedition, ownerId) : open.otherOwners(expedition, ownerId) < most;
    }

    /**
     * The owner's companions that can sign up, in party order, {@link #MOST_PER_OWNER} at most: bots of the owner's
     * party that are the owner's own and follow them, loaded, tied to no run (a commitment or a boss run), not kept
     * off follow (on their way, held, away), and on the entry NPC's map on the owner's channel, or gone to the store
     * for potions (they sign up once back). With more than one owner allowed (P7 Part B), no more than the owner's
     * share of {@code expedition}'s seats left (CompanionBossAllies.seatsLeft).
     */
    private List<Integer> signable(Character owner, AgentBossRunDefinition definition, Expedition expedition,
                                   long nowMs) {
        Party party = owner.getParty();
        Commitments commitments = world.commitments();
        if (party == null || commitments == null) {
            return List.of();
        }
        int most = world.config().COMPANION_BOSS_OWNERS_MAX > 1
                ? Math.min(MOST_PER_OWNER, allies.seatsLeft(owner.getId(), expedition)) : MOST_PER_OWNER;
        RosterService roster = world.roster();
        List<Integer> ids = new ArrayList<>();
        for (PartyCharacter member : party.getMembers()) {
            int id = member.getId();
            Persona persona = member.isBot() ? world.residents().personaOfCharacterId(id) : null;
            if (persona == null || roster.cachedOwnerOf(persona.id()) != owner.getId()
                    || AgentRuntimeRegistry.leaderIdForAgentCharacter(id) != owner.getId()
                    || commitments.isCommitted(id) || roster.keepsOffFollow(persona.id()) || open.inRun(id)) {
                continue;
            }
            Character bot = bot(id);
            boolean here = bot != null && bot.getMapId() == definition.recruitMapId()
                    && RosterService.sameChannel(owner, bot);
            if ((here || bot != null && supplies.restocking(id, nowMs)) && ids.size() < most) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * P7 Part B, pure: the seats of an expedition list of {@code maxSize} that one owner's companions may still take
     * while {@code owners} owners (the asker included; 0 counts as the asker alone) sign companions up. {@code reserve}
     * seats (HUMAN_SEATS) stay free for players. What the humans listed leave of the rest is split evenly,
     * floor((maxSize - reserve - humansListed) / owners) each, less the {@code held} seats of this owner's companions
     * (listed or on their way), and never more than the list's room after every bot listed or on its way. Never < 0.
     */
    static int seatsLeft(int maxSize, int reserve, int humansListed, int botsListedOrPending, int owners, int held) {
        int free = maxSize - reserve - humansListed;
        int share = Math.floorDiv(free, Math.max(1, owners));
        return Math.max(0, Math.min(share - held, free - botsListedOrPending));
    }

    /**
     * P7 Part B, Mu Young's Easy window for {@code clicker} (1061014.js, on their I/O thread, memory and the crew
     * cache): true when she offers "Call allies from my bench..." (CompanionBossAllies.offers).
     */
    public boolean alliesOffered(Character clicker, String bossKey) {
        return allies.offers(clicker, bossKey);
    }

    /**
     * P7 Part B, the owner picked "Call allies from my bench...": her bench picker's text (CompanionBossAllies), or
     * null when there is no one to show (her script then says {@link #NO_ALLY_CAN_COME}).
     */
    public String allyPicker(Character clicker, String bossKey) {
        return allies.pickerWindow(clicker, bossKey, System.currentTimeMillis());
    }

    /**
     * P7 Part B, the owner picked {@code selection} in her bench picker: whom she sent word to (their calls handed to
     * companion-ops) and the picker again; why she sent word to nobody, then the picker, for a shown choice she can no
     * longer send word to; the picker as it is now for a stale pick; null as {@link #allyPicker}.
     */
    public String allyCall(Character clicker, String bossKey, int selection) {
        return allies.callFromNpc(clicker, bossKey, selection, System.currentTimeMillis());
    }

    /**
     * P7 Part B, on the thread that landed a kill, from memory (LivingWorldHooks.bossDropOwner from Monster.killBy):
     * who gets a monster's drops and mesos, {@code candidate} (an Agent) being its stock owner. A bench ally's go to
     * the human it fights for (CompanionBossAllies.dropOwner); {@code candidate} for anyone else.
     */
    public Character dropOwner(Monster monster, Character candidate) {
        return allies.dropOwner(monster, candidate);
    }

    /**
     * P7 Part B, from memory, any thread: true while a bench ally's trip to a boss run holds {@code personaId}
     * (CompanionBossAllies), from its call to its unload; bench duty and the bench ledger leave it alone meanwhile.
     */
    public boolean allyOnTrip(String personaId) {
        return allies.onTrip(personaId);
    }

    /**
     * P7 Part B, any thread (/runtime/budget): the most EXPEDITION leases held at once since the living world started,
     * raised as a bench ally's call takes one (CompanionBossAllies); it never falls.
     */
    public int expeditionPeak() {
        return allies.peak();
    }

    /**
     * P7 Part B, on companion-ops as the living world stops (LivingWorld.stop, queued before the bench slices end):
     * every bench ally goes at once, saying nothing (CompanionBossAllies.stopAll).
     */
    public void stopAllies() {
        allies.stopAll();
    }

    /** The expedition of {@code definition}'s type on the clicker's channel (as her script reads it), or null. */
    static Expedition expeditionOf(Character clicker, AgentBossRunDefinition definition) {
        Client client = clicker.getClient();
        Channel channel = client == null ? null : client.getChannelServer();
        return channel == null ? null : channel.getExpedition(definition.type());
    }

    // ---- potions at the entrance (decision 7) ----------------------------------------------------

    /**
     * The boss whose potion standard (AgentSupplyReadiness.BOSS_STANDARDS) a companion keeps on {@code mapId}: the
     * boss's key on its entry NPC's room (easy_balrog in Mu Young's, 105100100) while the switch lists it; null
     * anywhere else or with the switch off. Memory only, any thread (CompanionRestock.standing asks it).
     */
    String restockKeyAt(int mapId) {
        BossEntrance entrance = BossEntrance.atRecruitMap(mapId);
        LivingWorldConfig config = world.config();
        String bossKey = entrance == null ? null : entrance.definition().key();
        return bossKey == null || config == null || !config.companionBossEnabled(bossKey) ? null : bossKey;
    }

    /**
     * True on a boss's entry NPC room whose entrance holds a companion's potion trips there
     * (BossEntrance.holdsTripsAtEntry: Adobis's, 211042400, for Zakum, where no shop is in reach and no route leads back
     * in) while the switch lists that boss ({@link #restockKeyAt}); false anywhere else, in Mu Young's room and with the
     * switch off. Memory only, any thread (CompanionRestock.restocksFrom asks it, P8).
     */
    boolean holdsTripsAt(int mapId) {
        BossEntrance entrance = BossEntrance.atRecruitMap(mapId);
        return entrance != null && entrance.holdsTripsAtEntry() && restockKeyAt(mapId) != null;
    }

    /**
     * On companion-ops: the owner came into a boss's entry NPC room ({@code mapId}, RosterService.ownerEnteredMap).
     * Each companion of theirs in their party that is short of that boss's potion standard looks at its potions on its
     * next tick instead of its next check, so one that can reach a shop sets off (and says when it will be back), and
     * one that cannot says there is no shop out here, before it is asked to sign up. In a room that holds its trips
     * ({@link #holdsTripsAt}: Adobis's, P8) none can set off, so its own no-shop refusal never comes: the readiness rule
     * has each one short of the boss's standard say so at once instead (CompanionRestock.bossGate). First, a bench ally
     * of theirs down from the fight and waiting there says goodbye and goes back to its bench duty (CompanionBossAllies).
     */
    void ownerArrived(int ownerId, int mapId) {
        allies.ownerArrived(ownerId, mapId);
        String bossKey = restockKeyAt(mapId);
        if (bossKey != null && holdsTripsAt(mapId)) {
            CompanionRestock restock = world.restock();
            if (restock != null) {
                restock.bossGate(ownerId, mapId, AgentSupplyReadiness.Activity.partyQuest(bossKey));
            }
            return;
        }
        Character owner = bossKey == null ? null : world.roster().onlineCharacter(ownerId);
        Party party = owner == null ? null : owner.getParty();
        if (party == null) {
            return;
        }
        for (PartyCharacter member : party.getMembers()) {
            int id = member.getId();
            Persona persona = member.isBot() ? world.residents().personaOfCharacterId(id) : null;
            if (persona != null && world.roster().cachedOwnerOf(persona.id()) == ownerId && bot(id) != null) {
                supplies.checkIfShort(id, bossKey);
            }
        }
    }

    // ---- potions on the way there (fix L7: no shop is in reach of the entrance) --------------------

    /**
     * The boss whose approach map with a store {@code mapId} is (BossEntrance.approachMapId: Ant Tunnel Park,
     * 105070001, on the walk down to Easy Balrog) while the switch lists it; null anywhere else or with the switch off.
     * Memory only, any thread.
     */
    String approachKeyAt(int mapId) {
        BossEntrance entrance = BossEntrance.atApproachMap(mapId);
        LivingWorldConfig config = world.config();
        String bossKey = entrance == null ? null : entrance.definition().key();
        return bossKey == null || config == null || !config.companionBossEnabled(bossKey) ? null : bossKey;
    }

    /**
     * The boss whose potion standard companion {@code bot} keeps on its way there: the boss's key while it stands on
     * that boss's approach map with the switch listing the boss ({@link #approachKeyAt}), it is at or above the boss's
     * level (its expedition's minimum: 50 for Easy Balrog) and it is in its owner's party; null otherwise. The rule
     * opens that map to its trip (CompanionRestock.restocksOnField). Memory only, any thread.
     */
    String approachKeyFor(Character bot, int ownerId) {
        String bossKey = bot == null ? null : approachKeyAt(bot.getMapId());
        AgentBossRunDefinition definition = bossKey == null ? null : AgentBossRunCatalog.of(bossKey);
        if (definition == null || bot.getLevel() < definition.type().getMinLevel()) {
            return null;
        }
        Party party = bot.getParty();
        return party != null && party.getMemberById(ownerId) != null ? bossKey : null;
    }

    /**
     * On companion-ops: the owner came into a boss's approach map with a store ({@code mapId},
     * RosterService.ownerEnteredMap). Each companion of theirs in their party at or above the boss's level that is
     * short of the boss's potion standard looks at its potions on its next tick instead of its next check, so it shops
     * at that store (and says when it will be back) before the party walks on.
     */
    void ownerApproached(int ownerId, int mapId) {
        String bossKey = approachKeyAt(mapId);
        AgentBossRunDefinition definition = bossKey == null ? null : AgentBossRunCatalog.of(bossKey);
        Character owner = definition == null ? null : world.roster().onlineCharacter(ownerId);
        Party party = owner == null ? null : owner.getParty();
        if (party == null) {
            return;
        }
        for (PartyCharacter member : party.getMembers()) {
            int id = member.getId();
            Persona persona = member.isBot() ? world.residents().personaOfCharacterId(id) : null;
            Character bot = persona == null || world.roster().cachedOwnerOf(persona.id()) != ownerId ? null : bot(id);
            if (bot != null && bot.getLevel() >= definition.type().getMinLevel()) {
                supplies.checkIfShort(id, bossKey);
            }
        }
    }

    // ---- the host rule (AgentBossRunHost), on the run's tick -------------------------------------

    /**
     * Ties the companion to its owner's run key, from memory and at once (Commitments is synchronized and memory only);
     * false when it is tied to another run (the run refuses it BUSY, or counts it out MISSED_START at adoption). The run
     * asks again at adoption, which ties a companion again that a park let go of.
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

    /**
     * P7 Part B, on the run's tick, from memory: room for a companion at its turn to sign up, on a list of {@code
     * maxSize} that holds {@code listed} now. While the ally switch lists the boss, no companion (a party companion or
     * a bench ally) takes one of the last COMPANION_BOSS_HUMAN_SEATS seats, so players who joined while it was on its
     * way still find them free; it is refused FULL and says the list is full. Otherwise every seat the list has, so
     * Part A's sign-ups stay exactly as they were.
     */
    @Override
    public boolean roomFor(Seat seat, int listed, int maxSize) {
        LivingWorldConfig config = world.config();
        return config == null || !config.companionBossAlliesEnabled(seat.bossKey())
                || listed < maxSize - config.COMPANION_BOSS_HUMAN_SEATS;
    }

    @Override
    public void registered(Seat seat) {
        world.executor().submit("boss.registered " + seat.agentCharacterId(), () -> signedUp(seat));
    }

    @Override
    public void refused(Seat seat, Refusal refusal) {
        world.executor().submit("boss.refused " + seat.agentCharacterId(), () -> notListed(seat, refusal));
    }

    /**
     * On the run's tick, from memory: the member left the run. For an exit alive, whether the run is past its clear is
     * read here, as the run hands the member back (a bench ally's goodbye goes by it): the last ones out end the run on
     * this same tick, and it leaves the registry before companion-ops gets to the report.
     */
    @Override
    public void memberOut(Seat seat, Out out) {
        boolean cleared = out == Out.EXITED && open.cleared(seat.sessionId());
        world.executor().submit("boss.member_out " + seat.agentCharacterId(), () -> out(seat, out, cleared));
    }

    @Override
    public void runEnded(RunEnded ended) {
        world.executor().submit("boss.run_ended " + ended.sessionId(), () -> finish(ended));
    }

    /**
     * P8, on the run's tick, from memory: the member went into the fight. A companion this run allowed for a boss with
     * a daily entry log (CompanionBossQuota: Zakum) has its entry logged on companion-ops, once; every other member
     * (Easy Balrog's, one the stock count let in) hands nothing over.
     */
    @Override
    public void entered(Seat seat) {
        if (quota.counts(seat)) {
            world.executor().submit("boss.entered " + seat.agentCharacterId(), () -> quota.entered(seat));
        }
    }

    /**
     * P8, the boss log's question (LivingWorldHooks.bossQuota, from memory, on the asking thread): TRUE for a
     * companion this run allowed for {@code expedition} (its sign-up and the Start count and log nothing for it);
     * null, the stock count and log, for everyone else (CompanionBossQuota.answer).
     */
    public Boolean bossQuota(int characterId, Expedition expedition, boolean logEntry) {
        return quota.answer(characterId, expedition, logEntry);
    }

    /**
     * P7 Part B, on the run's tick, from memory: where a member waiting to sign up stands on the entry NPC's map, its
     * own spot beside her by its ordinal in the run (BossEntrance.spot), so a bench ally (which leads itself) walks up
     * to it. Null while the ally switch leaves the boss out, so Part A's sign-ups stay exactly as they were.
     */
    @Override
    public Point standAt(Seat seat, MapleMap map, int ordinal) {
        LivingWorldConfig config = world.config();
        BossEntrance entrance = config == null || !config.companionBossAlliesEnabled(seat.bossKey()) ? null
                : BossEntrance.of(seat.bossKey());
        return entrance == null ? null : entrance.spot(map, ordinal);
    }

    // ---- on companion-ops -----------------------------------------------------------------------

    /**
     * The companion is on the entry NPC's list: its persona and its potions are noted for its record and the potion
     * line (a bench ally's too), a bench ally's owner's allies form their party once none of them waits, and once none
     * of its owner's companions waits to sign up any more (the list still open), one of them says they are all set.
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
        allies.registered(seat);
        allSignedUp(run, id);
    }

    /**
     * The companion did not get onto the list, or the leader took it off: it is let go and says why (a bench ally in
     * its own words, and it goes back to its bench duty). When it was the last one of its owner's still waiting and the
     * list is still open, one already on the list says they are all set.
     */
    private void notListed(Seat seat, Refusal refusal) {
        int id = seat.agentCharacterId();
        CompanionBossRun run = whileOpen(seat);
        if (run != null) {
            run.signedUp.remove(id);
        }
        release(key(seat), id);
        if (!allies.refused(seat, refusal, run)) {
            BossRunLines.say(world, bot(id), BossRunLines.refusal(refusal), null, 0, run);
        }
        if (run != null) {
            allSignedUp(run, 0);
        }
    }

    /**
     * Once per owner, while the entry NPC's list is still open, when none of their companions waits to sign up any
     * more (a bench ally still being called or on its way down included) and one is on the list: {@code speaker} (or,
     * when it is 0, not loaded or a bench ally, another on the list) says they are all signed up, in the owner's party
     * when a companion of it is on the list; with only bench allies on it, one of them tells the owner where they hear
     * it (a party of bots has nobody to hear it). After Start it says nothing: the owner is on the way into the fight.
     */
    private void allSignedUp(CompanionBossRun run, int speaker) {
        if (run.signedUpSaid || run.signedUp.isEmpty() || !open.registering(run.sessionId)
                || open.waiting(run.sessionId, run.ownerId) || allies.calling(run.ownerId)) {
            return;
        }
        Character bot = follows(speaker, run.ownerId) ? bot(speaker) : null;
        Character ally = null;
        for (Iterator<Integer> listed = run.signedUp.iterator(); bot == null && listed.hasNext(); ) {
            int id = listed.next();
            Character loaded = bot(id);
            if (follows(id, run.ownerId)) {
                bot = loaded;
            } else if (ally == null) {
                ally = loaded;
            }
        }
        if (bot != null) {
            run.signedUpSaid = true;
            BossRunLines.say(world, bot, BossRunLines.SIGNED_UP, null, 0);
        } else if (ally != null) {
            run.signedUpSaid = true;
            BossRunLines.tell(world, ally, BossRunLines.SIGNED_UP, null, null, run, run.ownerId);
        }
    }

    /**
     * The companion left the run (a bench ally's way home is CompanionBossAllies'). A missed start lets it go with a
     * line; a death keeps it tied to the run (it waits outside for its owner and follows them out) with a line; no
     * human left is one line per owner and the run's end lets them go; a plain exit says nothing; a runtime that went
     * away (parked at its owner's logout) is let go. {@code cleared}: the run was past its clear as it handed the
     * member back (only a bench ally's goodbye reads it).
     */
    private void out(Seat seat, Out out, boolean cleared) {
        int id = seat.agentCharacterId();
        if (allies.out(seat, out, cleared, runs.get(key(seat)))) {
            return;
        }
        switch (out) {
            case MISSED_START -> {
                release(key(seat), id);
                BossRunLines.say(world, bot(id), BossRunLines.MISSED, null, 0, whileOpen(seat));
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
     * and each owner's part of it is taken out, once; every bench ally of it still here says how it went and goes back
     * to its bench duty, a list that never started too. A run that started then writes one record per companion that
     * went in (an owner's part taken out here: a second end of the same run writes nothing), logs the potion line, and
     * when it cleared each owner online hears one gg. P8: the run's allowed daily entries are forgotten.
     */
    void finish(RunEnded ended) {
        quota.runEnded(ended.sessionId());
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
        allies.runEnded(ended, taken);
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

    /**
     * One gg for the owner, when online, from a companion of their party that went in (one in at the clear first);
     * never from a bench ally, which says its own goodbye.
     */
    private void sayGg(CompanionBossRun run, List<MemberResult> wentIn) {
        if (run.ggSaid || world.roster().onlineCharacter(run.ownerId) == null) {
            return;
        }
        List<MemberResult> theirs = wentIn.stream().filter(member -> member.enlistedBy() == run.ownerId)
                .sorted((a, b) -> Boolean.compare(b.inAtClear(), a.inAtClear())).toList();
        for (MemberResult member : theirs) {
            Character bot = follows(member.agentId(), run.ownerId) ? bot(member.agentId()) : null;
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
     * {@link CompanionPartyQuestService#LOST_RUN_AFTER_MS}, with no record. The bench allies' trips are swept too
     * (CompanionBossAllies.sweep: a lost run's allies go home, an unload that left one online is tried again).
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
        allies.sweep(nowMs);
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
     * Decision 8b for a boss run, on companion-ops (RosterService.onOwnerLogout): a companion its owner leaves in the
     * fight sees it through for {@code heir}, the human of the owner's party still in it, while the fight is on or past
     * its clear, the companion is in it (not down outside, leaving or out) and the heir is a player of its event. The
     * run then has it stay with the heir instead of its owner (AgentBossRunService.handTo): it fights on, and after the
     * clear it leaves when the heir leaves; the run's end lets it go and it parks. The first of the owner's to stay
     * says so in party chat. Not agreed before the fight is on, nor for a run with no part of the owner's kept here:
     * the companion leaves with its owner (the park lets it go, and the run counts it gone).
     */
    @Override
    public boolean delegate(Character companion, Character heir, String key) {
        CompanionBossRun run = key == null ? null : runs.get(key);
        EventInstanceManager event = companion == null ? null : companion.getEventInstance();
        if (run == null || heir == null || event == null || !open.underway(run.sessionId)
                || !open.inside(run.sessionId, companion.getId()) || event.getPlayerById(heir.getId()) == null
                || !open.handTo(run.sessionId, companion.getId(), heir.getId())) {
            return false;
        }
        if (!run.handoffSaid) {
            run.handoffSaid = true;
            BossRunLines.say(world, companion, BossRunLines.HANDOFF, heir.getName(), 0);
        }
        return true;
    }

    /**
     * P7 Part B, on companion-ops (RosterService.onOwnerLogout, after the owner's party companions were parked or
     * handed over; never for the old session of a channel change): the owner's bench allies are called off, or see
     * their fight through for a human still in it (CompanionBossAllies.ownerLoggedOut).
     */
    void ownerLoggedOut(Character owner) {
        allies.ownerLoggedOut(owner);
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

    /**
     * The seat's owner's part of its run, kept from now on while the run is open (its lines' wordings are noted there);
     * after the run, only one still kept, else null: nothing is kept again for a run that is over.
     */
    private CompanionBossRun whileOpen(Seat seat) {
        return open.open(seat.sessionId()) ? run(seat) : runs.get(key(seat));
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

    /** True while the loaded Agent follows {@code ownerId}: a party companion of theirs (a bench ally leads itself). */
    private static boolean follows(int characterId, int ownerId) {
        return characterId != 0 && AgentRuntimeRegistry.leaderIdForAgentCharacter(characterId) == ownerId;
    }
}
