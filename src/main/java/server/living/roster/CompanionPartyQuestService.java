package server.living.roster;

import client.Character;
import client.Client;
import client.inventory.InventoryType;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.combat.AgentExpectedDamagePolicy;
import server.agents.capabilities.combat.data.AgentAttackDataProvider;
import server.agents.capabilities.dialogue.AgentDialogueCatalog;
import server.agents.capabilities.dialogue.AgentDialogueSelector;
import server.agents.capabilities.partyquest.AgentCompanionRunSession;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.kpq.AgentKpqRerollPolicy;
import server.agents.capabilities.partyquest.kpq.AgentKpqSession;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimator;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfiles;
import server.agents.capabilities.supplies.AgentPotionService;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.integration.AgentPresence;
import server.life.LifeFactory;
import server.life.Monster;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.resident.LivingTalk;
import server.living.store.LivingStore.CompanionRunStats;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToDoubleBiFunction;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/**
 * Party quests with companions (docs/BOT_MANAGEMENT_PLAN.md P6, flow 3): a party leader who asks a listed party quest's
 * entry NPC to start it (living_world.COMPANION_PQ_ENABLED_KEYS; Lakelis for "kpq", Tory for "hpq") is offered a lineup
 * of their party and companions instead of the stock start. On Yes the companions left out wait beside the NPC (a
 * HOLD), and the lineup is admitted and started at once, so the run begins with the party already inside: the 45 s
 * gather at Lakelis that 93 of 119 background runs failed never runs. KPQ is admitted and started in one step on the
 * leader's I/O thread; HPQ's admission runs on companion-ops and its start comes back to that thread. The run is kept
 * here, in memory, until its session ends (CompanionRunLifecycle); its companions and extras are committed to it
 * meanwhile. Each quest's rules are its entrance's (PqEntrance); the lineup windows are each entrance's desk's
 * (roster.ui EntryDesk, one per entrance, which LivingWorld installs).
 */
public final class CompanionPartyQuestService {
    private static final Logger log = LoggerFactory.getLogger(CompanionPartyQuestService.class);
    /**
     * A companion short of potions at the desk asks for some at most this often (the Agents' own resupply waits as
     * long); one that cannot afford them says so as often. How many it needs is the Agents' one readiness standard
     * (AgentSupplyReadiness.standard(PARTY_QUEST), agent-engine.yaml), which its own restock fills.
     */
    static final long MANA_ASK_EVERY_MS = 300_000L;
    /**
     * Admission asks the activity a companion is on to stop and lets it finish its step, within 30 s (town life,
     * hunting and questing's DEFAULT_GRACEFUL_EXIT_TIMEOUT_MS, a shop or trade's drain). Until then a Yes that finds it
     * still at it is answered at once, and the extras do not step out and back again.
     */
    static final long FINISHING_MS = 30_000L;
    /** A failed damage estimate is logged at info at most this often (else at debug). */
    static final long ESTIMATE_FAILURE_LOG_MS = 60_000L;
    /** Any body action: asking for its timing loads every body action's and stance's ({@link #warmPace}). */
    private static final String WARM_ACTION = "swingO1";
    private static final AtomicLong estimateFailureLoggedAtMs = new AtomicLong();
    /** A failed clear-time estimate is logged at info at most once per ESTIMATE_FAILURE_LOG_MS too. */
    private static final AtomicLong clearTimeFailureLoggedAtMs = new AtomicLong();
    /** A run whose session went away without ending it is let go once it has been gone this long (the audit's pace). */
    static final long LOST_RUN_AFTER_MS = 60_000L;
    /** A companion an entry NPC sent for from the bench says it is ready for her when it arrives within this long. */
    static final long ARRIVAL_NOTE_MS = 600_000L;

    /**
     * An entry NPC's windows about a lineup (Lakelis's, Tory's), each the answer to the leader's own click: the lineup,
     * the lineup again when a Yes found things changed, and how a start that did not go through ended.
     */
    public interface Desk {
        void proposal(Character leader, LineupPlan plan, String topLine);

        void thingsChanged(Character leader, LineupPlan plan);

        void outcome(Character leader, String line, long clickedAtMs);

        /**
         * A Yes found a companion still finishing what it was doing ({@code line} says so; admission asked it to stop):
         * {@code plan}, the lineup the leader said Yes to (its extras and their reasons too), goes in with the next Yes
         * once it is done. Without a window of its own, the line is the outcome.
         */
        default void stillFinishing(Character leader, LineupPlan plan, String line, long clickedAtMs) {
            outcome(leader, line, clickedAtMs);
        }
    }

    /**
     * Where a lineup's clear time is read from, all in memory on the leader's I/O thread: the quest's pace profile once
     * companion-ops warmed it ({@link #warmPace}; null while cold, and it never loads anything), its calibration for a
     * leader (what the clear-time records taught, the profile's priors until runs are recorded), a party member's power
     * against it, and how long the leader's last clear with exactly these companions took (seconds; 0: none on record).
     */
    interface PaceSource {
        AgentPqPaceProfile profile(String pqKey);

        AgentPqPaceCalibration calibration(AgentPqPaceProfile profile, int leaderId);

        AgentPqMemberPower power(Character chr, AgentPqPaceProfile profile, boolean human, boolean leader);

        double lastClearS(String pqKey, int leaderId, Set<Integer> companionIds);

        /**
         * The warmed profiles, the characters' own attacks, and the clear-time records {@code ledger} holds: what they
         * taught for the leader, or the profile's priors while none were read (or they were read under another epoch).
         */
        static PaceSource live(PqPaceLedger ledger) {
            return new PaceSource() {
                @Override
                public AgentPqPaceProfile profile(String pqKey) {
                    return AgentPqPaceProfiles.cached(pqKey);
                }

                @Override
                public AgentPqPaceCalibration calibration(AgentPqPaceProfile profile, int leaderId) {
                    AgentPqPaceCalibration learned = ledger.calibration(profile.questKey(), leaderId);
                    return learned != null && learned.epoch() == profile.epoch() ? learned
                            : AgentPqPaceCalibration.prior(profile);
                }

                @Override
                public AgentPqMemberPower power(Character chr, AgentPqPaceProfile profile, boolean human,
                                                boolean leader) {
                    return AgentPqMemberPower.of(chr, profile, human, leader);
                }

                @Override
                public double lastClearS(String pqKey, int leaderId, Set<Integer> companionIds) {
                    return ledger.lastClearS(pqKey, leaderId, companionIds);
                }
            };
        }

        /** No profile ever: every lineup is chosen and shown as before the clear time. */
        PaceSource COLD = new PaceSource() {
            @Override
            public AgentPqPaceProfile profile(String pqKey) {
                return null;
            }

            @Override
            public AgentPqPaceCalibration calibration(AgentPqPaceProfile profile, int leaderId) {
                return null;
            }

            @Override
            public AgentPqMemberPower power(Character chr, AgentPqPaceProfile profile, boolean human, boolean leader) {
                return null;
            }

            @Override
            public double lastClearS(String pqKey, int leaderId, Set<Integer> companionIds) {
                return 0.0d;
            }
        };
    }

    /** Until an entrance's desk is installed: no window, and an outcome is pink text in {@code voice}'s name. */
    private static Desk pinkText(DeskVoice voice) {
        return new Desk() {
            @Override
            public void proposal(Character leader, LineupPlan plan, String topLine) {
            }

            @Override
            public void thingsChanged(Character leader, LineupPlan plan) {
            }

            @Override
            public void outcome(Character leader, String line, long clickedAtMs) {
                leader.dropMessage(5, voice.npcName() + ": " + DeskLines.plain(line));
            }
        };
    }

    private final LivingWorld world;
    private final PqEntryPort port;
    private final ToIntFunction<Character> hpPotions;
    private final ToIntFunction<Character> mpPotions;
    /** The projectiles a loaded companion carries for its weapon (Integer.MAX_VALUE: it shoots none). */
    private volatile ToIntFunction<Character> ammo = AgentSupplyReadiness::carriedAmmo;
    /** What a loaded companion hits for at an entrance (the lineup's choice without a clear time). */
    private final ToDoubleBiFunction<Character, PqEntrance> dps;
    private final PaceSource pace;
    private final CompanionRunRecorder records;
    private final PqPaceLedger ledger;
    /** Each entrance's desk by its key; one not installed answers in pink text. */
    private final Map<String, Desk> desks = new ConcurrentHashMap<>();
    /** The entrances whose background population was found running at a click: said once each. */
    private final Set<String> backgroundWarned = ConcurrentHashMap.newKeySet();
    /** Leaders whose Yes is being carried out: a second Yes meanwhile only hears "hold on". */
    private final Set<Integer> entering = ConcurrentHashMap.newKeySet();
    private final Map<String, CompanionRun> runsBySession = new ConcurrentHashMap<>();
    private final Map<String, CompanionRun> runsByKey = new ConcurrentHashMap<>();
    /** Every lineup member and extra of a run -> the run. */
    private final Map<Integer, CompanionRun> runByCharacter = new ConcurrentHashMap<>();
    /** "ownerId personaId" -> which entrance sent for that companion from its owner's bench, and when. */
    private final Map<String, Called> calledForDesk = new ConcurrentHashMap<>();
    /** Companion character id -> when it last asked for potions (or said it cannot afford them) at Lakelis. */
    private final Map<Integer, Long> askedForMana = new ConcurrentHashMap<>();
    /** Leader id -> the companion admission last found still finishing something for their Yes. */
    private final Map<Integer, Finishing> finishing = new ConcurrentHashMap<>();
    /**
     * Lineup leader id -> when his last companion run that got in ended in a re-roll (he left stage 1 to run it
     * again); a run of his that ends any other way takes him out. Written on companion-ops, read on his I/O thread.
     */
    private final Map<Integer, Long> rerolledAt = new ConcurrentHashMap<>();

    /** A companion admission asked to stop what it was doing ({@code line}: her word for it), at {@code atMs}. */
    private record Finishing(int characterId, String line, long atMs) { }

    /** A companion {@code pqKey}'s desk sent for from its owner's bench, at {@code atMs}. */
    private record Called(String pqKey, long atMs) { }

    /**
     * What a companion sent for from {@code pqKey}'s bench tells the party as it arrives: {@code text}, her desk's
     * arrival line.
     */
    record ArrivalLine(String pqKey, String text) {
        /**
         * Once {@code speaker} said it in party chat: logged at info the way the quest's companion-run lines are
         * ('HPQ line: session= key= speaker= text='), with no session yet and the key 'arrival'.
         */
        void said(String speaker) {
            log.info("{} line: session=- key=arrival speaker={} text={}", label(pqKey), speaker, text);
        }
    }

    /** Tests count a companion's projectiles themselves. */
    void ammoCounter(ToIntFunction<Character> counter) {
        ammo = counter;
    }

    public CompanionPartyQuestService(LivingWorld world) {
        this(world, new PqPaceLedger(world));
    }

    private CompanionPartyQuestService(LivingWorld world, PqPaceLedger ledger) {
        this(world, PqEntryPort.STOCK, CompanionPartyQuestService::hpPotions, CompanionPartyQuestService::mpPotions,
                CompanionPartyQuestService::dps, PaceSource.live(ledger), ledger);
    }

    /** The service below with no clear time ever ({@link PaceSource#COLD}). */
    CompanionPartyQuestService(LivingWorld world, PqEntryPort port, ToIntFunction<Character> hpPotions,
                               ToIntFunction<Character> mpPotions, ToDoubleFunction<Character> kpqDps) {
        this(world, port, hpPotions, mpPotions, kpqDps, PaceSource.COLD);
    }

    /** The service below whose clear time is read from the warm profiles and {@code ledger}'s records. */
    CompanionPartyQuestService(LivingWorld world, PqEntryPort port, ToIntFunction<Character> hpPotions,
                               ToIntFunction<Character> mpPotions, ToDoubleFunction<Character> kpqDps,
                               PqPaceLedger ledger) {
        this(world, port, hpPotions, mpPotions, kpqDps, PaceSource.live(ledger), ledger);
    }

    /** The service below with clear-time records of its own, which its lineups do not read. */
    CompanionPartyQuestService(LivingWorld world, PqEntryPort port, ToIntFunction<Character> hpPotions,
                               ToIntFunction<Character> mpPotions, ToDoubleFunction<Character> kpqDps,
                               PaceSource pace) {
        this(world, port, hpPotions, mpPotions, kpqDps, pace, new PqPaceLedger(world));
    }

    /** The service below whose companions hit for {@code kpqDps} at every entrance. */
    CompanionPartyQuestService(LivingWorld world, PqEntryPort port, ToIntFunction<Character> hpPotions,
                               ToIntFunction<Character> mpPotions, ToDoubleFunction<Character> kpqDps,
                               PaceSource pace, PqPaceLedger ledger) {
        this(world, port, hpPotions, mpPotions, (bot, entrance) -> kpqDps.applyAsDouble(bot), pace, ledger);
    }

    /**
     * {@code port}: the doors into the quests; {@code hpPotions}, {@code mpPotions}: what a loaded companion carries;
     * {@code dps}: what it hits for at an entrance (the lineup's choice without a clear time); {@code pace}: where the
     * lineup's clear time is read from; {@code ledger}: where each run's clear-time record goes as it ends.
     */
    CompanionPartyQuestService(LivingWorld world, PqEntryPort port, ToIntFunction<Character> hpPotions,
                               ToIntFunction<Character> mpPotions, ToDoubleBiFunction<Character, PqEntrance> dps,
                               PaceSource pace, PqPaceLedger ledger) {
        this.world = world;
        this.port = port;
        this.hpPotions = hpPotions;
        this.mpPotions = mpPotions;
        this.dps = dps;
        this.pace = pace;
        this.records = new CompanionRunRecorder(world);
        this.ledger = ledger;
    }

    /** The companions' party-quest records, which the lineups rank by. */
    public CompanionRunRecorder records() {
        return records;
    }

    /** The PQ clear-time records (pq_run_pace), which the lineup's clear time is learned from. */
    public PqPaceLedger paceLedger() {
        return ledger;
    }

    /** Lakelis's desk (KPQ's windows, roster.ui); null: none, and her outcomes are pink text. */
    public void installDesk(Desk installed) {
        installDesk("kpq", installed);
    }

    /** {@code pqKey}'s desk (its entry NPC's windows); null: none, and her outcomes are pink text. */
    public void installDesk(String pqKey, Desk installed) {
        String key = PqEntrance.of(pqKey).key();
        if (installed == null) {
            desks.remove(key);
        } else {
            desks.put(key, installed);
        }
    }

    /** {@code pqKey}'s desk, or pink text in its entry NPC's name while none is installed. */
    private Desk desk(String pqKey) {
        PqEntrance entrance = PqEntrance.of(pqKey);
        Desk installed = desks.get(entrance.key());
        return installed != null ? installed : pinkText(entrance.voice());
    }

    /**
     * The party leader asked {@code pqKey}'s entry NPC to start it (on their I/O thread, inside the stock script).
     * True when this answered the click (with the lineup, planned as the answer); false leaves the stock start to the
     * script, with nothing sent or changed: a quest with no entrance, one the kill switch does not list, one whose
     * background population runs its lobbies (said once in the log), or a party it stands aside for.
     */
    public boolean offerAtDesk(Character leader, String pqKey) {
        PqEntrance entrance = PqEntrance.find(pqKey);
        if (entrance == null || !world.config().companionPqEnabled(pqKey)) {
            return false; // the stock start, untouched
        }
        if (entrance.backgroundRuns()) {
            if (backgroundWarned.add(entrance.key())) {
                log.warn("companion {} stands aside while the background population runs it (its lobbies count only "
                        + "their own runs): {} stays stock", label(entrance.key()), entrance.voice().npcName());
            }
            return false;
        }
        if (standsAside(leader, pqKey)) {
            return false;
        }
        return LivingTalk.answerScriptClick(leader, () -> {
            boolean restock = world.config().COMPANION_RESTOCK_ENABLED;
            if (restock) {
                checkShortSoon(leader);
            }
            LineupPlan plan = plan(leader, entrance.key());
            String back = entrance.voice().backAfterReroll(); // spent only by a voice that has a greeting
            String top = back != null && greetAfterReroll(leader.getId(), System.currentTimeMillis()) ? back
                    : restock && atTheStore(plan) ? DeskLines.RAN_TO_THE_STORE : null;
            desk(entrance.key()).proposal(leader, plan, top);
            if (restock) {
                askShort(plan);
            } else {
                askForMana(plan);
            }
        });
    }

    /**
     * Every loaded companion of the party short of the readiness standard of the quest whose entry NPC the leader
     * stands at (PqEntrance.keyAt, the standard its restock duty there fills), potions or projectiles, looks at its
     * supplies on its next tick (AgentSupplyReadiness.checkSoon), so one that can go sets off now. Memory only, on
     * the leader's I/O thread.
     */
    private void checkShortSoon(Character leader) {
        Party party = leader.getParty();
        AgentSupplyReadiness.Activity activity =
                AgentSupplyReadiness.Activity.partyQuest(PqEntrance.keyAt(leader.getMapId()));
        for (PartyCharacter member : party == null ? List.<PartyCharacter>of() : party.getMembers()) {
            AgentRuntimeEntry runtime = member.isBot() ? AgentRuntimeRegistry.findByAgentCharacterId(member.getId())
                    : null;
            Character bot = runtime == null ? null : AgentRuntimeIdentityRuntime.bot(runtime);
            if (bot != null && (!AgentSupplyReadiness.shortfall(hpPotions.applyAsInt(bot), mpPotions.applyAsInt(bot),
                    AgentSupplyReadiness.needsManaPotions(bot), activity).none()
                    || AgentSupplyReadiness.ammoShortfall(ammo.applyAsInt(bot), activity) > 0)) {
                AgentSupplyReadiness.checkSoon(runtime);
            }
        }
    }

    /** True when a companion of the lineup, or one waiting with it, is gone to the store. */
    private static boolean atTheStore(LineupPlan plan) {
        for (List<LineupPlan.Seat> seats : List.of(plan.onTheWay(), plan.extras())) {
            for (LineupPlan.Seat seat : seats) {
                if (seat.errand() != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Before entry: a companion the lineup leaves waiting for potions or ammo asks for some in party chat, in the
     * Agents' own words (mana, HP, or ammo); one that cannot afford them asks for them or mesos in its own voice, in
     * the desk's words for what it lacks (mana potions or potions; its ammo broke line names what it shoots). At most
     * once per {@link #MANA_ASK_EVERY_MS} per companion, said on companion-ops. One
     * whose own restock decides on its next tick (AgentSupplyReadiness.restockPending: the click just asked it to
     * look) asks nothing: it sets off, or says why not, itself. The broke line shares its memory with the companion's
     * own (CompanionRestock.mayAsk, one key for a potion and an ammo broke line), so it is never said twice within
     * seconds.
     */
    private void askShort(LineupPlan plan) {
        long now = System.currentTimeMillis();
        CompanionRestock restock = world.restock();
        for (LineupPlan.Waiting member : plan.waiting()) {
            String reason = member.reason();
            boolean brokeForAmmo = DeskLines.LOW_ON_AMMO_BROKE.equals(reason);
            boolean broke = DeskLines.LOW_ON_POTIONS_BROKE.equals(reason)
                    || DeskLines.LOW_ON_MANA_BROKE.equals(reason) || brokeForAmmo;
            boolean mana = DeskLines.LOW_ON_MANA.equals(reason);
            boolean shotless = DeskLines.LOW_ON_AMMO.equals(reason);
            if (!broke && !mana && !shotless && !DeskLines.LOW_ON_POTIONS.equals(reason)) {
                continue;
            }
            int id = member.characterId();
            if (!broke
                    && AgentSupplyReadiness.restockPending(AgentRuntimeRegistry.findByAgentCharacterId(id), now)) {
                continue;
            }
            boolean[] fresh = {false};
            askedForMana.compute(id, (key, at) -> {
                if (at != null && now - at < MANA_ASK_EVERY_MS) {
                    return at;
                }
                fresh[0] = true;
                return now;
            });
            if (!fresh[0] || broke && restock != null
                    && !restock.mayAsk(id, CompanionRestockLines.BROKE, MANA_ASK_EVERY_MS)) {
                continue;
            }
            world.executor().submit("pq.ask_potions " + id, () -> {
                Character bot = AgentRuntimeIdentityRuntime.bot(AgentRuntimeRegistry.findByAgentCharacterId(id));
                if (bot == null) {
                    return;
                }
                if (brokeForAmmo) {
                    CompanionRestockLines.say(world, bot, CompanionRestockLines.BROKE_AMMO,
                            CompanionRestockLines.ammoWord(bot));
                } else if (broke) {
                    CompanionRestockLines.say(world, bot, CompanionRestockLines.BROKE,
                            DeskLines.LOW_ON_MANA_BROKE.equals(reason) ? CompanionRestockLines.MANA_POTIONS
                                    : CompanionRestockLines.POTIONS);
                } else {
                    world.roster().sayInParty(bot, AgentDialogueSelector.randomReply(shotless
                            ? AgentDialogueCatalog.combatAmmoLowReplies() : mana
                            ? AgentDialogueCatalog.potRequestMpReplies() : AgentDialogueCatalog.potRequestHpReplies()));
                }
            });
        }
    }

    /**
     * True when the leader's last companion run ended in a re-roll less than REROLL_STREAK_RESET_MS ago: Lakelis
     * greets him back above the lineup. From memory, on his I/O thread.
     */
    boolean backAfterReroll(int leaderId, long nowMs) {
        Long at = rerolledAt.get(leaderId);
        return at != null && nowMs - at < AgentKpqRerollPolicy.configured().streakResetMs();
    }

    /**
     * {@link #backAfterReroll}, once per re-roll: the greeting is spent as she says it, so his next visit (after
     * "Never mind.", say) gets the lineup alone. The streak itself is the Agent layer's and unchanged.
     */
    private boolean greetAfterReroll(int leaderId, long nowMs) {
        Long at = rerolledAt.remove(leaderId); // one step against companion-ops recording his next re-roll
        return at != null && nowMs - at < AgentKpqRerollPolicy.configured().streakResetMs();
    }

    /**
     * Before entry: a companion the lineup leaves waiting for mana potions asks for some in party chat, where its owner
     * reads it, in the Agents' own pot-share words; at most once per {@link #MANA_ASK_EVERY_MS}, said on companion-ops.
     */
    private void askForMana(LineupPlan plan) {
        long now = System.currentTimeMillis();
        for (LineupPlan.Waiting member : plan.waiting()) {
            int id = member.characterId();
            if (!DeskLines.LOW_ON_MANA.equals(member.reason())) {
                continue;
            }
            long askedAt = askedForMana.compute(id, (key, at) -> at != null && now - at < MANA_ASK_EVERY_MS ? at : now);
            if (askedAt != now) {
                continue;
            }
            world.executor().submit("pq.ask_mana " + id, () -> {
                Character bot = AgentRuntimeIdentityRuntime.bot(AgentRuntimeRegistry.findByAgentCharacterId(id));
                if (bot != null) {
                    world.roster().sayInParty(bot,
                            AgentDialogueSelector.randomReply(AgentDialogueCatalog.potRequestMpReplies()));
                }
            });
        }
    }

    /**
     * True when the stock path takes the click and nothing here is touched: the quest has no entrance, an Agent clicked
     * (a background party's headless entry), there is no party or the clicker does not lead it, a session of the
     * quest already holds the leader or a member, a test run (a TEST_OBSERVATION engagement) owns one of them, or
     * nobody in the party is a companion. Owners are looked up only for the party's bots, so a human member never
     * costs a persona lookup.
     */
    boolean standsAside(Character leader, String pqKey) {
        PqEntrance entrance = PqEntrance.find(pqKey);
        if (entrance == null || leader == null || AgentPresence.isAgent(leader)) {
            return true;
        }
        Party party = leader.getParty();
        if (party == null || party.getLeaderId() != leader.getId()) {
            return true;
        }
        Collection<PartyCharacter> members = party.getMembers();
        List<Integer> ids = new ArrayList<>();
        for (PartyCharacter member : members) {
            ids.add(member.getId());
        }
        if (!entrance.registrationBlocker(leader.getId(), ids).isEmpty()
                || testRun(AgentPartyQuestEngagementRegistry.forOperator(leader.getId()))) {
            return true;
        }
        boolean companions = false;
        for (PartyCharacter member : members) {
            if (testRun(AgentPartyQuestEngagementRegistry.forMember(member.getId()))) {
                return true;
            }
            companions |= member.isBot() && world.directory().ownerOfCharacter(member.getId()) != 0;
        }
        return !companions;
    }

    private static boolean testRun(AgentPartyQuestEngagement engagement) {
        return engagement != null && engagement.mode() == AgentPartyQuestEngagement.Mode.TEST_OBSERVATION;
    }

    // ---- the lineup ----------------------------------------------------------------------------

    /** {@link #plan(Character, String, Set)} with the best companions chosen. */
    public LineupPlan plan(Character leader, String pqKey) {
        return plan(leader, pqKey, null);
    }

    /**
     * The lineup the leader's party would go in with, {@code picked} (companion ids, null: the best) chosen. On the
     * leader's I/O thread, from memory: the party, the loaded characters, the Crew menus' row cache, the commitments,
     * the companions' records and the game data's monsters of the quest (what a companion that can go hits for; read
     * at boot, {@link #warmPace}). While living_world.COMPANION_PQ_CLEAR_TIME is on and the quest's pace profile is
     * warm, every member's power is read too, and the lineup is weighed and shown with its clear time; else it is as
     * before. The quest's rules are its entrance's (PqEntrance.of throws for a quest with none).
     */
    public LineupPlan plan(Character leader, String pqKey, Set<Integer> picked) {
        PqEntrance entrance = PqEntrance.of(pqKey);
        Party party = leader.getParty();
        AgentPqPaceProfile profile = world.config().COMPANION_PQ_CLEAR_TIME ? profile(leader, pqKey) : null;
        List<LineupPlan.Candidate> candidates = new ArrayList<>();
        for (PartyCharacter member : party == null ? List.<PartyCharacter>of() : party.getMembers()) {
            candidates.add(member.isBot() ? companion(leader, member, profile, entrance)
                    : human(leader, member, profile, entrance));
        }
        LineupPlan plan = LineupPlan.build(pqKey, leader.getId(), leader.getLevel(), candidates, picked,
                profile == null ? null : clearTime(leader, pqKey, profile));
        // what the quest needs between its members, before Yes (LPQ's roles; every other quest's plan as it is)
        return LineupComposition.apply(plan, leader, entrance,
                id -> AgentRuntimeIdentityRuntime.bot(AgentRuntimeRegistry.findByAgentCharacterId(id)));
    }

    /** The quest's warm pace profile; null while cold, or when reading it failed. */
    private AgentPqPaceProfile profile(Character leader, String pqKey) {
        try {
            return pace.profile(pqKey);
        } catch (RuntimeException | LinkageError failure) {
            clearTimeFailed(leader, pqKey, "profile", failure);
            return null;
        }
    }

    /**
     * How the lineup's clear time is estimated for this leader; null (the lineup as without one) when there is no
     * calibration to estimate by, or reading it failed.
     */
    private LineupPlan.Pace clearTime(Character leader, String pqKey, AgentPqPaceProfile profile) {
        AgentPqPaceCalibration calibration;
        try {
            calibration = pace.calibration(profile, leader.getId());
        } catch (RuntimeException | LinkageError failure) {
            clearTimeFailed(leader, profile.questKey(), "calibration", failure);
            return null;
        }
        if (calibration == null) {
            return null;
        }
        return new LineupPlan.Pace(members -> estimate(leader, profile, members, calibration), profile.timerMs(),
                companions -> lastClearS(leader, pqKey, companions));
    }

    /**
     * The estimate of a lineup; one that throws (or whose game data cannot initialise) counts as none: the lineup is
     * chosen and shown as without a clear time, never failed by it. Logged at info now and then.
     */
    private static AgentPqPaceEstimate estimate(Character leader, AgentPqPaceProfile profile,
                                                List<AgentPqMemberPower> members,
                                                AgentPqPaceCalibration calibration) {
        try {
            return AgentPqPaceEstimator.estimate(profile, members, calibration);
        } catch (RuntimeException | LinkageError failure) {
            clearTimeFailed(leader, profile.questKey(), "estimate", failure);
            return null;
        }
    }

    /** The leader's last clear with exactly these companions, in seconds; 0 when none is on record or it failed. */
    private double lastClearS(Character leader, String pqKey, Set<Integer> companions) {
        try {
            return pace.lastClearS(pqKey, leader.getId(), companions);
        } catch (RuntimeException | LinkageError failure) {
            clearTimeFailed(leader, pqKey, "last clear", failure);
            return 0.0d;
        }
    }

    /**
     * A member's power against a warm profile ({@code profile} null: none); null when reading it failed, a class it
     * reads that cannot initialise (game data that needs the database, say) included.
     */
    private AgentPqMemberPower power(Character chr, AgentPqPaceProfile profile, boolean human, boolean leader) {
        if (profile == null) {
            return null;
        }
        try {
            return pace.power(chr, profile, human, leader);
        } catch (RuntimeException | LinkageError failure) {
            clearTimeFailed(chr, profile.questKey(), "power", failure);
            return null;
        }
    }

    /** A part of a lineup's clear time could not be read: logged at info at most once per ESTIMATE_FAILURE_LOG_MS. */
    private static void clearTimeFailed(Character chr, String pqKey, String what, Throwable failure) {
        long now = System.currentTimeMillis();
        long loggedAt = clearTimeFailureLoggedAtMs.get();
        if (now - loggedAt >= ESTIMATE_FAILURE_LOG_MS && clearTimeFailureLoggedAtMs.compareAndSet(loggedAt, now)) {
            log.info("no {} clear time ({}) at {}'s lineup; it is chosen and shown as without one", pqKey, what,
                    chr.getName(), failure);
        } else {
            log.debug("no {} clear time ({}) at {}'s lineup", pqKey, what, chr.getName(), failure);
        }
    }

    /** A human always goes in; one that cannot keeps the run from starting, in the words admission would refuse it. */
    private LineupPlan.Candidate human(Character leader, PartyCharacter member, AgentPqPaceProfile profile,
                                       PqEntrance entrance) {
        int id = member.getId();
        Character chr = id == leader.getId() ? leader : world.roster().onlineCharacter(id);
        if (chr == null) {
            return new LineupPlan.Candidate(seat(leader, id, member.getName(), null, true, 0, null, 0L, entrance),
                    LineupPlan.Fit.BLOCKS, DeskLines.named(DeskLines.NOT_HERE, member.getName()), false);
        }
        String refusal = entrance.memberProblem(chr, id == leader.getId());
        String line = !RosterService.sameChannel(leader, chr) ? DeskLines.THIS_CHANNEL
                : engaged(leader, id) ? DeskLines.CATCHING_BREATH
                : refusal.isEmpty() ? null : entrance.voice().forAdmit(refusal, leader.getName());
        return new LineupPlan.Candidate(seat(leader, id, chr.getName(), chr, true, 0, null, 0L, entrance),
                line == null ? LineupPlan.Fit.READY : LineupPlan.Fit.BLOCKS, line, false, 0.0d,
                power(chr, profile, true, id == leader.getId()));
    }

    /**
     * A bot in the party. A companion goes in when it is loaded, ACTIVE, on the leader's channel, standing, tied to no
     * run, admissible and carries its potions; one called over is on its way; one loaded otherwise waits outside with
     * its reason. A companion not loaded (logged off with a friend), one tied to a run, one whose row is not out with
     * its owner (a hold would refuse it: walking out to the bench, say) and any other Agent (a citizen, which carries
     * its own engagement) keep the run from starting.
     */
    private LineupPlan.Candidate companion(Character leader, PartyCharacter member, AgentPqPaceProfile profile,
                                           PqEntrance entrance) {
        int id = member.getId();
        Character bot = AgentRuntimeIdentityRuntime.bot(AgentRuntimeRegistry.findByAgentCharacterId(id));
        Persona persona = world.residents().personaOfCharacterId(id);
        int owner = persona == null ? 0 : world.roster().cachedOwnerOf(persona.id());
        String name = persona != null ? persona.name() : bot != null ? bot.getName() : member.getName();
        if (owner == 0) {
            return blocks(id, name, DeskLines.NOT_IN_CREW, entrance);
        }
        if (bot == null) {
            return blocks(id, name, DeskLines.NOT_UP, entrance);
        }
        if (world.commitments().isCommitted(id) || AgentPartyQuestEngagementRegistry.forMember(id) != null) {
            return new LineupPlan.Candidate(seat(leader, id, name, bot, false, owner, persona.id(), 0L, entrance),
                    LineupPlan.Fit.BLOCKS, DeskLines.CATCHING_BREATH, false);
        }
        RosterEntry row = world.views().row(owner, persona.id());
        if (row == null || !CompanionSeatState.partyMember(row.state())) {
            return blocks(id, name, DeskLines.BUSY, entrance);
        }
        boolean usual = world.views().lineup(owner).contains(persona.id());
        if (row.state() == RosterState.TRAVELLING) {
            long etaMs = Math.max(0L, row.etaAtMs() - System.currentTimeMillis());
            return new LineupPlan.Candidate(seat(leader, id, name, bot, false, owner, persona.id(), etaMs, entrance),
                    LineupPlan.Fit.ON_THE_WAY, null, usual, dps.applyAsDouble(bot, entrance),
                    power(bot, profile, false, false));
        }
        String reason = !RosterService.sameChannel(leader, bot) ? DeskLines.OTHER_CHANNEL
                : bot.getHp() <= 0 ? DeskLines.KNOCKED_OUT
                : waitingReason(bot, entrance);
        if (world.config().COMPANION_RESTOCK_ENABLED && restockReason(reason, entrance)) {
            AgentSupplyReadiness.Status trip = AgentSupplyReadiness.status(
                    AgentRuntimeRegistry.findByAgentCharacterId(id), System.currentTimeMillis());
            if (trip.restocking()) {
                // gone to the store for potions or ammo (its own restock): on its way, as a bench call is
                return new LineupPlan.Candidate(seat(leader, id, name, bot, false, owner, persona.id(), trip.etaMs(),
                        DeskLines.AT_THE_STORE, entrance), LineupPlan.Fit.ON_THE_WAY, null, usual,
                        dps.applyAsDouble(bot, entrance), power(bot, profile, false, false));
            }
            if (trip.cannotAfford() && DeskLines.LOW_ON_POTIONS.equals(reason)) {
                reason = DeskLines.LOW_ON_POTIONS_BROKE;
            } else if (trip.cannotAfford() && DeskLines.LOW_ON_MANA.equals(reason)) {
                reason = DeskLines.LOW_ON_MANA_BROKE;
            } else if (trip.cannotAfford() && DeskLines.LOW_ON_AMMO.equals(reason)) {
                reason = DeskLines.LOW_ON_AMMO_BROKE;
            }
        }
        return new LineupPlan.Candidate(seat(leader, id, name, bot, false, owner, persona.id(), 0L, entrance),
                reason == null ? LineupPlan.Fit.READY : LineupPlan.Fit.WAITS, reason, usual,
                reason == null ? dps.applyAsDouble(bot, entrance) : 0.0d,
                reason == null ? power(bot, profile, false, false) : null);
    }

    /**
     * Why the quest's admission would refuse the companion (its entrance's words for it), or why it would go in
     * unready: short of the quest's readiness standard (AgentSupplyReadiness.shortfall(partyQuest(key)), the one its
     * own restock fills at that entrance) on HP potions, or on mana potions when it fights with skills (every job
     * past a beginner's), or short of the quest's ammo standard (AgentSupplyReadiness.ammoStandard) for a claw, bow,
     * crossbow or gun, which its own restock buys like potions; null: it can go.
     */
    private String waitingReason(Character bot, PqEntrance entrance) {
        String problem = entrance.memberProblem(bot, false);
        if (!problem.isEmpty()) {
            return entrance.voice().reason(problem, bot.getLevel());
        }
        AgentSupplyReadiness.Shortfall shortfall = AgentSupplyReadiness.shortfall(hpPotions.applyAsInt(bot),
                mpPotions.applyAsInt(bot), AgentSupplyReadiness.needsManaPotions(bot),
                AgentSupplyReadiness.Activity.partyQuest(entrance.key()));
        return shortfall.hp() > 0 ? DeskLines.LOW_ON_POTIONS
                : shortfall.mp() > 0 ? DeskLines.LOW_ON_MANA
                : AgentSupplyReadiness.ammoShortfall(ammo.applyAsInt(bot),
                        AgentSupplyReadiness.Activity.partyQuest(entrance.key())) > 0
                ? DeskLines.LOW_ON_AMMO : null;
    }

    /** A wait its own restock may be behind: short of potions or ammo, or off the entrance (at the shop). */
    private static boolean restockReason(String reason, PqEntrance entrance) {
        return DeskLines.LOW_ON_POTIONS.equals(reason) || DeskLines.LOW_ON_MANA.equals(reason)
                || DeskLines.LOW_ON_AMMO.equals(reason) || entrance.voice().notAtEntrance().equals(reason);
    }

    private LineupPlan.Candidate blocks(int id, String name, String line, PqEntrance entrance) {
        return new LineupPlan.Candidate(seat(null, id, name, null, false, 0, null, 0L, entrance), LineupPlan.Fit.BLOCKS,
                DeskLines.named(line, name), false);
    }

    /**
     * The member's seat; a companion's carries its record in the quest. Here: at the quest's entrance, on the leader's
     * channel.
     */
    private LineupPlan.Seat seat(Character leader, int id, String name, Character chr, boolean human, int ownerId,
                                 String personaId, long etaMs, PqEntrance entrance) {
        return seat(leader, id, name, chr, human, ownerId, personaId, etaMs, null, entrance);
    }

    /** {@link #seat} on {@code errand} (DeskLines.AT_THE_STORE; null: none). */
    private LineupPlan.Seat seat(Character leader, int id, String name, Character chr, boolean human, int ownerId,
                                 String personaId, long etaMs, String errand, PqEntrance entrance) {
        int jobId = chr == null ? -1 : chr.getJob().getId();
        CompanionRunStats record = human ? CompanionRunRecorder.NONE : records.stats(entrance.key(), id);
        boolean here = chr != null && chr.getMapId() == entrance.definition().recruitMapId()
                && RosterService.sameChannel(leader, chr);
        return new LineupPlan.Seat(id, name, chr == null ? 0 : chr.getLevel(), jobId, CompanionView.jobName(jobId), human,
                ownerId, personaId, chr == null ? 0 : chr.getMapId(), here, etaMs, record.runs(), record.clears(), errand);
    }

    /** True while an engagement holds the character (a run's post-run recovery, say), or the leader operates one. */
    private static boolean engaged(Character leader, int id) {
        return AgentPartyQuestEngagementRegistry.forMember(id) != null
                || id == leader.getId() && AgentPartyQuestEngagementRegistry.forOperator(id) != null;
    }

    /** The HP-restoring potions in the companion's USE inventory, by quantity, as the Agent counts them. */
    static int hpPotions(Character bot) {
        return bot.getInventory(InventoryType.USE) == null ? 0 : AgentPotionService.countPotions(bot)[0];
    }

    /** The MP-restoring potions in the companion's USE inventory, by quantity (an Elixir counts here too). */
    static int mpPotions(Character bot) {
        return bot.getInventory(InventoryType.USE) == null ? 0 : AgentPotionService.countPotions(bot)[1];
    }

    /** What a loaded companion hits for at {@code entrance}: {@link #dpsAgainst} its monsters. */
    static double dps(Character bot, PqEntrance entrance) {
        return dpsAgainst(bot, entrance.dpsMobIds());
    }

    /** What a loaded companion hits for in KPQ: {@link #dpsAgainst} Lakelis's monsters. */
    static double kpqDps(Character bot) {
        return dps(bot, PqEntrance.of("kpq"));
    }

    /**
     * What a loaded companion hits for: its expected damage per second against each of {@code mobIds}
     * (AgentExpectedDamagePolicy: its best attack after hit chance and defense), averaged. One that cannot be
     * estimated counts as 0, so the lineup never fails on it; that is logged at info now and then, so an estimate
     * that fails for a whole job shows in a live run.
     */
    static double dpsAgainst(Character bot, int[] mobIds) {
        try {
            double total = 0.0d;
            for (int mobId : mobIds) {
                Monster mob = LifeFactory.getMonster(mobId);
                total += mob == null ? 0.0d : AgentExpectedDamagePolicy.damagePerSecond(bot, mob);
            }
            return total / mobIds.length;
        } catch (RuntimeException failure) {
            long now = System.currentTimeMillis();
            long loggedAt = estimateFailureLoggedAtMs.get();
            if (now - loggedAt >= ESTIMATE_FAILURE_LOG_MS && estimateFailureLoggedAtMs.compareAndSet(loggedAt, now)) {
                log.info("no damage estimate for {} (job {}) against {}; it counts as 0 in the lineup", bot.getName(),
                        bot.getJob(), Arrays.toString(mobIds), failure);
            } else {
                log.debug("no damage estimate for {}", bot.getName(), failure);
            }
            return 0.0d;
        }
    }

    /**
     * At boot, on companion-ops, for every entrance (PqEntrance.keys(), listed by the kill switch or not, so a quest
     * switched on at the next restart is warm and its warm is seen in the log first): its pace profile is registered
     * and built from the game data (AgentPqPaceProfiles.warm: Map.wz's monster census, and its monsters into
     * LifeFactory), its monsters are read for the lineup without a clear time (LifeFactory keeps them, so a profile
     * that could not be built leaves them warm all the same); then the body timings every attack's cooldown is read
     * from are loaded: a lineup at an entry NPC never parses Mob.wz or Character.wz on a leader's I/O thread. Each
     * step is on its own: one that fails, even with a class that cannot initialise, is logged and the others still
     * run.
     */
    public void warmPace() {
        for (String key : PqEntrance.keys()) {
            PqEntrance entrance = PqEntrance.of(key);
            try {
                AgentPqPaceProfiles.register(key, entrance.paceLoader());
                AgentPqPaceProfiles.warm(key);
            } catch (RuntimeException | LinkageError failure) {
                log.warn("{}'s pace profile could not be warmed; its lineups show no clear time", label(key), failure);
            }
            try {
                for (int mobId : entrance.dpsMobIds()) {
                    LifeFactory.getMonster(mobId);
                }
            } catch (RuntimeException | LinkageError failure) {
                log.warn("{}'s monsters could not be warmed for the lineup", label(key), failure);
            }
        }
        try {
            AgentAttackDataProvider timings = AgentAttackDataProvider.getInstance();
            timings.getBodyActionId(WARM_ACTION);
            timings.getBodyActionDurationMs(WARM_ACTION);
            timings.getBodyStanceDurationMs(WARM_ACTION);
        } catch (RuntimeException | LinkageError failure) {
            log.warn("attack timings could not be warmed; the first lineup at an entry NPC reads them", failure);
        }
    }

    /** Each lineup companion's HP and MP potions as the run is let in (one not online is left out). */
    private static void notePotionsAtYes(CompanionRun run) {
        for (int companionId : run.ownerByCompanion.keySet()) {
            int[] potions = potionsOf(companionId);
            if (potions != null) {
                run.potionsAtYes.put(companionId, potions);
            }
        }
    }

    /** The companion's HP and MP potions ({HP, MP}) as it carries them now; null when it is not online. */
    static int[] potionsOf(int companionId) {
        Character bot = AgentRuntimeIdentityRuntime.bot(AgentRuntimeRegistry.findByAgentCharacterId(companionId));
        return bot == null ? null : AgentPotionService.countPotions(bot);
    }

    // ---- sent for from the bench ----------------------------------------------------------------

    /** {@link #noteCalledForDesk} at Lakelis's (KPQ's) desk. */
    public void noteCalledForLakelis(int ownerId, String personaId) {
        noteCalledForDesk(ownerId, personaId, "kpq");
    }

    /**
     * {@code pqKey}'s entry NPC sent for the owner's companion from their bench and it set off (the desk, on
     * companion-ops): as it arrives it tells the party it is ready for her instead of its usual word. There is no
     * timer: the note is looked at only when the companion arrives, and one older than {@link #ARRIVAL_NOTE_MS} is
     * forgotten then.
     */
    public void noteCalledForDesk(int ownerId, String personaId, String pqKey) {
        calledForDesk.put(ownerId + " " + personaId, new Called(PqEntrance.of(pqKey).key(),
                System.currentTimeMillis()));
    }

    /**
     * RosterService.arrive: once, when the companion arriving now was sent for from an entry NPC's bench, what it says
     * to the party (her voice's arrival line, {@link ArrivalLine#said} once said); else null.
     */
    ArrivalLine takeArrivalLine(int ownerId, String personaId, long nowMs) {
        Called called = calledForDesk.remove(ownerId + " " + personaId);
        return called != null && nowMs - called.atMs() <= ARRIVAL_NOTE_MS
                ? new ArrivalLine(called.pqKey(), PqEntrance.of(called.pqKey()).voice().arrivalLine()) : null;
    }

    /** True, once, when the companion arriving now was sent for from an entry NPC's bench. */
    boolean takeArrivalNote(int ownerId, String personaId, long nowMs) {
        return takeArrivalLine(ownerId, personaId, nowMs) != null;
    }

    // ---- Yes: the extras step out, then admission and the start in one I/O step -----------------

    /**
     * The leader said Yes to {@code clicked} (on their I/O thread, as the answer to that click). Re-planned the way it
     * was drawn (her choice, or the leader's pick); when that is no longer the lineup they saw, or it cannot start, the
     * lineup is shown again instead, and so it is while a companion the last Yes found finishing something is at it
     * still ({@link #stillFinishing}). Otherwise the extras step out on companion-ops ({@link #prepareEntry}) and the
     * run starts back here. A Yes that throws lets the next one through: only its log is left.
     */
    public void start(Character leader, LineupPlan clicked) {
        int leaderId = leader.getId();
        Desk desk = desk(clicked.pqKey());
        if (!entering.add(leaderId)) {
            desk.outcome(leader, DeskLines.HOLD_ON, System.currentTimeMillis());
            return;
        }
        try {
            LineupPlan fresh = plan(leader, clicked.pqKey(), clicked.picked());
            if (!fresh.startable() || !fresh.memberIds().equals(clicked.memberIds())) {
                entering.remove(leaderId);
                desk.thingsChanged(leader, fresh);
                return;
            }
            long clickedAt = System.currentTimeMillis();
            String finishingLine = stillFinishing(leaderId, fresh, clickedAt);
            if (finishingLine != null) {
                entering.remove(leaderId);
                desk.stillFinishing(leader, fresh, finishingLine, clickedAt);
                return;
            }
            logClearTime(leader, fresh);
            world.executor().submit("pq.prepare " + leaderId, () -> prepareEntry(leader, fresh, clickedAt));
        } catch (RuntimeException failure) {
            entering.remove(leaderId);
            throw failure;
        }
    }

    /**
     * The clear time of the lineup the leader said Yes to, for the next timed run to set beside its clear: seconds,
     * as the estimate gives them (nothing a player sees). Nothing without one.
     */
    private static void logClearTime(Character leader, LineupPlan plan) {
        LineupPlan.ClearTime clearTime = plan.clearTime();
        if (clearTime == null) {
            return;
        }
        AgentPqPaceEstimate estimate = clearTime.estimate();
        log.info("companion {} clear time at Yes for {}: members={} clearable={} p10/p50/p90={}/{}/{}s overTimer={}"
                        + " time={}s objective={}s ownTime={}s ownObjective={}s source={} records={} partyDps={}"
                        + " weaknesses={}", plan.pqKey(),
                leader.getName(), plan.memberIds(), estimate.clearable(), Math.round(estimate.p10S()),
                Math.round(estimate.p50S()), Math.round(estimate.p90S()),
                String.format(java.util.Locale.ROOT, "%.3f", estimate.overTimerChance()),
                seconds(clearTime.timeS()), seconds(clearTime.objectiveS()), seconds(clearTime.ownTimeS()),
                seconds(clearTime.ownObjectiveS()),
                estimate.source(), estimate.recordsUsed(), estimate.partyDpsByMob(), estimate.weaknesses());
    }

    /** Whole seconds for the log; "never" for a lineup that cannot clear, "none" for no objective. */
    private static String seconds(double seconds) {
        return Double.isNaN(seconds) ? "none" : Double.isInfinite(seconds) ? "never" : Long.toString(Math.round(seconds));
    }

    /**
     * Her line while the companion that admission found still finishing something at the leader's last Yes, under
     * {@link #FINISHING_MS} ago, is in this lineup and at it still: it was asked to stop then, so this Yes would only
     * step the extras out and back for the same answer. Null: the Yes goes ahead, and admission asks again.
     */
    String stillFinishing(int leaderId, LineupPlan fresh, long nowMs) {
        Finishing note = finishing.get(leaderId);
        if (note == null) {
            return null;
        }
        if (nowMs - note.atMs() < FINISHING_MS && fresh.memberIds().contains(note.characterId())
                && port.finishing(note.characterId())) {
            return note.line();
        }
        finishing.remove(leaderId, note);
        return null;
    }

    /**
     * On companion-ops: every extra steps out of the party to wait outside the run (a HOLD), beside the entry NPC when
     * it is at the entrance (PqEntrance.beside: beside Lakelis, on the park floor off Tory's ledge) and where it stands
     * otherwise (another map, another channel, on its way). The leader's own beside her say so aloud; the rest tell
     * their owner, never the strangers of some far map. Then the run starts on the leader's I/O thread; one that cannot
     * step out calls the others back, and so does a hold that throws (the leader hears the generic line; companion-ops
     * logs the failure).
     */
    void prepareEntry(Character leader, LineupPlan fresh, long clickedAt) {
        Set<Integer> touched = new LinkedHashSet<>();
        try {
            PqEntrance entrance = PqEntrance.of(fresh.pqKey());
            int beside = 0;
            for (LineupPlan.Seat extra : fresh.extras()) {
                Character owner = world.roster().onlineCharacter(extra.ownerId());
                Persona persona = world.content().persona(extra.personaId());
                // gone to the store: it waits beside her once back (RosterService.hold lets the trip finish)
                boolean restocking = world.config().COMPANION_RESTOCK_ENABLED && AgentSupplyReadiness.status(
                        AgentRuntimeRegistry.findByAgentCharacterId(extra.characterId()),
                        System.currentTimeMillis()).restocking();
                Point point = extra.here() || restocking ? entrance.beside(leader.getMap(), beside) : null;
                if (point != null) {
                    beside++;
                }
                int holdMapId = restocking ? leader.getMapId() : extra.mapId();
                HoldNote note = extra.ownerId() != leader.getId()
                        ? HoldNote.whisper(leader.getName() + " asked me to wait outside.")
                        : extra.here() ? HoldNote.map(null) : HoldNote.whisper(null);
                RosterResult held = owner == null || persona == null ? new RosterResult.NotOwned()
                        : world.roster().hold(owner, persona, holdMapId, point, note);
                if (!held.ok()) {
                    log.info("companion {} for {}: {} could not wait outside: {}", label(fresh.pqKey()),
                            leader.getName(), extra.name(), held);
                    rejoin(touched);
                    entering.remove(leader.getId());
                    answerOnIo(leader, fresh.pqKey(), DeskLines.PARTY_CHANGED, clickedAt);
                    return;
                }
                touched.add(extra.ownerId());
            }
            Client client = leader.getClient();
            if (client == null) {
                rejoin(touched); // logged off meanwhile
                entering.remove(leader.getId());
                return;
            }
            client.runOnIoThread(() -> enter(leader, fresh, clickedAt));
        } catch (RuntimeException failure) {
            entering.remove(leader.getId()); // first: the next Yes goes through even if the rejoin throws too
            rejoin(touched);
            answerOnIo(leader, fresh.pqKey(), PqEntrance.of(fresh.pqKey()).voice().generic(), clickedAt);
            throw failure;
        }
    }

    /**
     * On the leader's I/O thread: the party must still be the lineup, all of it at the entrance; a lineup of humans
     * only takes the stock start. Otherwise it is admitted, marked a companion run (tap-outs, guidance), committed with
     * its extras, and the event started. KPQ does all of it in this one step; HPQ's admission runs on companion-ops
     * ({@link #admitOffIo}: never on an I/O thread) and the rest comes back here. A start that fails after admission
     * stops the session again. A companion still finishing another activity is never waited for on this thread (live,
     * 19:56 and 19:58 on 2026-09-30, the refusals came on the Netty one): the lineup the leader said Yes to is shown
     * again for the next Yes, and the companion is noted for it ({@link #stillFinishing}).
     */
    void enter(Character leader, LineupPlan fresh, long clickedAt) {
        Set<Integer> owners = owners(fresh);
        boolean handedOn = false;
        try {
            Desk desk = desk(fresh.pqKey());
            List<Character> members = present(leader, fresh);
            if (members == null) {
                rejoinLater(leader, owners);
                desk.outcome(leader, DeskLines.PARTY_CHANGED, clickedAt);
                return;
            }
            List<LineupPlan.Seat> companions = fresh.lineup().stream().filter(seat -> !seat.human()).toList();
            if (companions.isEmpty()) {
                // the extras rejoin as their owners come out
                PqEntryPort.Start started = startInstance(leader, fresh.pqKey());
                if (started != PqEntryPort.Start.STARTED) {
                    rejoinLater(leader, owners);
                    desk.outcome(leader, PqEntrance.of(fresh.pqKey()).voice().forStart(started), clickedAt);
                }
                return;
            }
            if (!PqEntrance.of(fresh.pqKey()).admitsOnIo()) {
                world.executor().submit("pq.admit " + leader.getId(), () -> admitOffIo(leader, fresh, members,
                        clickedAt));
                handedOn = true;
                return;
            }
            long now = System.currentTimeMillis();
            entered(leader, fresh, members, port.admit(fresh.pqKey(), leader, members,
                    ThreadLocalRandom.current().nextLong(), now), now, clickedAt);
        } finally {
            if (!handedOn) {
                entering.remove(leader.getId());
            }
        }
    }

    /**
     * On companion-ops, for a quest admitted off the I/O thread (HPQ): the lineup is admitted here, then the rest of
     * the entry goes back to the leader's I/O thread ({@link #entered}). A leader who logged off meanwhile, or a
     * hand-back that throws, has an admitted session stopped and the extras called back; a throw answers the generic
     * line too (companion-ops logs the failure).
     */
    private void admitOffIo(Character leader, LineupPlan fresh, List<Character> members, long clickedAt) {
        Set<Integer> owners = owners(fresh);
        boolean handedOn = false;
        PqEntryPort.Admitted admitted = null;
        try {
            long now = System.currentTimeMillis();
            admitted = port.admit(fresh.pqKey(), leader, members, ThreadLocalRandom.current().nextLong(), now);
            PqEntryPort.Admitted entry = admitted;
            Client client = leader.getClient();
            if (client == null) {
                stopAdmitted(fresh, entry, "leader logged off"); // nobody is left to start it
                rejoin(owners);
                return;
            }
            client.runOnIoThread(() -> {
                try {
                    entered(leader, fresh, members, entry, now, clickedAt);
                } finally {
                    entering.remove(leader.getId());
                }
            });
            handedOn = true;
        } catch (RuntimeException failure) {
            stopAdmitted(fresh, admitted, "start failed");
            rejoin(owners);
            answerOnIo(leader, fresh.pqKey(), PqEntrance.of(fresh.pqKey()).voice().generic(), clickedAt);
            throw failure;
        } finally {
            if (!handedOn) {
                entering.remove(leader.getId());
            }
        }
    }

    /**
     * On the leader's I/O thread, once admission answered ({@code admitted}, at {@code now}): a refusal is said in the
     * entry NPC's words (a companion still finishing something shows the lineup again); else the session is marked a
     * companion run (KPQ's flag here, HPQ's admission set it already), given the lineup's clear-time calibration and
     * the guidance switch, its run registered and committed with its extras, and the event started.
     */
    private void entered(Character leader, LineupPlan fresh, List<Character> members, PqEntryPort.Admitted admitted,
                         long now, long clickedAt) {
        Desk desk = desk(fresh.pqKey());
        DeskVoice voice = PqEntrance.of(fresh.pqKey()).voice();
        if (!admitted.success()) {
            log.info("companion {} admission refused for {}: {}", label(fresh.pqKey()), leader.getName(),
                    admitted.message());
            rejoinLater(leader, owners(fresh)); // its own rollback handed the companions back already
            String line = voice.forAdmit(admitted.message(), leader.getName());
            String finishingName = DeskLines.stillFinishing(admitted.message());
            if (finishingName != null) {
                for (Character member : members) {
                    if (member.getName().equals(finishingName)) {
                        finishing.put(leader.getId(), new Finishing(member.getId(), line, now));
                    }
                }
                desk.stillFinishing(leader, fresh, line, clickedAt);
            } else {
                desk.outcome(leader, line, clickedAt);
            }
            return;
        }
        AgentCompanionRunSession session = admitted.session();
        if (session instanceof AgentKpqSession kpq) {
            kpq.setCompanionAdopted(true); // HPQ's admission flags its session before it publishes it
        }
        AgentPqPaceProfile paced = pace.profile(fresh.pqKey());
        if (paced != null) {
            // a heavy stage-1 draw is weighed for a re-roll at the rate this lineup was priced with
            session.setPaceCalibration(pace.calibration(paced, leader.getId()));
        }
        session.setMemberCoordinationChatEnabled(world.config().COMPANION_PQ_GUIDANCE);
        CompanionRun run = register(new CompanionRun(session.sessionId(), fresh, now));
        notePotionsAtYes(run);
        int firstAgent = firstAgent(fresh);
        if (!commit(run)) {
            abort(leader, run, firstAgent, DeskLines.CATCHING_BREATH, clickedAt);
            return;
        }
        if (!port.holds(run.pqKey, firstAgent, run.sessionId)) {
            endRun(run); // released meanwhile
            desk.outcome(leader, voice.generic(), clickedAt);
            return;
        }
        PqEntryPort.Start started = startInstance(leader, run.pqKey);
        if (started != PqEntryPort.Start.STARTED) {
            abort(leader, run, firstAgent, voice.forStart(started), clickedAt);
            return;
        }
        run.started = true;
    }

    /** Stops the session {@code admitted} registered, if any, before any run of it was ({@code why}: the log's). */
    private void stopAdmitted(LineupPlan fresh, PqEntryPort.Admitted admitted, String why) {
        if (admitted != null && admitted.success()) {
            port.forceStop(firstAgent(fresh), "companion " + label(fresh.pqKey()) + " " + why,
                    System.currentTimeMillis());
        }
    }

    /** The first companion of the lineup: the one its session is looked up and stopped by. */
    private static int firstAgent(LineupPlan plan) {
        return plan.lineup().stream().filter(seat -> !seat.human()).findFirst().orElseThrow().characterId();
    }

    /**
     * The quest's start (KerningPQ, HenesysPQ); one that throws (EventManager.startInstance rethrows a setup script's
     * failure) did not start, and ends as an event error does: the session stopped, the extras called back and the
     * leader answered.
     */
    private PqEntryPort.Start startInstance(Character leader, String pqKey) {
        try {
            return port.startInstance(pqKey, leader);
        } catch (RuntimeException failure) {
            log.warn("companion {} start for {} failed", label(pqKey), leader.getName(), failure);
            return PqEntryPort.Start.NO_EVENT;
        }
    }

    /**
     * The lineup's characters when the leader still leads a party of exactly the lineup, everyone online at the
     * quest's entrance and no session of the quest holding any of them; else null.
     */
    private List<Character> present(Character leader, LineupPlan fresh) {
        PqEntrance entrance = PqEntrance.of(fresh.pqKey());
        Party party = leader.getParty();
        if (leader.getClient() == null || party == null || party.getLeaderId() != leader.getId()) {
            return null;
        }
        Set<Integer> ids = new HashSet<>();
        for (PartyCharacter member : party.getMembers()) {
            ids.add(member.getId());
        }
        if (!ids.equals(fresh.memberIds()) || !entrance.registrationBlocker(leader.getId(), ids).isEmpty()) {
            return null;
        }
        List<Character> members = new ArrayList<>();
        for (LineupPlan.Seat seat : fresh.lineup()) {
            Character chr = seat.characterId() == leader.getId() ? leader
                    : seat.human() ? world.roster().onlineCharacter(seat.characterId())
                    : AgentRuntimeIdentityRuntime.bot(AgentRuntimeRegistry.findByAgentCharacterId(seat.characterId()));
            if (chr == null || chr.getMapId() != entrance.definition().recruitMapId()) {
                return null;
            }
            members.add(chr);
        }
        return members;
    }

    /** Ties the run's companions and extras to it; false when one is tied to another run already. */
    private boolean commit(CompanionRun run) {
        Commitments commitments = world.commitments();
        for (Map<Integer, Integer> held : List.of(run.ownerByCompanion, run.ownerByExtra)) {
            for (Map.Entry<Integer, Integer> companion : held.entrySet()) {
                if (!commitments.commit(run.key, companion.getValue(), companion.getKey())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The start failed after admission: the session is stopped (its end hands the companions back and ends the run,
     * CompanionRunLifecycle), or, with no session left to stop, the run is ended here. Then the leader hears why.
     */
    private void abort(Character leader, CompanionRun run, int firstAgent, String line, long clickedAt) {
        port.forceStop(firstAgent, "companion " + label(run.pqKey) + " start failed", System.currentTimeMillis());
        endRun(run);
        desk(run.pqKey).outcome(leader, line, clickedAt);
    }

    private static Set<Integer> owners(LineupPlan plan) {
        Set<Integer> owners = new LinkedHashSet<>();
        for (List<LineupPlan.Seat> seats : List.of(plan.lineup(), plan.extras())) {
            for (LineupPlan.Seat seat : seats) {
                if (!seat.human()) {
                    owners.add(seat.ownerId());
                }
            }
        }
        return owners;
    }

    /** {@code line}, the answer to the leader's click at {@code pqKey}'s desk, from companion-ops: on its IO thread. */
    private void answerOnIo(Character leader, String pqKey, String line, long clickedAt) {
        Client client = leader.getClient();
        if (client != null) {
            client.runOnIoThread(() -> desk(pqKey).outcome(leader, line, clickedAt));
        }
    }

    /** On companion-ops: the owners' companions waiting outside rejoin them (those no run holds). */
    private void rejoin(Collection<Integer> owners) {
        for (int ownerId : owners) {
            Character owner = world.roster().onlineCharacter(ownerId);
            if (owner != null) {
                world.roster().rejoinHolds(owner);
            }
        }
    }

    private void rejoinLater(Character leader, Set<Integer> owners) {
        if (!owners.isEmpty()) {
            world.executor().submit("pq.rejoin " + leader.getId(), () -> rejoin(owners));
        }
    }

    // ---- the runs ------------------------------------------------------------------------------

    private CompanionRun register(CompanionRun run) {
        runsBySession.put(run.sessionId, run);
        runsByKey.put(run.key, run);
        for (int id : run.characterIds()) {
            runByCharacter.put(id, run);
        }
        return run;
    }

    CompanionRun runForSession(String sessionId) {
        return sessionId == null ? null : runsBySession.get(sessionId);
    }

    CompanionRun runByKey(String key) {
        return key == null ? null : runsByKey.get(key);
    }

    CompanionRun runForCharacter(int characterId) {
        return runByCharacter.get(characterId);
    }

    /** True while the leader's Yes is being carried out (their companions are on their way in). */
    boolean entering(int leaderId) {
        return entering.contains(leaderId);
    }

    /** True while the character is in a companion run, or is the leader whose Yes is being carried out (!setlevel). */
    public boolean busyFor(int characterId) {
        return runByCharacter.containsKey(characterId) || entering.contains(characterId);
    }

    Collection<CompanionRun> runs() {
        return List.copyOf(runsBySession.values());
    }

    /** True while the character is a run's lineup companion, tapped out or not, loaded or not. */
    boolean runCompanion(int characterId) {
        CompanionRun run = runByCharacter.get(characterId);
        return run != null && run.ownerByCompanion.containsKey(characterId);
    }

    /** True while the character is a run's companion asked to tap out: its recovery only stops it. */
    boolean tappingOut(int characterId) {
        CompanionRun run = runByCharacter.get(characterId);
        return run != null && run.tappingOut.contains(characterId);
    }

    /** Takes the session's run out (once); null when there is none, or it was taken already. */
    CompanionRun takeRun(String sessionId) {
        CompanionRun run = sessionId == null ? null : runsBySession.remove(sessionId);
        if (run != null) {
            runsByKey.remove(run.key, run);
            for (int id : run.characterIds()) {
                runByCharacter.remove(id, run);
            }
        }
        return run;
    }

    /** {@link #endRun(CompanionRun, AgentPqPace)} with no pace reported (a start that failed, a lost session). */
    void endRun(CompanionRun run) {
        endRun(run, null);
    }

    /**
     * The run is over: taken out (once), and {@link #finish finished} on companion-ops with {@code pace}, how it went
     * as its session reported it (null: not reported).
     */
    void endRun(CompanionRun run, AgentPqPace pace) {
        if (takeRun(run.sessionId) == run) {
            world.executor().submit("pq.run_ended " + run.key, () -> finish(run, pace));
        }
    }

    /**
     * On companion-ops, once a run is over: its commitments end (a companion finishing it for a friend parks, the
     * party changes that waited go through), and the owners already out of the event get their held companions back.
     * An owner still inside gets them as they come out (RosterService.ownerEnteredMap). A run that got in goes on its
     * companions' records (a re-roll does not), and with its pace on the clear-time records; a re-roll is remembered
     * for Lakelis's greeting, which any other end of the leader's runs forgets.
     */
    void finish(CompanionRun run, AgentPqPace pace) {
        world.commitments().releaseKey(run.key);
        rejoin(run.owners); // an owner still in the event instance: none yet
        long now = System.currentTimeMillis();
        if (run.rerolled) {
            rerolledAt.put(run.leaderId, now); // Lakelis greets him back
        } else if (run.started) {
            rerolledAt.remove(run.leaderId);
        }
        records.record(run, now);
        ledger.record(run, pace, now);
    }

    /**
     * The run's session while its quest's registry holds it, else null. Admission makes the lineup's leader its
     * operator, and the operator stays its index for as long as the session lives: a member (the leader too, once the
     * lead passed and they left) is unindexed as it is ejected while the session goes on without it.
     */
    static PqEntrance.LiveSession liveSession(CompanionRun run) {
        return PqEntrance.of(run.pqKey).session(run.leaderId, run.sessionId);
    }

    /**
     * On companion-ops, from the audit: a run whose session is gone without ending it (no runEnded came; no session of
     * that id is registered in its quest's registry any more) is let go once it has been gone
     * {@link #LOST_RUN_AFTER_MS}. Only the session counts: a companion that tapped out, or a leader who passed the lead
     * and left, has left the session while the run goes on without them.
     */
    void sweepLostRuns(long nowMs) {
        for (CompanionRun run : runs()) {
            if (liveSession(run) != null) {
                run.goneSinceMs = 0L;
            } else if (run.goneSinceMs == 0L) {
                run.goneSinceMs = Math.max(1L, nowMs);
            } else if (nowMs - run.goneSinceMs >= LOST_RUN_AFTER_MS) {
                log.warn("companion {} run {} lost its session; letting its companions go", label(run.pqKey), run.key);
                endRun(run);
            }
        }
    }

    /** A quest's key as the log names it: "KPQ", "HPQ". */
    private static String label(String pqKey) {
        return pqKey.toUpperCase(Locale.ROOT);
    }

    /** EventInstanceManager.clearPQ (the stage-5 clear): the run of any of these players is cleared. */
    public void cleared(List<Character> players) {
        for (Character chr : players) {
            CompanionRun run = chr == null ? null : runByCharacter.get(chr.getId());
            if (run != null) {
                run.cleared = true;
            }
        }
    }

    /** A character died: a death of a run's member inside the event counts for the run's record. */
    public void died(Character chr) {
        CompanionRun run = runByCharacter.get(chr.getId());
        if (run != null && chr.getEventInstance() != null) {
            run.deaths.merge(chr.getId(), 1, Integer::sum);
        }
    }
}
