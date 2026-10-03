package server.living.roster.ui;

import client.Character;
import client.Client;
import server.agents.capabilities.partyquest.AgentPartyQuestDefinition;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.resident.LivingTalk;
import server.living.resident.LivingTalk.Option;
import server.living.roster.CompanionPartyQuestService;
import server.living.roster.CompanionView;
import server.living.roster.DeskLines;
import server.living.roster.DeskVoice;
import server.living.roster.LineupPlan;
import server.living.roster.LineupPlan.Seat;
import server.living.roster.LineupPlan.Waiting;
import server.living.roster.PqEntrance;
import server.living.roster.RecallTravel;
import server.living.roster.RosterResult;
import server.living.roster.RosterVoice;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A party quest's entry NPC at her entrance, for a party leader with companions in the party (P6, flow 3; P6b HPQ
 * step 6): Lakelis at the KPQ entrance, Tory at the HPQ one, one desk per entrance (PqEntrance), each in her own voice
 * (DeskVoice) and by her quest's seats, levels and map. The lineup she would send in, the leader's own pick of it, a
 * companion sent for from the leader's bench, and how a start that did not go through ended. Each window is the answer
 * to the leader's own click: the first to the click on her (the stock script hands it over,
 * LivingTalk.answerScriptClick), every later one to a pick in the window before, so none is ever drawn over another (a
 * second NPC_TALK drops the v83 client). Every window is read afresh from memory
 * ({@link CompanionPartyQuestService#plan}, the Crew menus' row cache), and she always speaks from her own portrait.
 * Nothing here admits or starts a run: Yes hands the lineup to the service (which admits HPQ on companion-ops).
 */
public final class EntryDesk implements CompanionPartyQuestService.Desk {
    /** An outcome later than this after its click is pink text: the start can take the stock 7.8 s wait for a lobby. */
    static final long ANSWER_MS = 10_000L;

    private final LivingWorld world;
    private final CompanionPartyQuestService service;
    private final CompanionMenus menus;
    private final PqEntrance entrance;
    private final DeskVoice voice;
    private final PqEntrance.SeatRules seats;

    public EntryDesk(LivingWorld world, CompanionPartyQuestService service, CompanionMenus menus, PqEntrance entrance) {
        this.world = world;
        this.service = service;
        this.menus = menus;
        this.entrance = entrance;
        this.voice = entrance.voice();
        this.seats = entrance.seatRules();
    }

    // ---- the lineup ----------------------------------------------------------------------------

    /**
     * Who she would send in, how long she thinks it takes and who would struggle (with a clear time), who waits with
     * her and who stays behind where they are; Yes only when they can go in now, "Are they here yet?" while a
     * companion called over is still on its way. Asked again, the lineup is planned the way it was drawn: the leader's
     * pick stays theirs.
     */
    @Override
    public void proposal(Character leader, LineupPlan plan, String topLine) {
        String pqKey = plan.pqKey();
        List<Option> options = new ArrayList<>();
        if (plan.startable()) {
            options.add(new Option("Yes, send us in.", () -> service.start(leader, plan)));
        }
        if (!plan.onTheWay().isEmpty()) {
            options.add(new Option("Are they here yet?",
                    () -> proposal(leader, service.plan(leader, pqKey, plan.picked()), null)));
        }
        options.add(new Option("Let me choose...", () -> choose(leader, pqKey, companions(plan), null)));
        if (plan.benchUseful() && benched(leader).stream().anyMatch(Benched::callable)) {
            options.add(new Option("Call someone from my bench...", () -> bench(leader, plan)));
        }
        options.add(new Option("Never mind.", null));
        talk(leader).menu(lineupText(plan, topLine), options).show();
    }

    /** A Yes found the party changed since the lineup was drawn: the lineup as it is now. */
    @Override
    public void thingsChanged(Character leader, LineupPlan plan) {
        proposal(leader, plan, CompanionMenus.THINGS_CHANGED);
    }

    private String lineupText(LineupPlan plan, String topLine) {
        List<String> lines = new ArrayList<>();
        lines.add(voice.title());
        if (topLine != null) {
            lines.add(topLine);
        }
        lines.add(voice.lineupHeader(plan.lineup().size()));
        for (Seat seat : plan.lineup()) {
            lines.add("#b" + seat.name() + "#k" + (seat.characterId() == plan.leaderId() ? " (leader)" : about(plan, seat)));
        }
        lines.addAll(voice.clearTime(plan)); // how long it should take, and who would struggle; none without
        if (!plan.extras().isEmpty()) {
            Map<Integer, String> reasons = new HashMap<>();
            for (Waiting member : plan.waiting()) {
                reasons.put(member.characterId(), member.reason());
            }
            List<String> here = new ArrayList<>();
            List<String> away = new ArrayList<>(); // another map, another channel, or on the road
            for (Seat extra : plan.extras()) {
                String reason = reasons.get(extra.characterId());
                String why = reason != null ? reason : extra.errand(); // "(at the store)": gone for potions
                (extra.here() ? here : away).add(extra.name() + (why == null ? "" : " (" + why + ")"));
            }
            if (!here.isEmpty()) {
                lines.add("Waiting here with me: " + String.join(", ", here));
            }
            if (!away.isEmpty()) {
                lines.add("Staying behind: " + String.join(", ", away));
            }
        }
        if (!plan.onTheWay().isEmpty()) {
            List<String> store = new ArrayList<>(); // gone for potions, their own restock
            List<String> coming = new ArrayList<>(); // called over, from the bench
            for (Seat seat : plan.onTheWay()) {
                (seat.errand() != null ? store : coming).add(seat.name() + " (" + RecallTravel.describe(seat.etaMs()) + ")");
            }
            if (!store.isEmpty()) {
                lines.add("Gone to the store: " + String.join(", ", store));
            }
            if (!coming.isEmpty()) {
                lines.add("On the way: " + String.join(", ", coming));
            }
        }
        if (plan.blocker() != null) {
            lines.add("#r" + voice.blockerHeader() + "#k " + plan.blocker());
        }
        return String.join("\r\n", lines) + "\r\n";
    }

    /** ", Lv24 Hunter (Ann's) - cleared 2 of 3": another owner's companion names its owner; its record once it has one. */
    private static String about(LineupPlan plan, Seat seat) {
        String owner = "";
        if (!seat.human() && seat.ownerId() != plan.leaderId()) {
            for (Seat human : plan.lineup()) {
                if (human.human() && human.characterId() == seat.ownerId()) {
                    owner = " (" + human.name() + "'s)";
                }
            }
        }
        String record = seat.clearRecord();
        return (seat.level() <= 0 ? "" : ", Lv" + seat.level() + (seat.jobName().isEmpty() ? "" : " " + seat.jobName()))
                + owner + (record == null ? "" : " - " + record);
    }

    /** The companions of the lineup: the leader's pick of it, as the lineup stands. */
    private static Set<Integer> companions(LineupPlan plan) {
        Set<Integer> ids = new HashSet<>();
        for (Seat seat : plan.lineup()) {
            if (!seat.human()) {
                ids.add(seat.characterId());
            }
        }
        return ids;
    }

    // ---- the leader's pick ---------------------------------------------------------------------

    /**
     * The leader picks who goes in: every companion that could go (here or on its way) is in or out, the humans always
     * go, and one that cannot come says why. Unpicked companions wait outside. {@code prompt}: null, or her line when
     * a pick would go past her quest's most (a fifth at Lakelis, a seventh at Tory). Under her question, a pick her
     * quest takes with a clear time hears how long it should take (and how much sooner hers would be).
     */
    private void choose(Character leader, String pqKey, Set<Integer> picked, String prompt) {
        LineupPlan plan = service.plan(leader, pqKey, picked);
        Set<Integer> in = companions(plan);
        Set<Integer> cannot = new HashSet<>();
        List<String> reasons = new ArrayList<>();
        for (Waiting member : plan.waiting()) {
            cannot.add(member.characterId());
            reasons.add(member.name() + " (" + member.reason() + ")");
        }
        List<String> humans = new ArrayList<>();
        List<Seat> toggles = new ArrayList<>();
        for (Seat seat : plan.lineup()) {
            if (seat.human()) {
                humans.add(seat.name());
            } else {
                toggles.add(seat);
            }
        }
        for (Seat extra : plan.extras()) {
            if (!cannot.contains(extra.characterId())) {
                toggles.add(extra);
            }
        }
        toggles.sort(Comparator.comparing(Seat::name)); // a pick moves nobody in the list
        List<Option> options = new ArrayList<>();
        options.add(Option.heading("Going in for sure: " + String.join(", ", humans)));
        for (Seat seat : toggles) {
            boolean going = in.contains(seat.characterId());
            options.add(new Option((going ? "(in) " : "(out) ") + seat.name() + about(plan, seat), () -> {
                Set<Integer> next = new HashSet<>(in);
                if (going) {
                    next.remove(seat.characterId());
                } else if (plan.lineup().size() >= seats.max()) {
                    choose(leader, pqKey, next, voice.atMost());
                    return;
                } else {
                    next.add(seat.characterId());
                }
                choose(leader, pqKey, next, null);
            }));
        }
        if (!reasons.isEmpty()) {
            options.add(Option.heading("Can't come: " + String.join(", ", reasons)));
        }
        int size = plan.lineup().size();
        if (size >= seats.min() && size <= seats.max()) {
            // these go in now when they can; else the lineup says why (one of them still on its way, say)
            options.add(new Option("Send these in.", () -> {
                if (plan.startable()) {
                    service.start(leader, plan);
                } else {
                    proposal(leader, service.plan(leader, pqKey, in), null);
                }
            }));
        }
        String clearTime = prompt == null ? voice.chooseLine(plan) : null; // the pick's time, under the question
        talk(leader).menu((prompt == null ? voice.whoGoesIn() : prompt) + (clearTime == null ? "" : "\r\n" + clearTime)
                        + "\r\n", options)
                .back(() -> proposal(leader, service.plan(leader, pqKey, in), null))
                .show();
    }

    // ---- sent for from the bench ---------------------------------------------------------------

    /**
     * One of the leader's benched or away companions; callable when it is in her quest's levels (its admission lets
     * nobody else in: KPQ 21-30, HPQ 10-255).
     */
    private record Benched(Persona persona, CompanionView view, AgentPartyQuestDefinition definition) {
        boolean callable() {
            return view.level() >= definition.minimumLevel() && view.level() <= definition.maximumLevel();
        }
    }

    /** The leader's benched and away companions, from the Crew menus' row cache. */
    private List<Benched> benched(Character leader) {
        List<Benched> benched = new ArrayList<>();
        for (RosterEntry entry : menus.rows(leader)) {
            Persona persona = entry.state() == RosterState.BENCHED || entry.state() == RosterState.AWAY
                    ? world.content().persona(entry.personaId()) : null;
            if (persona != null) {
                benched.add(new Benched(persona, menus.view(leader, entry), entrance.definition()));
            }
        }
        return benched;
    }

    /**
     * The leader's benched and away companions in her quest's levels, to send for; the rest are named under them. It
     * is offered only with one to send for. A pick calls the companion (join-then-travel) on companion-ops; Back is
     * the lineup {@code shown} again, planned the way it was drawn.
     */
    private void bench(Character leader, LineupPlan shown) {
        String pqKey = shown.pqKey();
        List<Option> options = new ArrayList<>();
        List<String> notReady = new ArrayList<>();
        for (Benched companion : benched(leader)) {
            CompanionView view = companion.view();
            if (!companion.callable()) {
                notReady.add(view.name() + " (Lv" + view.level() + ")");
            } else {
                options.add(new Option(CompanionMenus.benchRow(view), () -> call(leader, pqKey, companion.persona())));
            }
        }
        if (!notReady.isEmpty()) {
            options.add(Option.heading("Not ready for this: " + String.join(", ", notReady)));
        }
        talk(leader).menu(voice.benchPrompt() + "\r\n", options)
                .back(() -> proposal(leader, service.plan(leader, pqKey, shown.picked()), null))
                .show();
    }

    /**
     * Calls the companion on companion-ops. One that sets off is on its way (and says her arrival line to the party
     * as it arrives); anything else ("your party is full") tops the lineup again. Either way she chooses again, so a
     * companion that joined is not left out by a pick made before it.
     */
    private void call(Character leader, String pqKey, Persona persona) {
        AtomicBoolean travelling = new AtomicBoolean();
        menus.command(leader, "pq.call " + persona.id(), () -> {
            RosterResult called = world.roster().call(leader, persona);
            if (called instanceof RosterResult.Travelling) {
                travelling.set(true);
                noteCalled(leader.getId(), persona.id());
            }
            return RosterVoice.line(persona, called);
        }, line -> {
            if (travelling.get()) {
                onTheWay(leader, pqKey, line);
            } else {
                proposal(leader, service.plan(leader, pqKey), line);
            }
        });
    }

    /** The call that set off is noted at her desk: Lakelis's own note, as before, or her quest's desk's. */
    private void noteCalled(int ownerId, String personaId) {
        if ("kpq".equals(entrance.key())) {
            service.noteCalledForLakelis(ownerId, personaId);
        } else {
            service.noteCalledForDesk(ownerId, personaId, entrance.key());
        }
    }

    /** "Rin is on the way (~40 s). Talk to me again once they're here." */
    private void onTheWay(Character leader, String pqKey, String line) {
        talk(leader).menu(line + " Talk to me again once they're here.\r\n", List.of(
                new Option("Are they here yet?", () -> proposal(leader, service.plan(leader, pqKey), null)),
                new Option("Never mind.", null))).show();
    }

    // ---- how a start ended ----------------------------------------------------------------------

    /**
     * A start that did not go through, on the leader's I/O thread: her OK window while it answers the click (within
     * {@link #ANSWER_MS}), nothing is on screen and the leader is still at the entrance (her quest's recruit map);
     * else pink text in her name.
     */
    @Override
    public void outcome(Character leader, String line, long clickedAtMs) {
        answer(leader, line, clickedAtMs, () -> talk(leader).ok(line).show());
    }

    /** A companion still finishing what it was doing: the lineup again under her line, so the next Yes is one click. */
    @Override
    public void stillFinishing(Character leader, LineupPlan plan, String line, long clickedAtMs) {
        answer(leader, line, clickedAtMs, () -> proposal(leader, plan, line));
    }

    /** {@code window} as the answer to the click, as {@link #outcome} draws it; else her line in pink text. */
    private void answer(Character leader, String line, long clickedAtMs, Runnable window) {
        Client client = leader.getClient();
        if (client == null) {
            return; // logged off meanwhile
        }
        client.runOnIoThread(() -> {
            if (System.currentTimeMillis() - clickedAtMs <= ANSWER_MS && LivingTalk.mayShow(leader)
                    && leader.getMapId() == entrance.definition().recruitMapId()) {
                window.run();
            } else {
                leader.dropMessage(5, voice.npcName() + ": " + DeskLines.plain(line));
            }
        });
    }

    private LivingTalk talk(Character leader) {
        return LivingTalk.open(leader, entrance.entryNpcId());
    }
}
