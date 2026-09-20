package server.living.resident;

import client.Character;
import server.agents.integration.typesafe.JevAnswer;
import server.agents.integration.typesafe.JevClient;
import server.agents.integration.typesafe.JevRequest;
import server.agents.integration.typesafe.JevResponse;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.store.LivingStore.Bond;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One typed question on a deterministic miss: which of the closed set of Resident intents does
 * the player's message express? Small state (persona blurb, offered quests, bond stage, last
 * turns), per-player daily budget, per-persona cooldown and an answer cache (plan 6).
 */
public final class ResidentIntentJudge {
    public static final String REQUEST_KIND = "resident-intent";
    public static final String QUESTION = "intent";
    private static final double ACT_CONFIDENCE = 0.75d;
    private static final double ASK_CONFIDENCE = 0.45d;

    public enum Intent {
        ASK_QUEST("ask_quest", "the player asks for work, a task, a favor, something to do or help with"),
        ACCEPT("accept", "the player agrees to take on the task that was just offered"),
        TURN_IN("turn_in", "the player says the task is done, or hands over / brought what was asked"),
        ASK_JOIN("ask_join", "the player asks the resident to join them, travel with them, be hired or come along"),
        ASK_ABOUT("ask_about", "the player asks about another person, a rumor, a place or the resident's past"),
        GOODBYE("goodbye", "the player is leaving the conversation"),
        SMALL_TALK("small_talk", "friendly chatter with no request in it"),
        NOT_FOR_ME("not_for_me", "the message is not addressed to this resident or is about something else entirely");

        final String option;
        final String criteria;

        Intent(String option, String criteria) {
            this.option = option;
            this.criteria = criteria;
        }
    }

    public record Decision(Intent intent, double confidence, boolean act, boolean ask) { }

    private final LivingWorld world;
    private final Map<Integer, int[]> dailyBudget = new ConcurrentHashMap<>();
    private final Map<String, Long> personaCooldown = new ConcurrentHashMap<>();
    private final Map<String, Decision> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> cacheAt = new ConcurrentHashMap<>();

    public ResidentIntentJudge(LivingWorld world) {
        this.world = world;
    }

    /** Empty when the judge is unavailable, over budget, cooling down, or the API did not answer. */
    public Optional<Decision> judge(Character speaker, Persona persona, String message, String normalized, List<String> recentTurns) {
        JevClient client = JevClient.runtime();
        if (!client.available()) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        String cacheKey = speaker.getId() + ":" + persona.id() + ":" + normalized;
        Decision cached = cache.get(cacheKey);
        if (cached != null && now - cacheAt.getOrDefault(cacheKey, 0L) < world.config().JEV_ANSWER_CACHE_MS) {
            return Optional.of(cached);
        }
        Long cooldown = personaCooldown.get(persona.id());
        if (cooldown != null && now - cooldown < world.config().JEV_PERSONA_COOLDOWN_MS) {
            return Optional.empty();
        }
        if (!spendBudget(speaker.getId(), now)) {
            return Optional.empty();
        }
        personaCooldown.put(persona.id(), now);
        Optional<JevResponse> response = client.ask(request(speaker, persona, message, recentTurns));
        if (response.isEmpty()) {
            return Optional.empty();
        }
        Optional<JevAnswer> answer = response.get().answer(QUESTION);
        if (answer.isEmpty() || answer.get().mostLikelyOption().isEmpty()) {
            return Optional.empty();
        }
        String option = answer.get().mostLikelyOption().get();
        Intent intent = Intent.NOT_FOR_ME;
        for (Intent candidate : Intent.values()) {
            if (candidate.option.equals(option)) {
                intent = candidate;
            }
        }
        double confidence = answer.get().probabilityOf(option);
        Decision decision = new Decision(intent, confidence, confidence >= ACT_CONFIDENCE, confidence >= ASK_CONFIDENCE);
        cache.put(cacheKey, decision);
        cacheAt.put(cacheKey, now);
        return Optional.of(decision);
    }

    private boolean spendBudget(int characterId, long now) {
        int day = (int) (now / 86_400_000L);
        int[] budget = dailyBudget.computeIfAbsent(characterId, id -> new int[] {day, 0});
        synchronized (budget) {
            if (budget[0] != day) {
                budget[0] = day;
                budget[1] = 0;
            }
            if (budget[1] >= world.config().JEV_PER_PLAYER_PER_DAY) {
                return false;
            }
            budget[1]++;
            return true;
        }
    }

    public boolean overBudget(int characterId) {
        int[] budget = dailyBudget.get(characterId);
        return budget != null && budget[0] == (int) (System.currentTimeMillis() / 86_400_000L)
                && budget[1] >= world.config().JEV_PER_PLAYER_PER_DAY;
    }

    JevRequest request(Character speaker, Persona persona, String message, List<String> recentTurns) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("message", message);
        Map<String, Object> resident = new LinkedHashMap<>();
        resident.put("name", persona.name());
        resident.put("title", persona.title());
        resident.put("home", persona.homeTown());
        resident.put("recruitable", world.arcs().recruitable(persona) ? "yes" : "no");
        List<String> offered = new ArrayList<>();
        for (Quest quest : world.quests().available(speaker, persona)) {
            offered.add(quest.title());
        }
        resident.put("tasks_it_can_offer", offered);
        Quest pending = world.quests().pendingOffer(speaker, persona);
        resident.put("task_just_offered", pending == null ? "none" : pending.title());
        resident.put("tasks_player_is_doing_for_it", world.quests().activeFrom(speaker, persona).size());
        state.put("resident", resident);
        Map<String, Object> player = new LinkedHashMap<>();
        Bond bond = world.bonds().peek(speaker.getId(), persona.id());
        player.put("relationship", bond == null ? "stranger" : bond.stage().name().toLowerCase(Locale.ROOT));
        player.put("recent_turns", recentTurns);
        state.put("speaker", player);
        Map<String, Object> instructions = new LinkedHashMap<>();
        instructions.put("question", "What does the player want from this town resident with `message`?");
        instructions.put("inspect", "`message`; use `resident` and `speaker` only as context");
        instructions.put("focus", "The resident is a townsperson who can offer tasks, accept finished tasks, and sometimes agree to travel with the player.");
        Map<String, Object> criteria = new LinkedHashMap<>();
        for (Intent intent : Intent.values()) {
            criteria.put(intent.option, intent.criteria);
        }
        return JevRequest.builder(state).kind(REQUEST_KIND).choice(QUESTION, instructions, criteria).build();
    }
}
