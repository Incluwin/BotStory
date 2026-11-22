package server.living.population.paper;

import client.Character;
import server.integration.AgentPresence;
import server.living.population.ChatterEngine;
import server.living.population.CitizenSession;
import server.living.population.PersonMemories;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Citizens in town talk about the paper on its first day (docs/WORLD_SYSTEMS_PLAN.md N1.3, chatter/paper.yaml). It
 * rides the 20 s chatter tick: a citizen in a town that would say an idle line instead says one of the issue's top
 * three headlines that nobody on its map has said in the last hour ({@code paper.headline}, its gist in {@code {gist}}),
 * so each headline is heard at most once per town map per hour. First, though, a player it knows who stands on its map
 * and is named in the issue hears "saw u in the paper" ({@code paper.named}), once per player and issue. Lines go out
 * only where a player stands to hear them.
 */
public final class PaperTalk {
    static final int HEADLINES = 3;
    static final long HEADLINE_GAP_MS = 3_600_000L;
    static final String HEADLINE_TRIGGER = "paper.headline";
    static final String NAMED_TRIGGER = "paper.named";

    private final PaperDesk desk;
    private final Predicate<Integer> town;
    private final Function<CitizenSession, List<Character>> mapPlayers;
    private final Predicate<ChatterEngine.Event> speak;
    private final PersonMemories memories;
    private final Map<String, Long> saidAt = new ConcurrentHashMap<>();
    private final Set<String> named = ConcurrentHashMap.newKeySet();
    private volatile int week = Integer.MIN_VALUE;

    /**
     * {@code town}: whether a map id is a town; {@code mapPlayers}: the characters on a session's map; {@code speak}:
     * the chatter engine's own speakTo (cooldowns, the map's line budget); {@code memories}: who a citizen knows.
     */
    public PaperTalk(PaperDesk desk, Predicate<Integer> town, Function<CitizenSession, List<Character>> mapPlayers,
                     Predicate<ChatterEngine.Event> speak, PersonMemories memories) {
        this.desk = desk;
        this.town = town;
        this.mapPlayers = mapPlayers;
        this.speak = speak;
        this.memories = memories;
    }

    /** The dwelling citizen talks about the paper; true when it said something (its idle line is then not said). */
    public boolean talk(CitizenSession session, long nowMs) {
        if (desk == null || !desk.on() || !town.test(session.mapId)) {
            return false;
        }
        Optional<PaperIssue> fresh = desk.fresh(nowMs);
        if (fresh.isEmpty()) {
            return false;
        }
        PaperIssue issue = fresh.get();
        if (week != issue.weekOf()) { // a new issue: last week's hours and names are done with
            week = issue.weekOf();
            saidAt.clear();
            named.clear();
        }
        List<Character> humans = mapPlayers.apply(session).stream().filter(chr -> !AgentPresence.isAgent(chr)).toList();
        if (humans.isEmpty()) {
            return false;
        }
        if (memories != null && memories.on()) {
            for (Character human : humans) {
                String key = human.getId() + "";
                if (issue.names(human.getName()) && memories.peek(session.characterId, human.getId()) != null
                        && !named.contains(key)
                        && speak.test(ChatterEngine.Event.of(NAMED_TRIGGER, session.mapId, session.channel)
                                .self(session).subject(human))) {
                    named.add(key);
                    return true;
                }
            }
        }
        List<PaperIssue.Story> top = issue.headlines(HEADLINES);
        for (int i = 0; i < top.size(); i++) {
            String key = session.mapId + ":" + i;
            Long last = saidAt.get(key);
            if (last != null && nowMs - last < HEADLINE_GAP_MS) {
                continue;
            }
            if (speak.test(ChatterEngine.Event.of(HEADLINE_TRIGGER, session.mapId, session.channel).self(session)
                    .slot("gist", top.get(i).gist()))) {
                saidAt.put(key, nowMs);
                return true;
            }
            return false; // its cooldowns or the map's budget: not now
        }
        return false;
    }
}
