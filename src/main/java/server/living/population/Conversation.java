package server.living.population;

import client.Character;
import net.server.Server;
import net.server.guild.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.integration.typesafe.JevAnswer;
import server.agents.integration.typesafe.JevClient;
import server.agents.integration.typesafe.JevRequest;
import server.agents.integration.typesafe.JevResponse;
import server.agents.social.conversation.AgentSocialDialogueRequestFactory;
import server.agents.social.conversation.AgentSocialDialogueRuntime;
import server.agents.social.conversation.AgentSocialDialogueRuntime.Styled;
import server.integration.AgentPresence;
import server.living.population.PopulationContent.Career;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Conversation that feels like a person, bounded (plan 6). A human line near citizens goes through
 * four tiers: silence (the voice pack's ignore rate), templates (greetings, "where is" questions),
 * one Jev judgment when it is unclear who was addressed, and finally the existing social dialogue
 * runtime with a persona card, journal, life summary and scene installed as grounding. Every
 * generated reply is styled by the citizen's voice and delayed like typing; budgets are per player,
 * per citizen and global.
 */
public final class Conversation {
    private static final Logger log = LoggerFactory.getLogger(Conversation.class);
    private static final int NEAR_PX = 700;
    private static final int CLOSE_PX = 260;
    private static final String JEV_KIND = "citizen-address";
    private static final String QUESTION = "addressee";
    private static final double ADDRESS_CONFIDENCE = 0.55d;
    private static final Pattern GREETING = Pattern.compile("^(hi|hello|hey|yo|sup|hiya|heyy+|hii+)( there| all| everyone| guys)?[!?.]*$");
    private static final Pattern WHERE = Pattern.compile("\\b(where|how do i get to|how to get to|which way)\\b");
    private static final Pattern COMMAND_LIKE = Pattern.compile("^(follow|grind|stop|come|attack|buff|trade|unequip|equip|go|wait|sentry|patrol)\\b");

    private final Population population;
    private final Random random;
    private final Semaphore inFlight;
    private final Map<Integer, int[]> playerBudget = new ConcurrentHashMap<>();
    private final Map<Integer, int[]> jevBudget = new ConcurrentHashMap<>();
    private final Map<Integer, Long> citizenCooldown = new ConcurrentHashMap<>();
    private final AtomicLong generatedTurns = new AtomicLong();
    private final AtomicLong templatedTurns = new AtomicLong();
    private final AtomicLong judgments = new AtomicLong();
    private final AtomicLong ignored = new AtomicLong();
    private final AtomicLong estimatedInputTokens = new AtomicLong();

    Conversation(Population population) {
        this.population = population;
        this.random = new Random(population.config().SEED ^ 0xc0117L);
        this.inFlight = new Semaphore(Math.max(1, population.config().CONVERSATION_MAX_CONCURRENT));
    }

    void installSeams() {
        AgentSocialDialogueRequestFactory.installGrounding(this::facts);
        AgentSocialDialogueRuntime.installStyler(this::style);
    }

    public String report() {
        return "conversation: generated=" + generatedTurns.get() + " templated=" + templatedTurns.get() + " judgments=" + judgments.get()
                + " ignored=" + ignored.get() + " est.input.tokens=" + estimatedInputTokens.get() + " mode=" + AgentSocialDialogueRuntime.mode();
    }

    // ---- entry point ---------------------------------------------------------------------

    /** A human spoke in map chat. True when a citizen took the line (it will answer or deliberately ignore it). */
    public boolean onChat(Character speaker, String text) {
        if (!population.config().CONVERSATION_ENABLED || speaker == null || AgentPresence.isAgent(speaker) || text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        if (COMMAND_LIKE.matcher(normalized).find()) {
            return false; // companion commands belong to the companion pipeline
        }
        List<CitizenSession> near = nearby(speaker);
        if (near.isEmpty()) {
            return false;
        }
        CitizenSession addressee = byName(near, normalized);
        boolean explicit = addressee != null;
        if (addressee == null) {
            addressee = byProximity(speaker, near, normalized);
        }
        if (addressee == null && (normalized.endsWith("?") || GREETING.matcher(normalized).matches())) {
            addressee = judge(speaker, near, text);
        }
        if (addressee == null) {
            return false;
        }
        respond(addressee, speaker, text, normalized, explicit);
        return true;
    }

    private void respond(CitizenSession session, Character speaker, String text, String normalized, boolean explicit) {
        long now = System.currentTimeMillis();
        session.journal.add("talk", speaker.getName() + " said \"" + trim(text, 60) + "\"");
        // tier 0: silence, unless called by name
        if (!explicit && population.voices().ignores(session.citizen, now ^ speaker.getId())) {
            ignored.incrementAndGet();
            return;
        }
        // tier 1: templates for the formulaic
        if (GREETING.matcher(normalized).matches()) {
            population.chatter().event(ChatterEngine.Event.of("enter", session.mapId, session.channel).self(session)
                    .withText(random.nextBoolean() ? "hey " + speaker.getName() : "hi"));
            templatedTurns.incrementAndGet();
            return;
        }
        if (WHERE.matcher(normalized).find()) {
            String hint = directions(normalized);
            if (hint != null) {
                population.chatter().event(ChatterEngine.Event.of("enter", session.mapId, session.channel).self(session).withText(hint));
                templatedTurns.incrementAndGet();
                return;
            }
        }
        // tier 3: generation, budgeted
        Long cooldown = citizenCooldown.get(session.characterId);
        if (cooldown != null && now - cooldown < population.config().CONVERSATION_CITIZEN_COOLDOWN_MS) {
            return;
        }
        if (!spend(playerBudget, speaker.getId(), population.config().CONVERSATION_PER_PLAYER_PER_DAY, now)) {
            population.chatter().event(ChatterEngine.Event.of("enter", session.mapId, session.channel).self(session)
                    .withText(random.nextBoolean() ? "gtg, ttyl" : "brb"));
            templatedTurns.incrementAndGet();
            return;
        }
        if (session.entry == null || !inFlight.tryAcquire()) {
            return;
        }
        citizenCooldown.put(session.characterId, now);
        generatedTurns.incrementAndGet();
        try {
            AgentSocialDialogueRuntime.maybeRespond(session.entry, speaker, text);
        } finally {
            // the semaphore bounds submissions per cooldown window rather than in-flight requests,
            // which the dialogue application already caps; release after a short hold
            server.TimerManager.getInstance().schedule(server.TimerManager.SchedulerLane.LOW_PRIORITY, inFlight::release, 4_000L);
        }
    }

    // ---- addressee -----------------------------------------------------------------------

    private List<CitizenSession> nearby(Character speaker) {
        List<CitizenSession> near = new ArrayList<>();
        for (CitizenSession session : population.presence().sessions()) {
            Character agent = session.agent();
            if (session.virtual || agent == null || agent.getMap() == null || speaker.getMap() == null
                    || agent.getMapId() != speaker.getMapId() || agent.getClient() == null || speaker.getClient() == null
                    || agent.getClient().getChannel() != speaker.getClient().getChannel()) {
                continue;
            }
            if (agent.getPosition().distance(speaker.getPosition()) <= NEAR_PX) {
                near.add(session);
            }
        }
        return near;
    }

    private CitizenSession byName(List<CitizenSession> near, String normalized) {
        for (CitizenSession session : near) {
            if (normalized.contains(session.name().toLowerCase(Locale.ROOT))) {
                return session;
            }
        }
        return null;
    }

    private CitizenSession byProximity(Character speaker, List<CitizenSession> near, String normalized) {
        if (near.size() != 1) {
            return null;
        }
        CitizenSession only = near.get(0);
        Character agent = only.agent();
        if (agent == null || agent.getPosition().distance(speaker.getPosition()) > CLOSE_PX) {
            return null;
        }
        return normalized.endsWith("?") || GREETING.matcher(normalized).matches() || random.nextInt(100) < 40 ? only : null;
    }

    /** Tier 2: one Jev Choice over the nearby names plus "nobody", budgeted per player per day. */
    private CitizenSession judge(Character speaker, List<CitizenSession> near, String text) {
        JevClient client = JevClient.runtime();
        if (!client.available() || near.size() > 6) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (!spend(jevBudget, speaker.getId(), population.config().CONVERSATION_JEV_PER_PLAYER_PER_DAY, now)) {
            return null;
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("message", text);
        List<Map<String, Object>> players = new ArrayList<>();
        for (CitizenSession session : near) {
            Character agent = session.agent();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", session.name());
            row.put("distance_px", agent == null ? 999 : (int) agent.getPosition().distance(speaker.getPosition()));
            row.put("doing", session.activity == null ? "standing" : session.activity.name().toLowerCase(Locale.ROOT));
            players.add(row);
        }
        state.put("nearby_players", players);
        Map<String, Object> instructions = new LinkedHashMap<>();
        instructions.put("question", "Which nearby player, if any, is `message` addressed to?");
        instructions.put("inspect", "`message`; `nearby_players` is context");
        instructions.put("focus", "Public map chat in MapleStory. Most lines are not addressed to anyone in particular.");
        Map<String, Object> criteria = new LinkedHashMap<>();
        for (CitizenSession session : near) {
            criteria.put(session.name(), "the line is clearly meant for " + session.name() + " (named, replied to, or the only person close by being asked something)");
        }
        criteria.put("nobody", "general chat, a joke, a sale, or a line for someone who is not listed");
        judgments.incrementAndGet();
        Optional<JevResponse> response = client.ask(JevRequest.builder(state).kind(JEV_KIND).choice(QUESTION, instructions, criteria).build());
        if (response.isEmpty()) {
            return null;
        }
        Optional<JevAnswer> answer = response.get().answer(QUESTION);
        if (answer.isEmpty() || answer.get().mostLikelyOption().isEmpty()) {
            return null;
        }
        String option = answer.get().mostLikelyOption().get();
        if (option.equals("nobody") || answer.get().probabilityOf(option) < ADDRESS_CONFIDENCE) {
            return null;
        }
        for (CitizenSession session : near) {
            if (session.name().equals(option)) {
                return session;
            }
        }
        return null;
    }

    private boolean spend(Map<Integer, int[]> budgets, int characterId, int limit, long now) {
        int day = (int) (now / 86_400_000L);
        int[] budget = budgets.computeIfAbsent(characterId, id -> new int[]{day, 0});
        synchronized (budget) {
            if (budget[0] != day) {
                budget[0] = day;
                budget[1] = 0;
            }
            if (budget[1] >= limit) {
                return false;
            }
            budget[1]++;
            return true;
        }
    }

    // ---- templates -----------------------------------------------------------------------

    private String directions(String normalized) {
        String[][] known = {
                {"ellinia", "ellinia is east of henesys, keep going right past the hunting grounds"},
                {"perion", "perion is north of henesys, up through the rocky path"},
                {"kerning", "kerning city is west of henesys, past the construction site"},
                {"henesys", "henesys is in the middle of victoria, all roads lead there"},
                {"lith", "lith harbor is on the west coast, take the taxi from henesys"},
                {"sleepywood", "sleepywood is south through the dungeon entrance from henesys"},
                {"orbis", "take the ship from ellinia station, it leaves every so often"},
                {"free market", "fm entrance is in every town, look for the portal"},
                {"fm", "fm entrance is in every town, look for the portal"},
                {"job", "job advancement npc is in the job town: henesys for bowmen, ellinia for mages, perion warriors, kerning thieves"},
                {"ludi", "orbis first, then the tower down to ludibrium"},
                {"el nath", "orbis, then take the ship to el nath"},
        };
        for (String[] entry : known) {
            if (normalized.contains(entry[0])) {
                return entry[1];
            }
        }
        return null;
    }

    // ---- grounding -----------------------------------------------------------------------

    /** Facts handed to the model for a citizen (persona card, journal, life summary, scene, rules). */
    Map<String, String> facts(int agentCharacterId, int speakerCharacterId) {
        Map<String, String> facts = new LinkedHashMap<>();
        Citizen citizen = population.citizen(agentCharacterId);
        CitizenSession session = population.presence().session(agentCharacterId);
        if (citizen == null) {
            return facts;
        }
        Character agent = session == null ? null : session.agent();
        Career career = population.content().career(citizen.career());
        StringBuilder card = new StringBuilder();
        card.append(citizen.name()).append(", a ").append(agent == null ? "player" : agent.getJob().name().toLowerCase(Locale.ROOT).replace('_', ' '))
                .append(agent == null ? "" : " level " + agent.getLevel());
        if (agent != null && agent.getGuildId() > 0) {
            Guild guild = Server.getInstance().getGuild(agent.getGuildId());
            card.append(" in the guild ").append(guild == null ? "" : guild.getName());
        }
        card.append(", from ").append(citizen.homeRegion().replace('-', ' ')).append(", plays ").append(citizen.cohort().replace('-', ' '));
        if (career != null) {
            card.append(", wants to reach level ").append(Math.min(200, (agent == null ? 10 : agent.getLevel()) + 5)).append(" as a ")
                    .append(career.job().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        facts.put("who i am", card.toString());
        if (!citizen.lifeSummary().isBlank()) {
            facts.put("my recent weeks", citizen.lifeSummary());
        }
        if (session != null && !session.journal.isEmpty()) {
            facts.put("what happened this session", session.journal.summary(220));
        }
        if (session != null && agent != null && agent.getMap() != null) {
            int humans = Population.humansIn(agent.getMap());
            facts.put("scene", "at " + population.fields().name(session.mapId).toLowerCase(Locale.ROOT) + ", "
                    + (session.activity == null ? "hanging around" : session.activity.name().toLowerCase(Locale.ROOT))
                    + ", " + humans + " other player(s) nearby");
        }
        facts.put("rules", "one short line like a real player would type; never mention being an AI or a bot; you cannot give items or meso through chat");
        return facts;
    }

    /** Voice pack over generated text plus a typing delay; null for characters that are not citizens. */
    Styled style(int agentCharacterId, int speakerCharacterId, String text, boolean fromModel) {
        Citizen citizen = population.citizen(agentCharacterId);
        if (citizen == null || text == null) {
            return null;
        }
        String rendered = population.voices().render(citizen, text, text.hashCode());
        if (fromModel) {
            estimatedInputTokens.addAndGet(1_500L);
        }
        CitizenSession session = population.presence().session(agentCharacterId);
        if (session != null) {
            session.journal.add("talk", "replied \"" + trim(rendered, 60) + "\"");
        }
        return new Styled(rendered, population.voices().delayMs(citizen, rendered));
    }

    private static String trim(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }
}
