package server.living.imperfections;

import client.Character;
import server.TimerManager;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.population.ChatterEngine;
import server.living.population.CitizenSession;
import server.living.population.Population;
import server.living.roster.Commitments;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The living world as the imperfections gate sees it, from memory: citizens (the population registry), companions (a
 * persona with an owner), party content (a companion's run commitment, a citizen's party quest or expedition), cheap
 * items (the Free Market price index, read only) and the bots' lines. A citizen speaks through the chatter engine
 * (chatter/time.yaml, its voice and typing delay); a companion says its persona's own line for the moment, else one of
 * {@link #LINES}, in party chat a moment later.
 */
final class ImperfectionsHost implements Imperfections.Host {
    /** A companion's lines when its persona has none of its own for the moment. */
    static final Map<String, List<String>> LINES = Map.of(
            "rez.ask.self", List.of("rez pls?", "rez?", "anyone got rez", "rezz pls"),
            "rez.thanks.self", List.of("ty!!", "ty for rez", "thx", "tyty"),
            "afk.brb", List.of("brb", "sec", "brb 1 min", "afk sec"),
            "afk.back", List.of("back", "ok back", "b"));
    static final long COMPANION_LINE_MIN_MS = 1_200L;
    static final long COMPANION_LINE_SPAN_MS = 1_300L;

    private final LivingWorld world;

    ImperfectionsHost(LivingWorld world) {
        this.world = world;
    }

    @Override
    public boolean citizen(int characterId) {
        Population population = Population.get();
        return population != null && population.isCitizen(characterId);
    }

    @Override
    public boolean companion(int characterId) {
        Persona persona = world.residents().personaOfCharacterId(characterId);
        return persona != null && world.roster().cachedOwnerOf(persona.id()) != 0;
    }

    @Override
    public boolean partyContent(int characterId) {
        Commitments commitments = world.commitments();
        if (commitments != null && commitments.isCommitted(characterId)) {
            return true;
        }
        Population population = Population.get();
        return population != null && population.partyQuests().holds(characterId);
    }

    @Override
    public boolean cheap(int itemId) {
        Population population = Population.get();
        return population != null && population.market().atOrUnderMedian(itemId);
    }

    @Override
    public void say(Character bot, String moment) {
        Population population = Population.get();
        CitizenSession session = population == null ? null : population.presence().session(bot.getId());
        if (session != null) {
            population.chatter().event(ChatterEngine.Event.of(moment, session.mapId, session.channel).self(session));
            return;
        }
        Persona persona = world.residents().personaOfCharacterId(bot.getId());
        if (persona == null) {
            return;
        }
        List<String> own = world.arcs().lines(persona, moment);
        List<String> lines = own.isEmpty() ? LINES.getOrDefault(moment, List.of()) : own;
        if (lines.isEmpty()) {
            return;
        }
        String line = lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
        long delay = COMPANION_LINE_MIN_MS + ThreadLocalRandom.current().nextLong(COMPANION_LINE_SPAN_MS);
        TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY,
                () -> world.roster().sayInParty(bot, line), delay);
    }
}
