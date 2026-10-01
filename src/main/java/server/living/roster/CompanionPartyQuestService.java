package server.living.roster;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.StatEffect;
import server.agents.capabilities.combat.AgentExpectedDamagePolicy;
import server.agents.capabilities.dialogue.AgentDialogueCatalog;
import server.agents.capabilities.dialogue.AgentDialogueSelector;
import server.agents.capabilities.inventory.AgentUseItemClassificationPolicy;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.kpq.AgentKpqAdmissionService;
import server.agents.capabilities.partyquest.kpq.AgentKpqDefinition;
import server.agents.capabilities.partyquest.kpq.AgentKpqSession;
import server.agents.capabilities.partyquest.kpq.AgentKpqSessionRegistry;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeConfig;
import server.agents.runtime.AgentRuntimeRegistry;
import server.integration.AgentPresence;
import server.life.LifeFactory;
import server.life.Monster;
import server.life.NPC;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.resident.LivingTalk;
import server.living.store.LivingStore.CompanionRunStats;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/**
 * Party quests with companions (docs/BOT_MANAGEMENT_PLAN.md P6, flow 3): a party leader who asks a listed party quest's
 * entry NPC to start it (living_world.COMPANION_PQ_ENABLED_KEYS; Lakelis for "kpq") is offered a lineup of their party
 * and companions instead of the stock start. On Yes the companions left out wait beside Lakelis (a HOLD), and the
 * lineup is admitted and started in one step on the leader's I/O thread, so the run begins with the party already
 * inside: the 45 s gather at Lakelis that 93 of 119 background runs failed never runs. The run is kept here, in memory,
 * until its session ends (CompanionRunLifecycle); its companions and extras are committed to it meanwhile. The lineup
 * windows are the desk's (roster.ui LakelisDesk, which LivingWorld installs).
 */
public final class CompanionPartyQuestService {
    private static final Logger log = LoggerFactory.getLogger(CompanionPartyQuestService.class);
    /** Lakelis, the KPQ entrance's NPC: the extras wait beside her. */
    static final int LAKELIS = 9_020_000;
    /** Live: 2 of 3 runs whose companions carried no potions lost one in stage 1. Fewer than this and it waits. */
    static final int KPQ_MIN_HP_POTIONS = 20;
    /**
     * Live (2026-09-30, two runs): Rurik and Yara, then Casse and Rurik, went in without mana potions and nothing
     * inside sells them ("no MP_POTION supplier ... from map 103000800"), so they fought on without their skills. A
     * warrior drinks about 15 a run, and its own resupply goes looking for a supplier once it is under POT_STOP (10).
     * A companion that fights with skills goes in with a run's worth above that ({@link #kpqMinMpPotions}).
     */
    static final int KPQ_MP_POTIONS_PER_RUN = 15;
    /** A companion short on mana potions asks for some at most this often (the Agents' own resupply waits as long). */
    static final long MANA_ASK_EVERY_MS = 300_000L;
    /**
     * Admission asks the activity a companion is on to stop and lets it finish its step, within 30 s (town life,
     * hunting and questing's DEFAULT_GRACEFUL_EXIT_TIMEOUT_MS, a shop or trade's drain). Until then a Yes that finds it
     * still at it is answered at once, and the extras do not step out and back again.
     */
    static final long FINISHING_MS = 30_000L;
    /** KPQ's monsters a companion is weighed against: stage 1's Ligator, stage 5's Jr. Necki and King Slime. */
    private static final int[] KPQ_MOBS = {9_300_001, 9_300_000, 9_300_003};
    /** A run whose session went away without ending it is let go once it has been gone this long (the audit's pace). */
    static final long LOST_RUN_AFTER_MS = 60_000L;
    /** A companion Lakelis sent for from the bench says it is ready for her when it arrives within this long. */
    static final long ARRIVAL_NOTE_MS = 600_000L;

    /**
     * Lakelis's windows about a lineup, each the answer to the leader's own click: the lineup itself, the lineup again
     * when a Yes found things changed, and how a start that did not go through ended.
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

    /** Until the desk is installed: no window, and an outcome is pink text. */
    private static final Desk PINK_TEXT = new Desk() {
        @Override
        public void proposal(Character leader, LineupPlan plan, String topLine) {
        }

        @Override
        public void thingsChanged(Character leader, LineupPlan plan) {
        }

        @Override
        public void outcome(Character leader, String line, long clickedAtMs) {
            leader.dropMessage(5, "Lakelis: " + LakelisLines.plain(line));
        }
    };

    private final LivingWorld world;
    private final PqEntryPort port;
    private final ToIntFunction<Character> hpPotions;
    private final ToIntFunction<Character> mpPotions;
    private final ToDoubleFunction<Character> kpqDps;
    private final CompanionRunRecorder records;
    private volatile Desk desk = PINK_TEXT;
    /** Leaders whose Yes is being carried out: a second Yes meanwhile only hears "hold on". */
    private final Set<Integer> entering = ConcurrentHashMap.newKeySet();
    private final Map<String, CompanionRun> runsBySession = new ConcurrentHashMap<>();
    private final Map<String, CompanionRun> runsByKey = new ConcurrentHashMap<>();
    /** Every lineup member and extra of a run -> the run. */
    private final Map<Integer, CompanionRun> runByCharacter = new ConcurrentHashMap<>();
    /** "ownerId personaId" -> when Lakelis sent for that companion from its owner's bench. */
    private final Map<String, Long> calledForLakelis = new ConcurrentHashMap<>();
    /** Companion character id -> when it last asked for mana potions at Lakelis. */
    private final Map<Integer, Long> askedForMana = new ConcurrentHashMap<>();
    /** Leader id -> the companion admission last found still finishing something for their Yes. */
    private final Map<Integer, Finishing> finishing = new ConcurrentHashMap<>();

    /** A companion admission asked to stop what it was doing ({@code line}: Lakelis's word for it), at {@code atMs}. */
    private record Finishing(int characterId, String line, long atMs) { }

    public CompanionPartyQuestService(LivingWorld world) {
        this(world, PqEntryPort.STOCK, CompanionPartyQuestService::hpPotions, CompanionPartyQuestService::mpPotions,
                CompanionPartyQuestService::kpqDps);
    }

    /**
     * {@code port}: the doors into the quest; {@code hpPotions}, {@code mpPotions}: what a loaded companion carries;
     * {@code kpqDps}: what it hits for in KPQ.
     */
    CompanionPartyQuestService(LivingWorld world, PqEntryPort port, ToIntFunction<Character> hpPotions,
                               ToIntFunction<Character> mpPotions, ToDoubleFunction<Character> kpqDps) {
        this.world = world;
        this.port = port;
        this.hpPotions = hpPotions;
        this.mpPotions = mpPotions;
        this.kpqDps = kpqDps;
        this.records = new CompanionRunRecorder(world);
    }

    /** The companions' KPQ records, which the lineup ranks by. */
    public CompanionRunRecorder records() {
        return records;
    }

    /** The desk's windows (roster.ui); null: none, and outcomes are pink text. */
    public void installDesk(Desk installed) {
        desk = installed == null ? PINK_TEXT : installed;
    }

    /**
     * The party leader asked {@code pqKey}'s entry NPC to start it (on their I/O thread, inside the stock script).
     * True when this answered the click (with the lineup, planned as the answer); false leaves the stock start to the
     * script, with nothing sent or changed.
     */
    public boolean offerAtDesk(Character leader, String pqKey) {
        if (!world.config().companionPqEnabled(pqKey) || standsAside(leader, pqKey)) {
            return false; // the stock start, untouched
        }
        return LivingTalk.answerScriptClick(leader, () -> {
            LineupPlan plan = plan(leader, pqKey);
            desk.proposal(leader, plan, null);
            askForMana(plan);
        });
    }

    /**
     * Before entry: a companion the lineup leaves waiting for mana potions asks for some in party chat, where its owner
     * reads it, in the Agents' own pot-share words; at most once per {@link #MANA_ASK_EVERY_MS}, said on companion-ops.
     */
    private void askForMana(LineupPlan plan) {
        long now = System.currentTimeMillis();
        for (LineupPlan.Waiting member : plan.waiting()) {
            int id = member.characterId();
            if (!LakelisLines.LOW_ON_MANA.equals(member.reason())) {
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
     * True when the stock path takes the click and nothing here is touched: an Agent clicked (a background party's
     * headless entry), there is no party or the clicker does not lead it, a KPQ session already holds the leader or a
     * member, a !kpqtest run (a TEST_OBSERVATION engagement) owns one of them, or nobody in the party is a companion.
     * Owners are looked up only for the party's bots, so a human member never costs a persona lookup.
     */
    boolean standsAside(Character leader, String pqKey) {
        if (leader == null || AgentPresence.isAgent(leader)) {
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
        if (!AgentKpqSessionRegistry.registrationBlocker(leader.getId(), ids).isEmpty()
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
     * the companions' records and the game data's KPQ monsters (what a companion that can go hits for).
     */
    public LineupPlan plan(Character leader, String pqKey, Set<Integer> picked) {
        Party party = leader.getParty();
        List<LineupPlan.Candidate> candidates = new ArrayList<>();
        for (PartyCharacter member : party == null ? List.<PartyCharacter>of() : party.getMembers()) {
            candidates.add(member.isBot() ? companion(leader, member) : human(leader, member));
        }
        return LineupPlan.build(pqKey, leader.getId(), leader.getLevel(), candidates, picked);
    }

    /** A human always goes in; one that cannot keeps the run from starting, in the words admission would refuse it. */
    private LineupPlan.Candidate human(Character leader, PartyCharacter member) {
        int id = member.getId();
        Character chr = id == leader.getId() ? leader : world.roster().onlineCharacter(id);
        if (chr == null) {
            return new LineupPlan.Candidate(seat(leader, id, member.getName(), null, true, 0, null, 0L),
                    LineupPlan.Fit.BLOCKS, LakelisLines.named(LakelisLines.NOT_HERE, member.getName()), false);
        }
        String refusal = AgentKpqAdmissionService.memberProblem(chr, id == leader.getId());
        String line = !RosterService.sameChannel(leader, chr) ? LakelisLines.THIS_CHANNEL
                : engaged(leader, id) ? LakelisLines.CATCHING_BREATH
                : refusal.isEmpty() ? null : LakelisLines.forAdmit(refusal, leader.getName());
        return new LineupPlan.Candidate(seat(leader, id, chr.getName(), chr, true, 0, null, 0L),
                line == null ? LineupPlan.Fit.READY : LineupPlan.Fit.BLOCKS, line, false);
    }

    /**
     * A bot in the party. A companion goes in when it is loaded, ACTIVE, on the leader's channel, standing, tied to no
     * run, admissible and carries its potions; one called over is on its way; one loaded otherwise waits outside with
     * its reason. A companion not loaded (logged off with a friend), one tied to a run, one whose row is not out with
     * its owner (a hold would refuse it: walking out to the bench, say) and any other Agent (a citizen, which carries
     * its own engagement) keep the run from starting.
     */
    private LineupPlan.Candidate companion(Character leader, PartyCharacter member) {
        int id = member.getId();
        Character bot = AgentRuntimeIdentityRuntime.bot(AgentRuntimeRegistry.findByAgentCharacterId(id));
        Persona persona = world.residents().personaOfCharacterId(id);
        int owner = persona == null ? 0 : world.roster().cachedOwnerOf(persona.id());
        String name = persona != null ? persona.name() : bot != null ? bot.getName() : member.getName();
        if (owner == 0) {
            return blocks(id, name, LakelisLines.NOT_IN_CREW);
        }
        if (bot == null) {
            return blocks(id, name, LakelisLines.NOT_UP);
        }
        if (world.commitments().isCommitted(id) || AgentPartyQuestEngagementRegistry.forMember(id) != null) {
            return new LineupPlan.Candidate(seat(leader, id, name, bot, false, owner, persona.id(), 0L),
                    LineupPlan.Fit.BLOCKS, LakelisLines.CATCHING_BREATH, false);
        }
        RosterEntry row = world.views().row(owner, persona.id());
        if (row == null || !CompanionSeatState.partyMember(row.state())) {
            return blocks(id, name, LakelisLines.BUSY);
        }
        boolean usual = world.views().lineup(owner).contains(persona.id());
        if (row.state() == RosterState.TRAVELLING) {
            long etaMs = Math.max(0L, row.etaAtMs() - System.currentTimeMillis());
            return new LineupPlan.Candidate(seat(leader, id, name, bot, false, owner, persona.id(), etaMs),
                    LineupPlan.Fit.ON_THE_WAY, null, usual, kpqDps.applyAsDouble(bot));
        }
        String reason = !RosterService.sameChannel(leader, bot) ? LakelisLines.OTHER_CHANNEL
                : bot.getHp() <= 0 ? LakelisLines.KNOCKED_OUT
                : waitingReason(bot);
        return new LineupPlan.Candidate(seat(leader, id, name, bot, false, owner, persona.id(), 0L),
                reason == null ? LineupPlan.Fit.READY : LineupPlan.Fit.WAITS, reason, usual,
                reason == null ? kpqDps.applyAsDouble(bot) : 0.0d);
    }

    /**
     * Why admission would refuse the companion, or why it would go in unready: low on potions, or on mana potions when
     * it fights with skills (every job past a Beginner's); null: it can go.
     */
    private String waitingReason(Character bot) {
        String problem = AgentKpqAdmissionService.memberProblem(bot, false);
        if (!problem.isEmpty()) {
            return LakelisLines.reason(problem, bot.getLevel());
        }
        if (hpPotions.applyAsInt(bot) < KPQ_MIN_HP_POTIONS) {
            return LakelisLines.LOW_ON_POTIONS;
        }
        return bot.getJob().getId() % 1000 != 0 && mpPotions.applyAsInt(bot) < kpqMinMpPotions()
                ? LakelisLines.LOW_ON_MANA : null;
    }

    /**
     * The mana potions a companion that fights with skills goes in with at least: a run's worth above the count its
     * own resupply calls critical (AgentRuntimeConfig POT_STOP), so it never asks the party for some mid-run.
     */
    static int kpqMinMpPotions() {
        return AgentRuntimeConfig.cfg.POT_STOP + KPQ_MP_POTIONS_PER_RUN;
    }

    private LineupPlan.Candidate blocks(int id, String name, String line) {
        return new LineupPlan.Candidate(seat(null, id, name, null, false, 0, null, 0L), LineupPlan.Fit.BLOCKS,
                LakelisLines.named(line, name), false);
    }

    /** The member's seat; a companion's carries its KPQ record. Here: at the entrance, on the leader's channel. */
    private LineupPlan.Seat seat(Character leader, int id, String name, Character chr, boolean human, int ownerId,
                                 String personaId, long etaMs) {
        int jobId = chr == null ? -1 : chr.getJob().getId();
        CompanionRunStats record = human ? CompanionRunRecorder.NONE : records.stats(id);
        boolean here = chr != null && chr.getMapId() == AgentKpqDefinition.RECRUIT_MAP
                && RosterService.sameChannel(leader, chr);
        return new LineupPlan.Seat(id, name, chr == null ? 0 : chr.getLevel(), jobId, CompanionView.jobName(jobId), human,
                ownerId, personaId, chr == null ? 0 : chr.getMapId(), here, etaMs, record.runs(), record.clears());
    }

    /** True while an engagement holds the character (a run's post-run recovery, say), or the leader operates one. */
    private static boolean engaged(Character leader, int id) {
        return AgentPartyQuestEngagementRegistry.forMember(id) != null
                || id == leader.getId() && AgentPartyQuestEngagementRegistry.forOperator(id) != null;
    }

    /** The HP-restoring potions in the companion's USE inventory, by quantity (CrewRotation's potion test, counted). */
    static int hpPotions(Character bot) {
        return potions(bot, true);
    }

    /** The MP-restoring potions in the companion's USE inventory, by quantity (an Elixir counts here too). */
    static int mpPotions(Character bot) {
        return potions(bot, false);
    }

    /**
     * What a loaded companion hits for in KPQ: its expected damage per second against each of {@link #KPQ_MOBS}
     * (AgentExpectedDamagePolicy: its best attack after hit chance and defense), averaged. One that cannot be
     * estimated counts as 0, so the lineup never fails on it.
     */
    static double kpqDps(Character bot) {
        try {
            double total = 0.0d;
            for (int mobId : KPQ_MOBS) {
                Monster mob = LifeFactory.getMonster(mobId);
                total += mob == null ? 0.0d : AgentExpectedDamagePolicy.damagePerSecond(bot, mob);
            }
            return total / KPQ_MOBS.length;
        } catch (RuntimeException failure) {
            log.debug("no KPQ damage estimate for {}", bot.getName(), failure);
            return 0.0d;
        }
    }

    private static int potions(Character bot, boolean hp) {
        Inventory use = bot.getInventory(InventoryType.USE);
        int count = 0;
        for (Item item : use == null ? List.<Item>of() : use.list()) {
            StatEffect effect = item.getQuantity() > 0 ? AgentUseItemClassificationPolicy.itemEffect(item.getItemId()) : null;
            if (AgentUseItemClassificationPolicy.isRecoveryPotion(effect) && (hp
                    ? effect.getHp() > 0 || effect.getHpRate() > 0 : effect.getMp() > 0 || effect.getMpRate() > 0)) {
                count += item.getQuantity();
            }
        }
        return count;
    }

    // ---- sent for from the bench ----------------------------------------------------------------

    /**
     * Lakelis sent for the owner's companion from their bench and it set off (the desk, on companion-ops): as it
     * arrives it tells the party it is ready for her instead of its usual word. There is no timer: the note is looked
     * at only when the companion arrives, and one older than {@link #ARRIVAL_NOTE_MS} is forgotten then.
     */
    public void noteCalledForLakelis(int ownerId, String personaId) {
        calledForLakelis.put(ownerId + " " + personaId, System.currentTimeMillis());
    }

    /** RosterService.arrive: true, once, when the companion arriving now was sent for from Lakelis's bench. */
    boolean takeArrivalNote(int ownerId, String personaId, long nowMs) {
        Long notedAt = calledForLakelis.remove(ownerId + " " + personaId);
        return notedAt != null && nowMs - notedAt <= ARRIVAL_NOTE_MS;
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
        if (!entering.add(leaderId)) {
            desk.outcome(leader, LakelisLines.HOLD_ON, System.currentTimeMillis());
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
            world.executor().submit("pq.prepare " + leaderId, () -> prepareEntry(leader, fresh, clickedAt));
        } catch (RuntimeException failure) {
            entering.remove(leaderId);
            throw failure;
        }
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
     * On companion-ops: every extra steps out of the party to wait outside the run (a HOLD), beside Lakelis when it is
     * at the entrance and where it stands otherwise (another map, another channel, on its way). The leader's own
     * beside her say so aloud; the rest tell their owner, never the strangers of some far map. Then the run starts on
     * the leader's I/O thread; one that cannot step out calls the others back, and so does a hold that throws (the
     * leader hears the generic line; companion-ops logs the failure).
     */
    void prepareEntry(Character leader, LineupPlan fresh, long clickedAt) {
        Set<Integer> touched = new LinkedHashSet<>();
        try {
            Point lakelis = lakelis(leader);
            int beside = 0;
            for (LineupPlan.Seat extra : fresh.extras()) {
                Character owner = world.roster().onlineCharacter(extra.ownerId());
                Persona persona = world.content().persona(extra.personaId());
                Point point = lakelis != null && extra.here() ? new Point(lakelis.x + 40 + 30 * beside++, lakelis.y)
                        : null;
                HoldNote note = extra.ownerId() != leader.getId()
                        ? HoldNote.whisper(leader.getName() + " asked me to wait outside.")
                        : extra.here() ? HoldNote.map(null) : HoldNote.whisper(null);
                RosterResult held = owner == null || persona == null ? new RosterResult.NotOwned()
                        : world.roster().hold(owner, persona, extra.mapId(), point, note);
                if (!held.ok()) {
                    log.info("companion KPQ for {}: {} could not wait outside: {}", leader.getName(), extra.name(),
                            held);
                    rejoin(touched);
                    entering.remove(leader.getId());
                    answerOnIo(leader, LakelisLines.PARTY_CHANGED, clickedAt);
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
            answerOnIo(leader, LakelisLines.GENERIC, clickedAt);
            throw failure;
        }
    }

    /** Where Lakelis stands on the leader's map; null when she is not on it. */
    private static Point lakelis(Character leader) {
        NPC npc = leader.getMap() == null ? null : leader.getMap().getNPCById(LAKELIS);
        return npc == null || npc.getPosition() == null ? null : new Point(npc.getPosition());
    }

    /**
     * On the leader's I/O thread: the party must still be the lineup, all of it at the entrance; a lineup of humans
     * only takes the stock start. Otherwise it is admitted, marked a companion run (tap-outs, guidance), committed with
     * its extras, and the event started, in one step. A start that fails after admission stops the session again. A
     * companion still finishing another activity is never waited for on this thread (live, 19:56 and 19:58 on
     * 2026-09-30, the refusals came on the Netty one): the lineup the leader said Yes to is shown again for the next
     * Yes, and the companion is noted for it ({@link #stillFinishing}).
     */
    void enter(Character leader, LineupPlan fresh, long clickedAt) {
        Set<Integer> owners = owners(fresh);
        try {
            List<Character> members = present(leader, fresh);
            if (members == null) {
                rejoinLater(leader, owners);
                desk.outcome(leader, LakelisLines.PARTY_CHANGED, clickedAt);
                return;
            }
            List<LineupPlan.Seat> companions = fresh.lineup().stream().filter(seat -> !seat.human()).toList();
            if (companions.isEmpty()) {
                PqEntryPort.Start started = startInstance(leader); // the extras rejoin as their owners come out
                if (started != PqEntryPort.Start.STARTED) {
                    rejoinLater(leader, owners);
                    desk.outcome(leader, LakelisLines.forStart(started), clickedAt);
                }
                return;
            }
            long now = System.currentTimeMillis();
            AgentKpqAdmissionService.AdmissionResult admitted =
                    port.admit(leader, members, ThreadLocalRandom.current().nextLong(), now);
            if (!admitted.success()) {
                log.info("companion KPQ admission refused for {}: {}", leader.getName(), admitted.message());
                rejoinLater(leader, owners); // its own rollback handed the companions back already
                String line = LakelisLines.forAdmit(admitted.message(), leader.getName());
                String finishingName = LakelisLines.stillFinishing(admitted.message());
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
            AgentKpqSession session = admitted.session();
            session.setCompanionAdopted(true);
            session.setMemberCoordinationChatEnabled(world.config().COMPANION_PQ_GUIDANCE);
            CompanionRun run = register(new CompanionRun(session.sessionId(), fresh, now));
            int firstAgent = companions.getFirst().characterId();
            if (!commit(run)) {
                abort(leader, run, firstAgent, LakelisLines.CATCHING_BREATH, clickedAt);
                return;
            }
            if (AgentKpqSessionRegistry.forMember(firstAgent) != session) {
                endRun(run); // released meanwhile
                desk.outcome(leader, LakelisLines.GENERIC, clickedAt);
                return;
            }
            PqEntryPort.Start started = startInstance(leader);
            if (started != PqEntryPort.Start.STARTED) {
                abort(leader, run, firstAgent, LakelisLines.forStart(started), clickedAt);
                return;
            }
            run.started = true;
        } finally {
            entering.remove(leader.getId());
        }
    }

    /**
     * KerningPQ's start; one that throws (EventManager.startInstance rethrows a setup script's failure) did not start,
     * and ends as an event error does: the session stopped, the extras called back and the leader answered.
     */
    private PqEntryPort.Start startInstance(Character leader) {
        try {
            return port.startInstance(leader);
        } catch (RuntimeException failure) {
            log.warn("companion KPQ start for {} failed", leader.getName(), failure);
            return PqEntryPort.Start.NO_EVENT;
        }
    }

    /**
     * The lineup's characters when the leader still leads a party of exactly the lineup, everyone online at the
     * entrance and no KPQ session holding any of them; else null.
     */
    private List<Character> present(Character leader, LineupPlan fresh) {
        Party party = leader.getParty();
        if (leader.getClient() == null || party == null || party.getLeaderId() != leader.getId()) {
            return null;
        }
        Set<Integer> ids = new HashSet<>();
        for (PartyCharacter member : party.getMembers()) {
            ids.add(member.getId());
        }
        if (!ids.equals(fresh.memberIds()) || !AgentKpqSessionRegistry.registrationBlocker(leader.getId(), ids).isEmpty()) {
            return null;
        }
        List<Character> members = new ArrayList<>();
        for (LineupPlan.Seat seat : fresh.lineup()) {
            Character chr = seat.characterId() == leader.getId() ? leader
                    : seat.human() ? world.roster().onlineCharacter(seat.characterId())
                    : AgentRuntimeIdentityRuntime.bot(AgentRuntimeRegistry.findByAgentCharacterId(seat.characterId()));
            if (chr == null || chr.getMapId() != AgentKpqDefinition.RECRUIT_MAP) {
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
        port.forceStop(firstAgent, "companion KPQ start failed", System.currentTimeMillis());
        endRun(run);
        desk.outcome(leader, line, clickedAt);
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

    /** {@code line} as the answer to the leader's click, from companion-ops: on their I/O thread. */
    private void answerOnIo(Character leader, String line, long clickedAt) {
        Client client = leader.getClient();
        if (client != null) {
            client.runOnIoThread(() -> desk.outcome(leader, line, clickedAt));
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

    /** The run is over: taken out (once), and {@link #finish finished} on companion-ops. */
    void endRun(CompanionRun run) {
        if (takeRun(run.sessionId) == run) {
            world.executor().submit("pq.run_ended " + run.key, () -> finish(run));
        }
    }

    /**
     * On companion-ops, once a run is over: its commitments end (a companion finishing it for a friend parks, the
     * party changes that waited go through), and the owners already out of the event get their held companions back.
     * An owner still inside gets them as they come out (RosterService.ownerEnteredMap). A run that got in goes on its
     * companions' records.
     */
    void finish(CompanionRun run) {
        world.commitments().releaseKey(run.key);
        rejoin(run.owners); // an owner still in the event instance: none yet
        records.record(run, System.currentTimeMillis());
    }

    /**
     * The run's KPQ session while it is registered, else null. Admission makes the lineup's leader its operator, and
     * the operator stays its index for as long as the session lives: a member (the leader too, once the lead passed
     * and they left) is unindexed as it is ejected while the session goes on without it.
     */
    static AgentKpqSession session(CompanionRun run) {
        AgentKpqSession session = AgentKpqSessionRegistry.forOperator(run.leaderId);
        return session != null && session.sessionId().equals(run.sessionId) ? session : null;
    }

    /**
     * On companion-ops, from the audit: a run whose KPQ session is gone without ending it (no runEnded came; no
     * session of that id is registered any more) is let go once it has been gone {@link #LOST_RUN_AFTER_MS}. Only the
     * session counts: a companion that tapped out, or a leader who passed the lead and left, has left the session
     * while the run goes on without them.
     */
    void sweepLostRuns(long nowMs) {
        for (CompanionRun run : runs()) {
            if (session(run) != null) {
                run.goneSinceMs = 0L;
            } else if (run.goneSinceMs == 0L) {
                run.goneSinceMs = Math.max(1L, nowMs);
            } else if (nowMs - run.goneSinceMs >= LOST_RUN_AFTER_MS) {
                log.warn("companion KPQ run {} lost its session; letting its companions go", run.key);
                endRun(run);
            }
        }
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
