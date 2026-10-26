package server.living.roster;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import server.agents.capabilities.partyquest.AgentPartyQuestDefinition;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.CompanionRunStats;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Who should come along?" (docs/BOT_MANAGEMENT_PLAN.md P9, learned lineup suggestions; decisions 2026-10-06): for each
 * party quest switched on whose levels take the owner, the crew its entry NPC's own rule picks ({@link LineupPlan}: the
 * level closest to the owner's, a job branch nobody in it has yet, and the clear record once a companion has three
 * runs, which changes with every recorded run; Wonky's also all five job branches when a swap gets them) from the owner,
 * the others in their party and the owner's rested bench companions in the quest's levels. A bench companion picked
 * is one to call. A crew is weighed without its clear time:
 * a companion at camp is not loaded, so its power cannot be read (the desk weighs it at the entrance). Companion-ops:
 * the rested check reads the store.
 */
public final class CrewSuggestions {
    /**
     * One quest's crew: {@code crew} as the desk would name it in order (the owner first, as "you"; a companion with a
     * clear record shows it), the owner's own companions in it ({@code own}, out or at camp) and of those the ones at
     * camp, to call.
     */
    public record Suggestion(String pqKey, List<String> crew, List<Persona> own, List<Persona> fromBench) {
    }

    private final LivingWorld world;
    private final PartySearchBench.RestedLevel rested;

    public CrewSuggestions(LivingWorld world) {
        this(world, (owner, entry, persona) -> world.crewRotation().restedLevel(owner, entry, persona));
    }

    CrewSuggestions(LivingWorld world, PartySearchBench.RestedLevel rested) {
        this.world = world;
        this.rested = rested;
    }

    /** A crew for each party quest switched on whose levels take the owner, in the order the entrances are listed. */
    public List<Suggestion> suggest(Character owner) {
        List<Suggestion> out = new ArrayList<>();
        for (String pqKey : PqEntrance.keys()) {
            Suggestion suggestion = suggest(owner, pqKey);
            if (suggestion != null) {
                out.add(suggestion);
            }
        }
        return out;
    }

    /**
     * The crew for {@code pqKey} alone (its entry NPC's bench page); null when the quest is switched off or its levels
     * do not take the owner.
     */
    public Suggestion suggest(Character owner, String pqKey) {
        if (!world.config().companionPqEnabled(pqKey)) {
            return null;
        }
        AgentPartyQuestDefinition levels = PqEntrance.of(pqKey).definition();
        if (owner.getLevel() < levels.minimumLevel() || owner.getLevel() > levels.maximumLevel()) {
            return null;
        }
        return suggest(owner, pqKey, levels);
    }

    private Suggestion suggest(Character owner, String pqKey, AgentPartyQuestDefinition levels) {
        CompanionRunRecorder records = world.partyQuests() == null ? null : world.partyQuests().records();
        Set<String> usual = world.store().lineupSlots(owner.getId()).keySet();
        List<LineupPlan.Candidate> candidates = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        candidates.add(human(owner.getId(), owner.getName(), owner.getLevel(), owner.getJob().getId()));
        seen.add(owner.getId());
        Party party = owner.getParty();
        for (PartyCharacter member : party == null ? List.<PartyCharacter>of() : party.getMembers()) {
            if (!seen.add(member.getId()) || !member.isOnline()) {
                continue;
            }
            if (!member.isBot()) {
                candidates.add(human(member.getId(), member.getName(), member.getLevel(), member.getJobId()));
                continue;
            }
            Persona persona = world.residents().personaOfCharacterId(member.getId());
            int ownerId = persona == null ? 0 : world.roster().ownerOf(persona.id());
            AgentRuntimeEntry agent = AgentRuntimeRegistry.findByAgentCharacterId(member.getId());
            Character bot = agent == null ? null : agent.bot(); // its own numbers: the party row's may lag a level-up
            int level = bot != null ? bot.getLevel() : member.getLevel();
            int jobId = bot != null && bot.getJob() != null ? bot.getJob().getId() : member.getJobId();
            if (ownerId != 0 && inLevels(level, levels)) { // a citizen guest goes in no companion run
                candidates.add(companion(member.getId(), persona, level, jobId, ownerId, 0L, LineupPlan.Fit.READY,
                        ownerId == owner.getId() && usual.contains(persona.id()), records, pqKey));
            }
        }
        Map<String, Persona> bench = new java.util.HashMap<>();
        for (RosterEntry entry : world.roster().roster(owner)) {
            Persona persona = entry.state() == RosterState.BENCHED && !CompanionLoans.lent(world, entry.personaId())
                    ? world.content().persona(entry.personaId()) : null;
            JobErrands errands = world.jobErrands();
            KeystoneErrands keystones = world.keystoneErrands();
            TicketErrands tickets = world.ticketErrands();
            if (persona == null || errands != null && errands.underway(persona.id())
                    || keystones != null && keystones.underway(persona.id())
                    || tickets != null && tickets.underway(persona.id())) {
                continue;
            }
            int level = rested.of(owner, entry, persona);
            int characterId = world.residents().characterIdOf(persona.id());
            if (level <= 0 || !inLevels(level, levels) || !seen.add(characterId)) {
                continue;
            }
            int jobId = world.store().characterLevelAndJob(characterId)[1];
            long etaMs = RecallTravel.etaMs(entry.campMapId(), owner.getMapId(), world.config().RECALL_MAX_ETA_MS);
            candidates.add(companion(characterId, persona, level, jobId, owner.getId(), etaMs, LineupPlan.Fit.ON_THE_WAY,
                    usual.contains(persona.id()), records, pqKey));
            bench.put(persona.id(), persona);
        }
        LineupPlan plan = LineupPlan.build(pqKey, owner.getId(), owner.getLevel(), candidates, null, null);
        List<String> crew = new ArrayList<>();
        List<Persona> own = new ArrayList<>();
        List<Persona> fromBench = new ArrayList<>();
        for (LineupPlan.Seat seat : plan.lineup()) {
            if (seat.personaId() != null && seat.ownerId() == owner.getId()) {
                own.add(world.content().persona(seat.personaId()));
            }
            String record = seat.clearRecord();
            crew.add(seat.characterId() == owner.getId() ? "you"
                    : seat.name() + (record == null ? "" : " (" + record + ")"));
            if (seat.personaId() != null && bench.containsKey(seat.personaId())) {
                fromBench.add(bench.get(seat.personaId()));
            }
        }
        return new Suggestion(pqKey, List.copyOf(crew), List.copyOf(own), List.copyOf(fromBench));
    }

    private static boolean inLevels(int level, AgentPartyQuestDefinition levels) {
        return level >= levels.minimumLevel() && level <= levels.maximumLevel();
    }

    private static LineupPlan.Candidate human(int id, String name, int level, int jobId) {
        return new LineupPlan.Candidate(new LineupPlan.Seat(id, name, level, jobId, CompanionView.jobName(jobId), true, 0,
                null, 0, true, 0L, 0, 0), LineupPlan.Fit.READY, null, false);
    }

    private static LineupPlan.Candidate companion(int id, Persona persona, int level, int jobId, int ownerId, long etaMs,
                                                  LineupPlan.Fit fit, boolean usual, CompanionRunRecorder records,
                                                  String pqKey) {
        CompanionRunStats record = records == null ? CompanionRunRecorder.NONE : records.stats(pqKey, id);
        return new LineupPlan.Candidate(new LineupPlan.Seat(id, persona.name(), level, jobId, CompanionView.jobName(jobId),
                false, ownerId, persona.id(), 0, false, etaMs, record.runs(), record.clears()), fit, null, usual);
    }
}
