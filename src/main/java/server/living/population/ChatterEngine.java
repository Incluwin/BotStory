package server.living.population;

import client.Character;
import net.server.Server;
import net.server.guild.Guild;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.agents.capabilities.dialogue.AgentChatTextSanitizer;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.agents.integration.AgentPacketGatewayRuntime;
import server.integration.AgentPresence;
import server.living.population.PopulationContent.Intent;
import server.living.population.PopulationContent.Lobby;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Zero-cost chat (plan 5): world events propose templated lines to citizens in range; a citizen
 * speaks with a probability from its traits, subject to per-citizen cooldowns, per-map line
 * budgets and a memory of recent templates; the line is filled, rendered through the voice pack
 * and delivered with a typing delay on the right channel. Nothing here calls a model.
 */
public final class ChatterEngine {
    private static final Logger log = LoggerFactory.getLogger(ChatterEngine.class);
    private static final long SELF_COOLDOWN_MS = 15_000L;
    private static final long INTENT_COOLDOWN_DEFAULT_MS = 300_000L;
    private static final int MAX_REACTORS = 2;
    private static final double DWELL_BASE_CHANCE = 0.05d;
    /** Shorter map names than this ("fm") are not looked for inside chatter lines. */
    private static final int MIN_PLACE_NAME = 4;

    /** Something that may make nearby citizens talk. */
    public static final class Event {
        final String trigger;
        final int mapId;
        final int channel;
        CitizenSession self;
        String subjectName;
        int subjectLevel;
        int subjectId;
        String item;
        String job;
        String text;
        int need;

        private Event(String trigger, int mapId, int channel) {
            this.trigger = trigger;
            this.mapId = mapId;
            this.channel = channel;
        }

        public static Event of(String trigger, int mapId, int channel) {
            return new Event(trigger, mapId, channel);
        }

        public Event self(CitizenSession session) {
            this.self = session;
            return this;
        }

        public Event subject(Character chr) {
            if (chr != null) {
                this.subjectName = chr.getName();
                this.subjectLevel = chr.getLevel();
                this.subjectId = chr.getId();
                this.job = chr.getJob().name().toLowerCase(Locale.ROOT).replace('_', ' ');
            }
            return this;
        }

        public Event subject(String name, int level) {
            this.subjectName = name;
            this.subjectLevel = level;
            return this;
        }

        /** The {job} slot as players type it ("white knight"), over the subject's enum name. */
        public Event job(String spokenJob) {
            this.job = spokenJob;
            return this;
        }

        public Event item(String itemName) {
            this.item = itemName;
            return this;
        }

        public Event withText(String fixedText) {
            this.text = fixedText;
            return this;
        }

        /** The {need} slot: how many more a party quest's party wants ("lf{need}m kpq"). */
        public Event need(int more) {
            this.need = more;
            return this;
        }
    }

    private final Population population;
    private final Random random;
    /** "channel:mapId" -> token bucket for map chat density. */
    private final Map<String, double[]> buckets = new ConcurrentHashMap<>();
    private volatile long lastSmegaMs;
    private volatile int smegasThisHour;
    private volatile long hourStartMs;
    private volatile long linesSpoken;
    private final java.util.Deque<String> recent = new java.util.ArrayDeque<>();

    ChatterEngine(Population population) {
        this.population = population;
        this.random = new Random(population.config().SEED ^ 0xc4a77e2L);
    }

    void reload() {
        buckets.clear();
    }

    public long linesSpoken() {
        return linesSpoken;
    }

    /** The last few lines citizens said anywhere (newest last), for ops and the dashboard. */
    public List<String> recentLines() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    private void remember(String line) {
        synchronized (recent) {
            recent.addLast(line);
            while (recent.size() > 30) {
                recent.removeFirst();
            }
        }
    }

    // ---- entry points --------------------------------------------------------------------

    public void onLogin(CitizenSession session) {
        if (!population.config().CHATTER_ENABLED) {
            return;
        }
        event(Event.of("login", session.mapId, session.channel).self(session));
        event(Event.of("enter", session.mapId, session.channel).self(session));
        population.social().greetBuddies(session);
    }

    public void onLogout(CitizenSession session) {
        if (!population.config().CHATTER_ENABLED) {
            return;
        }
        event(Event.of("logout", session.mapId, session.channel).self(session));
    }

    /** Called by hooks and services; never blocks the caller for long. */
    public void event(Event event) {
        if (!population.config().CHATTER_ENABLED || event == null) {
            return;
        }
        try {
            switch (event.trigger) {
                case "levelup.other", "death.other", "drop.rare", "boss", "jobadvance.other" -> react(event);
                case "login.buddy", "party.invite", "party.forming" -> speakSelf(event, false);
                default -> speakSelf(event, true);
            }
        } catch (RuntimeException failure) {
            log.debug("chatter event {} failed", event.trigger, failure);
        }
    }

    /** Every 20 s: idle chatter for loaded citizens, and the megaphone cadence. */
    public void tick() {
        if (!population.config().CHATTER_ENABLED) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            for (CitizenSession session : population.presence().sessions()) {
                if (session.virtual || session.afkUntilMs > now || session.agent() == null) {
                    continue;
                }
                double sociability = sociability(session) / 100.0d;
                if (random.nextDouble() >= DWELL_BASE_CHANCE * (0.4d + sociability)) {
                    continue;
                }
                speakSelf(Event.of("dwell", session.mapId, session.channel).self(session), true);
            }
            smegaCadence(now);
        } catch (RuntimeException failure) {
            log.debug("chatter tick failed", failure);
        }
    }

    // ---- selection -----------------------------------------------------------------------

    private void react(Event event) {
        MapleMap map = population.map(event.channel, event.mapId);
        if (map == null) {
            return;
        }
        List<CitizenSession> candidates = new ArrayList<>();
        for (CitizenSession session : population.presence().sessions()) {
            if (session.virtual || session.mapId != event.mapId || session.channel != event.channel
                    || session.characterId == event.subjectId || session.afkUntilMs > 0L || session.agent() == null) {
                continue;
            }
            candidates.add(session);
        }
        if (candidates.isEmpty()) {
            return;
        }
        java.util.Collections.shuffle(candidates, random);
        int spoken = 0;
        for (CitizenSession session : candidates) {
            if (spoken >= MAX_REACTORS) {
                break;
            }
            double chance = 0.25d + sociability(session) / 200.0d;
            if (random.nextDouble() < chance && speak(session, event, event.trigger, false)) {
                spoken++;
            }
        }
    }

    private void speakSelf(Event event, boolean respectIntentCooldown) {
        CitizenSession session = event.self;
        if (session == null || session.virtual) {
            return;
        }
        speak(session, event, event.trigger, !respectIntentCooldown);
    }

    private boolean speak(CitizenSession session, Event event, String trigger, boolean urgent) {
        long now = System.currentTimeMillis();
        String where = population.fields().kind(session.mapId);
        List<Intent> fitting = new ArrayList<>();
        int total = 0;
        int level = session.agent() == null ? 0 : session.agent().getLevel();
        // A party quest's own talk ("lf2m kpq" again, "gg") repeats as players repeat it: no memory of recent intents,
        // the self cooldown on its channel, and each intent's own cooldown. A boss expedition's talk too.
        boolean partyQuest = trigger.startsWith("pq.") || trigger.startsWith("boss.");
        for (Intent intent : population.content().intents(trigger)) {
            if (!intent.where().equals("any") && !intent.where().equals(where) && !intent.mapIds().contains(session.mapId)) {
                continue;
            }
            if (intent.maxLevel() > 0 && level > intent.maxLevel()) {
                continue; // "how do i get to orbis" from a level-90 reads scripted
            }
            if (!channelAvailable(session, intent.channel())) {
                continue;
            }
            if (!partyQuest && session.recentIntents.contains(intent.id())) {
                continue;
            }
            Long lastIntent = session.lastSpokeAtMs.get("intent:" + intent.id());
            if (lastIntent != null && now - lastIntent < (intent.cooldownMs() > 0 ? intent.cooldownMs() : INTENT_COOLDOWN_DEFAULT_MS)) {
                continue;
            }
            fitting.add(intent);
            total += Math.max(1, intent.weight());
        }
        if (fitting.isEmpty()) {
            return false;
        }
        int roll = random.nextInt(total);
        Intent chosen = fitting.get(fitting.size() - 1);
        for (Intent intent : fitting) {
            roll -= Math.max(1, intent.weight());
            if (roll < 0) {
                chosen = intent;
                break;
            }
        }
        String channel = chosen.channel();
        Long lastOnChannel = session.lastSpokeAtMs.get(channel);
        long cooldown = urgent || partyQuest || trigger.endsWith(".self") ? SELF_COOLDOWN_MS
                : population.config().CHATTER_CITIZEN_COOLDOWN_MS;
        if (lastOnChannel != null && now - lastOnChannel < cooldown) {
            return false;
        }
        if (channel.equals("map") && !takeToken(session.channel, session.mapId, where, now)) {
            return false;
        }
        List<String> lines = elsewhere(chosen.linesFor(population.voices().styleOf(session.citizen)), session.mapId);
        String template = event.text != null ? event.text : lines.get(random.nextInt(lines.size()));
        String filled = fill(template, session, event);
        if (filled == null || filled.isBlank()) {
            return false;
        }
        String rendered = population.voices().render(session.citizen, filled, now ^ chosen.id().hashCode());
        session.lastSpokeAtMs.put(channel, now);
        session.lastSpokeAtMs.put("intent:" + chosen.id(), now);
        session.recentIntents.addLast(chosen.id());
        while (session.recentIntents.size() > population.config().CHATTER_RECENT_MEMORY) {
            session.recentIntents.removeFirst();
        }
        long delay = urgent ? 400L : population.voices().delayMs(session.citizen, rendered);
        deliver(session, channel, rendered, delay);
        if (channel.equals("map")) {
            population.conversation().overheard(session, filled, delay); // another citizen may answer its question
        }
        return true;
    }

    /** The lines that do not name the map the citizen stands on ("how do i get to ellinia" is not asked in Ellinia). */
    private List<String> elsewhere(List<String> lines, int mapId) {
        String here = population.fields().name(mapId).toLowerCase(Locale.ROOT).split(" ")[0]; // "kerning" of "Kerning City"
        if (here.length() < MIN_PLACE_NAME) {
            return lines;
        }
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (!line.toLowerCase(Locale.ROOT).contains(here)) {
                out.add(line);
            }
        }
        return out.isEmpty() ? lines : out;
    }

    private boolean channelAvailable(CitizenSession session, String channel) {
        Character agent = session.agent();
        if (agent == null) {
            return false;
        }
        return switch (channel) {
            case "map" -> true;
            case "party" -> agent.getParty() != null;
            case "guild" -> agent.getGuildId() > 0;
            case "buddy" -> population.social().onlineBuddyIds(agent).length > 0;
            case "smega" -> smegaAllowed(System.currentTimeMillis());
            default -> false;
        };
    }

    private boolean takeToken(int channel, int mapId, String where, long now) {
        int perMinute = population.config().CHATTER_TOWN_LINES_PER_MINUTE;
        double rate = ("town".equals(where) || "fm".equals(where) || "lobby".equals(where) ? perMinute : Math.max(1, perMinute / 3)) / 60_000.0d;
        double[] bucket = buckets.computeIfAbsent(channel + ":" + mapId, k -> new double[]{perMinute, now});
        synchronized (bucket) {
            bucket[0] = Math.min(perMinute, bucket[0] + (now - bucket[1]) * rate);
            bucket[1] = now;
            if (bucket[0] < 1.0d) {
                return false;
            }
            bucket[0] -= 1.0d;
            return true;
        }
    }

    // ---- slots ---------------------------------------------------------------------------

    private String fill(String template, CitizenSession session, Event event) {
        if (!template.contains("{")) {
            return template;
        }
        Map<String, String> slots = new LinkedHashMap<>();
        Character agent = session.agent();
        int level = agent == null ? 0 : agent.getLevel();
        slots.put("me", session.name());
        slots.put("name", event.subjectName != null ? event.subjectName : session.name());
        slots.put("level", String.valueOf(event.subjectLevel > 0 ? event.subjectLevel : level));
        slots.put("map", population.fields().name(session.mapId).toLowerCase(Locale.ROOT));
        slots.put("job", event.job != null ? event.job : agent == null ? "adventurer" : agent.getJob().name().toLowerCase(Locale.ROOT).replace('_', ' '));
        slots.put("region", session.citizen.homeRegion().replace('-', ' '));
        Guild guild = agent == null || agent.getGuildId() <= 0 ? null : Server.getInstance().getGuild(agent.getGuildId());
        slots.put("guild", guild == null ? "our guild" : guild.getName());
        slots.put("motto", population.social().motto(agent == null ? 0 : agent.getGuildId()));
        Lobby lobby = population.fields().lobbyAt(session.mapId);
        if (lobby == null) {
            lobby = population.fields().lobbyFor(session.citizen, level);
        }
        slots.put("pq", lobby == null ? "pq" : lobby.name());
        slots.put("levels", lobby == null ? String.valueOf(level) : lobby.levelMin() + "-" + lobby.levelMax());
        if (event.need > 0) {
            slots.put("need", String.valueOf(event.need));
        }
        Market.Listing listing = event.item != null ? null : population.market().talkAbout(session, random);
        slots.put("item", event.item != null ? event.item : listing == null ? "stuff" : listing.name());
        slots.put("price", listing == null ? "cheap" : Market.formatPrice(listing.price()));
        String out = template;
        for (Map.Entry<String, String> slot : slots.entrySet()) {
            out = out.replace("{" + slot.getKey() + "}", slot.getValue());
        }
        return out.contains("{") ? null : out;
    }

    private int sociability(CitizenSession session) {
        return population.social().sociability(session.citizen);
    }

    // ---- delivery ------------------------------------------------------------------------

    private void deliver(CitizenSession session, String channel, String text, long delayMs) {
        deliver(session, channel, text, delayMs, null);
    }

    /** {@code atSend} (may be null) gets the line at send time and returns what to send instead, or null to drop it. */
    private void deliver(CitizenSession session, String channel, String text, long delayMs, UnaryOperator<String> atSend) {
        Runnable send = () -> {
            try {
                Character agent = session.agent();
                if (agent == null || population.presence().session(session.characterId) == null) {
                    return;
                }
                String sent = atSend == null ? text : atSend.apply(text);
                if (sent == null) {
                    return;
                }
                String clean = AgentChatTextSanitizer.sanitize(sent);
                switch (channel) {
                    case "party" -> AgentDialogueTransportRuntime.sayPartyNow(agent, clean);
                    case "guild" -> {
                        Guild guild = Server.getInstance().getGuild(agent.getGuildId());
                        if (guild != null) {
                            guild.guildChat(agent.getName(), agent.getId(), clean);
                        }
                    }
                    case "buddy" -> {
                        World world = population.gameWorld();
                        int[] online = population.social().onlineBuddyIds(agent);
                        if (world != null && online.length > 0) {
                            world.buddyChat(online, agent.getId(), agent.getName(), clean);
                        }
                    }
                    case "smega" -> {
                        Server.getInstance().broadcastMessage(population.world().config().WORLD,
                                PacketCreator.serverNotice(3, session.channel, agent.getName() + " : " + clean, false));
                        lastSmegaMs = System.currentTimeMillis();
                    }
                    default -> AgentDialogueTransportRuntime.sayMapNow(agent, clean);
                }
                linesSpoken++;
                session.journal.add("said", "said \"" + clean + "\"");
                remember(session.name() + " [" + channel + " " + population.fields().name(session.mapId) + "]: " + clean);
                log.debug("[chatter] {} ({}) @{}: {}", session.name(), channel, session.mapId, clean);
            } catch (RuntimeException failure) {
                log.debug("chatter delivery failed for {}", session.name(), failure);
            }
        };
        TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY, send, Math.max(1L, delayMs));
    }

    /**
     * A citizen replies on map chat in its voice with its typing delay (greetings, conversation templates): no intent
     * picker or map line budget, because someone spoke to it, but nothing while chatter is off and at most one line per
     * self cooldown, so "hi mia" typed every second gets one "hey".
     */
    public void say(CitizenSession session, String text) {
        long now = System.currentTimeMillis();
        if (!mayReply(session, now)) {
            return;
        }
        String rendered = population.voices().render(session.citizen, text, now);
        deliver(session, "map", rendered, population.voices().delayMs(session.citizen, rendered));
    }

    /** {@link #say}'s gate: chatter is on and the citizen said nothing on map chat for the self cooldown; takes the slot. */
    boolean mayReply(CitizenSession session, long now) {
        Long last = session.lastSpokeAtMs.get("map");
        if (!population.config().CHATTER_ENABLED || last != null && now - last < SELF_COOLDOWN_MS) {
            return false;
        }
        session.lastSpokeAtMs.put("map", now);
        return true;
    }

    /**
     * A content answer already in the citizen's voice ({@link VoicePacks#renderAnswer}), sent on map chat after
     * {@code delayMs}; {@code atSend} may drop it then (the asker left) or swap it ("^" when a human answered first).
     */
    public void answer(CitizenSession session, String text, long delayMs, UnaryOperator<String> atSend) {
        deliver(session, "map", text, delayMs, atSend);
    }

    /** Whisper to a human from a citizen (used by social and conversation). */
    public void whisper(CitizenSession session, Character human, String text) {
        Character agent = session.agent();
        if (agent == null || human == null || human.getClient() == null) {
            return;
        }
        String rendered = population.voices().render(session.citizen, text, System.currentTimeMillis());
        TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY, () -> {
            if (population.presence().session(session.characterId) != null && human.getClient() != null) {
                AgentPacketGatewayRuntime.packets().sendWhisperReceive(human, agent.getName(), session.channel - 1, false,
                        AgentChatTextSanitizer.sanitize(rendered));
                linesSpoken++;
            }
        }, population.voices().delayMs(session.citizen, rendered));
    }

    // ---- megaphones ----------------------------------------------------------------------

    private boolean smegaAllowed(long now) {
        if (now - hourStartMs > 3_600_000L) {
            hourStartMs = now;
            smegasThisHour = 0;
        }
        return smegasThisHour < population.config().CHATTER_SMEGA_PER_HOUR && now - lastSmegaMs > 600_000L;
    }

    private void smegaCadence(long now) {
        if (!smegaAllowed(now)) {
            return;
        }
        double perTick = population.config().CHATTER_SMEGA_PER_HOUR / 180.0d; // ticks per hour at 20 s
        if (random.nextDouble() >= perTick) {
            return;
        }
        List<CitizenSession> speakers = new ArrayList<>();
        for (CitizenSession session : population.presence().sessions()) {
            Character agent = session.agent();
            if (session.virtual || agent == null) {
                continue;
            }
            if (session.citizen.has(Citizen.FLAG_RECRUITER) && agent.getGuildId() > 0 || session.citizen.has(Citizen.FLAG_MERCHANT)) {
                speakers.add(session);
            }
        }
        if (speakers.isEmpty()) {
            return;
        }
        CitizenSession speaker = speakers.get(random.nextInt(speakers.size()));
        if (speak(speaker, Event.of("smega", speaker.mapId, speaker.channel).self(speaker), "smega", true)) {
            smegasThisHour++;
        }
    }

    /** Humans on the same map instance as a session (for "is anyone listening" decisions). */
    static boolean anyHuman(MapleMap map) {
        if (map == null) {
            return false;
        }
        for (Character chr : map.getAllPlayers()) {
            if (!AgentPresence.isAgent(chr)) {
                return true;
            }
        }
        return false;
    }
}
