package server.living.population;

import client.Character;
import net.server.Server;
import net.server.guild.Guild;
import net.server.guild.GuildCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.integration.AgentPresence;
import server.living.population.CitizenSession.Activity;
import server.living.population.PopulationContent.Lobby;
import server.living.population.records.Clear;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guild chat that answers (docs/WORLD_SYSTEMS_PLAN.md N5.1, {@code GUILD_CHAT_ENABLED}). A human's guild line
 * (MultiChatHandler, multi-chat 2) is heard by the online citizens of their guild, and at most
 * {@link #REPLIERS_MAX} of them answer, each after a human-looking pause plus its typing delay: a greeting or a
 * goodbye gets one or two templated lines, a question or a line naming one of them one turn of the conversation path
 * (Conversation.guildTurn: the same budgets, answered back in guild chat), other lines only now and then. A human
 * member logging in is greeted and one logging out is seen off (not on a channel change); a member's level-up and a
 * clear with a human member in it get a "gz". An LFG line ("anyone up for kpq") ends in a real party: a guild mate in
 * a party forming for that quest has its leader invite them (CitizenPartyQuests.acceptRunInvite, as a yes to a run
 * invitation, N3.1); else a free guild mate whose level fits goes to the lobby (CitizenPartyQuests.send) and, once its
 * party forms, the leader invites them. Citizens of another guild never answer. No model call but the conversation
 * path's; lines are chatter/guild.yaml. Rides the party-quest tick (the LFG follow-up); no timer of its own.
 */
public final class GuildChat {
    private static final Logger log = LoggerFactory.getLogger(GuildChat.class);
    /** At most this many guild mates answer one line. */
    static final int REPLIERS_MAX = 2;
    /** A first answer comes at least this long after the line (reading it), plus up to {@link #NOTICE_SPREAD_MS}. */
    static final long NOTICE_MIN_MS = 2_500L;
    static final long NOTICE_SPREAD_MS = 4_000L;
    /** A second answerer starts at least this long after the first. */
    static final long SECOND_MIN_MS = 3_000L;
    /** A human's lines this close together get templated answers once (a "hi" typed three times, one round of "hey"). */
    static final long GUILD_ANSWER_GAP_MS = 45_000L;
    /** A logged-out human seen off this long after, if still gone (a reconnect in between drops the line). */
    static final long BYE_DELAY_MS = 8_000L;
    /** A guild mate who went to a lobby for a human's LFG line invites them once its party forms, for this long. */
    static final long LFG_WAIT_MS = 10 * 60_000L;
    /** One in this many unaddressed statements gets an answer (a question or a greeting always may). */
    static final int CHATTER_ODDS = 4;

    private static final Pattern GREETING = Pattern.compile(
            "^(hi+|hello|hey+|yo+|sup|hiya|heya|hai|morning|evening|o/|wb)( there| all| everyone| guys| guild| ppl| peeps)?[!?.~ ]*$");
    private static final Pattern FAREWELL = Pattern.compile(
            "^(bye+|gtg|g2g|gn+|good ?night|night|nite|cya+|see ya|later|laters|off for (today|tonight|now)|logging off)( all| everyone| guys| guild)?[!?.~ ]*$");
    private static final Pattern THANKS = Pattern.compile("^(ty+|thanks?|thx|tyvm|tysm)( all| guys| everyone)?[!?.~ ]*$");
    /** Asking for a party: "anyone up for kpq", "lf kpq", "who wants to do lpq?", "kpq anyone?". */
    private static final Pattern LFG = Pattern.compile(
            "\\b(any ?(one|1|body)|ne1|who|lf\\d?m?|lfg|lfp|up for|down for|wanna|want to|doing|do|run|go|party for)\\b");
    /** What players call a quest citizens run -> the lobby id (regions.yaml). */
    private static final Map<String, String> QUEST_WORDS = Map.ofEntries(
            Map.entry("kpq", "kpq"), Map.entry("kerning pq", "kpq"), Map.entry("hpq", "hpq"),
            Map.entry("henesys pq", "hpq"), Map.entry("moon bunny", "hpq"), Map.entry("lpq", "lpq"),
            Map.entry("ludi pq", "lpq"), Map.entry("opq", "opq"), Map.entry("orbis pq", "opq"),
            Map.entry("balrog", "balrog"), Map.entry("bal", "balrog"));

    /** A guild mate on its way to a lobby for a human's LFG line. */
    record Going(int humanId, int mateId, String questId, long untilMs) { }

    private final Population population;
    private final Random random;
    /** Guild id -> its loaded citizens (any activity); live: presence's sessions in that guild. */
    private final IntFunction<List<CitizenSession>> loadedMembers;
    /** True when a human member of the guild is online (the audience for a "gz"). */
    private final IntPredicate humanOnline;
    /** Human id -> when they last logged in and whether they logged out since (a login without one is a channel change). */
    private final Map<Integer, Boolean> loggedOutSince = new HashMap<>();
    /** "guildId:humanId" -> when that human's last line got templated answers. */
    private final Map<String, Long> answeredAt = new HashMap<>();
    private final List<Going> going = new ArrayList<>();

    GuildChat(Population population) {
        this(population, new Random(), guildId -> liveMembers(population, guildId), guildId -> liveHumanOnline(population, guildId));
    }

    GuildChat(Population population, Random random, IntFunction<List<CitizenSession>> loadedMembers, IntPredicate humanOnline) {
        this.population = population;
        this.random = random;
        this.loadedMembers = loadedMembers;
        this.humanOnline = humanOnline;
    }

    private static List<CitizenSession> liveMembers(Population population, int guildId) {
        List<CitizenSession> out = new ArrayList<>();
        for (CitizenSession session : population.presence().sessions()) {
            Character agent = session.virtual ? null : session.agent();
            if (agent != null && agent.getGuildId() == guildId) {
                out.add(session);
            }
        }
        return out;
    }

    private static boolean liveHumanOnline(Population population, int guildId) {
        Guild guild = Server.getInstance().getGuild(guildId);
        if (guild == null) {
            return false;
        }
        for (GuildCharacter member : guild.getMembers()) {
            Character chr = member.getCharacter();
            if (chr != null && member.isOnline() && !AgentPresence.isAgent(chr)) {
                return true;
            }
        }
        return false;
    }

    boolean on() {
        return population.config().GUILD_CHAT_ENABLED;
    }

    // ---- a human's line --------------------------------------------------------------------------------------------

    /** A human's line in their guild chat. True when a guild mate took it (it will answer or chose not to). */
    public synchronized boolean heard(Character speaker, String text, long now) {
        if (!on() || speaker == null || text == null || text.isBlank() || speaker.getGuildId() <= 0
                || AgentPresence.isAgent(speaker)) {
            return false;
        }
        int guildId = speaker.getGuildId();
        List<CitizenSession> mates = awake(guildId, speaker.getId(), now);
        if (mates.isEmpty()) {
            return false;
        }
        String said = text.trim().toLowerCase(Locale.ROOT);
        String quest = lfgQuest(said);
        if (quest != null && lfg(speaker, mates, quest, now)) {
            return true;
        }
        CitizenSession named = named(mates, said);
        if (named != null) {
            return population.conversation().guildTurn(named, speaker, text);
        }
        String trigger = GREETING.matcher(said).matches() ? "guild.reply.hi"
                : FAREWELL.matcher(said).matches() ? "guild.reply.bye"
                : THANKS.matcher(said).matches() ? "guild.reply.np" : null;
        if (trigger != null) {
            String key = guildId + ":" + speaker.getId();
            Long last = answeredAt.get(key);
            if (last != null && now - last < GUILD_ANSWER_GAP_MS) {
                return true; // answered a moment ago: one round of "hey" for three "hi"s
            }
            answeredAt.put(key, now);
            return answer(mates, speaker, trigger, trigger.equals("guild.reply.np") ? 1 : REPLIERS_MAX, now) > 0;
        }
        if (!said.endsWith("?") && random.nextInt(CHATTER_ODDS) != 0) {
            return false; // a statement nobody picks up, as in any guild
        }
        for (CitizenSession mate : pick(mates, speaker, 1, now)) {
            if (population.conversation().guildTurn(mate, speaker, text)) {
                return true;
            }
        }
        return false;
    }

    /** The guild's online citizens who could answer now: loaded, not the speaker, not away or mid-duel. */
    private List<CitizenSession> awake(int guildId, int speakerId, long now) {
        List<CitizenSession> out = new ArrayList<>();
        for (CitizenSession session : loadedMembers.apply(guildId)) {
            Character agent = session.virtual ? null : session.agent();
            if (agent == null || agent.getGuildId() != guildId || session.characterId == speakerId
                    || session.afkUntilMs > now || session.activity == Activity.AFK
                    || server.pvp.DuelRegistry.inDuel(session.characterId)) {
                continue;
            }
            out.add(session);
        }
        return out;
    }

    /** The guild mate the line names ("mia u there?"), or null. */
    private static CitizenSession named(List<CitizenSession> mates, String said) {
        String words = " " + said.replaceAll("[^a-z0-9]+", " ").trim() + " ";
        for (CitizenSession mate : mates) {
            String name = mate.name().toLowerCase(Locale.ROOT);
            if (name.length() >= 3 && words.contains(" " + name + " ")) {
                return mate;
            }
        }
        return null;
    }

    /** Up to {@code max} mates in random order, those who would not ignore the speaker now; never more than the cap. */
    private List<CitizenSession> pick(List<CitizenSession> mates, Character speaker, int max, long now) {
        List<CitizenSession> shuffled = new ArrayList<>(mates);
        Collections.shuffle(shuffled, random);
        List<CitizenSession> out = new ArrayList<>();
        for (CitizenSession mate : shuffled) {
            if (out.size() >= Math.min(max, REPLIERS_MAX)) {
                break;
            }
            if (!population.voices().ignores(mate.citizen, now ^ speaker.getId() ^ mate.characterId)) {
                out.add(mate);
            }
        }
        return out;
    }

    /** {@code trigger}'s line from up to {@code max} mates, the second a few seconds after the first. Returns how many. */
    private int answer(List<CitizenSession> mates, Character subject, String trigger, int max, long now) {
        int sent = 0;
        long notice = NOTICE_MIN_MS + (long) random.nextInt((int) NOTICE_SPREAD_MS);
        for (CitizenSession mate : pick(mates, subject, max, now)) {
            if (say(mate, trigger, subject, notice, null)) {
                sent++;
                notice += SECOND_MIN_MS + random.nextInt((int) NOTICE_SPREAD_MS);
            }
        }
        return sent;
    }

    private boolean say(CitizenSession mate, String trigger, Character subject, long noticeMs,
                        java.util.function.UnaryOperator<String> atSend) {
        return say(mate, trigger, subject, Map.of(), noticeMs, atSend);
    }

    private boolean say(CitizenSession mate, String trigger, Character subject, Map<String, String> slots, long noticeMs,
                        java.util.function.UnaryOperator<String> atSend) {
        ChatterEngine.Event event = ChatterEngine.Event.of(trigger, mate.mapId, mate.channel).self(mate).subject(subject);
        slots.forEach(event::slot);
        String line = population.chatter().compose(event);
        if (line == null || line.isBlank()) {
            return false;
        }
        population.chatter().guildSay(mate, line, noticeMs, atSend);
        return true;
    }

    // ---- LFG -------------------------------------------------------------------------------------------------------

    /** The lobby id of a quest citizens run that the line asks a party for ("anyone up for kpq" -> kpq), else null. */
    static String lfgQuest(String said) {
        String words = " " + said.replaceAll("[^a-z0-9]+", " ").trim() + " ";
        String quest = null;
        for (Map.Entry<String, String> entry : QUEST_WORDS.entrySet()) {
            if (words.contains(" " + entry.getKey() + " ")) {
                quest = entry.getValue();
                break;
            }
        }
        if (quest == null) {
            return null;
        }
        Matcher asking = LFG.matcher(words);
        return asking.find() || said.endsWith("?") ? quest : null;
    }

    /**
     * An LFG line for {@code questId} from a human: a forming party with a guild mate in it invites them, else a free
     * mate whose level fits heads to the lobby; true when a mate answered. False (the line goes on as chat) when
     * citizens do not run that quest.
     */
    private boolean lfg(Character human, List<CitizenSession> mates, String questId, long now) {
        CitizenPartyQuests runs = population.partyQuests();
        if (runs == null || !CitizenPartyQuests.quests(population.config()).contains(questId)) {
            return false;
        }
        Set<Integer> ids = new HashSet<>();
        mates.forEach(mate -> ids.add(mate.characterId));
        int[] seat = runs.formingWith(ids, questId);
        if (seat != null) {
            int leaderId = seat[0];
            CitizenSession speaker = byId(mates, seat[1]);
            CitizenPartyQuests.JoinAnswer answer = runs.acceptRunInvite(human, leaderId, now);
            String trigger = switch (answer) {
                case INVITED -> "guild.lfg.invited";
                case FULL -> "guild.lfg.full";
                case LEVEL -> "guild.lfg.level";
                case IN_PARTY -> "guild.lfg.party";
                case INVITE_OUT -> "guild.lfg.wait";
                default -> null;
            };
            if (trigger != null && speaker != null) {
                say(speaker, trigger, human, where(leaderId), noticeMs(), null);
                log.info("guild chat: {} asked for {}; {}'s party answered {}", human.getName(), questId,
                        speaker.name(), answer);
                return true;
            }
        }
        Lobby lobby = null;
        CitizenSession free = null;
        for (CitizenSession mate : pick(mates, human, mates.size(), now)) {
            Lobby fits = lobbyOf(mate, questId);
            if (fits != null && lobby == null) {
                lobby = fits;
            }
            if (fits != null && free == null && freeToGo(mate, runs)) {
                free = mate;
            }
        }
        if (lobby == null) {
            return false; // no mate of that quest's band: it goes on as chat
        }
        if (human.getLevel() < lobby.levelMin() || human.getLevel() > lobby.levelMax()) {
            CitizenSession mate = mates.get(random.nextInt(mates.size()));
            return say(mate, "guild.lfg.level", human, Map.of("pq", lobby.name(), "levels",
                    lobby.levelMin() + "-" + lobby.levelMax()), noticeMs(), null);
        }
        if (free == null || alreadyGoing(human.getId())) {
            CitizenSession mate = mates.get(random.nextInt(mates.size()));
            return say(mate, "guild.lfg.none", human, Map.of("pq", lobby.name()), noticeMs(), null);
        }
        String sent = runs.send(free, lobby.id(), now);
        going.add(new Going(human.getId(), free.characterId, lobby.id(), now + LFG_WAIT_MS));
        say(free, "guild.lfg.going", human, Map.of("pq", lobby.name(), "map",
                population.fields().name(lobby.mapId()).toLowerCase(Locale.ROOT)), noticeMs(), null);
        log.info("guild chat: {} asked for {}; {} ({})", human.getName(), questId, free.name(), sent);
        return true;
    }

    private long noticeMs() {
        return NOTICE_MIN_MS + random.nextInt((int) NOTICE_SPREAD_MS);
    }

    private boolean alreadyGoing(int humanId) {
        for (Going g : going) {
            if (g.humanId() == humanId) {
                return true;
            }
        }
        return false;
    }

    /** The {pq}, {map} and {ch} of the party {@code leaderId} forms. */
    private Map<String, String> where(int leaderId) {
        CitizenSession leader = population.presence().session(leaderId);
        if (leader == null) {
            return Map.of();
        }
        Lobby lobby = population.fields().lobbyAt(leader.mapId);
        Map<String, String> slots = new HashMap<>();
        slots.put("map", population.fields().name(leader.mapId).toLowerCase(Locale.ROOT));
        slots.put("ch", Integer.toString(leader.channel));
        if (lobby != null) {
            slots.put("pq", lobby.name());
        }
        return slots;
    }

    private static CitizenSession byId(List<CitizenSession> mates, int id) {
        for (CitizenSession mate : mates) {
            if (mate.characterId == id) {
                return mate;
            }
        }
        return null;
    }

    /** The lobby {@code questId} names that fits {@code mate}'s level, or null. */
    private Lobby lobbyOf(CitizenSession mate, String questId) {
        Character agent = mate.agent();
        if (agent == null) {
            return null;
        }
        for (Lobby lobby : population.fields().lobbiesFor(mate.citizen, agent.getLevel())) {
            if (lobby.id().equals(questId)) {
                return lobby;
            }
        }
        return null;
    }

    /** In town or training, not in or on its way to a run, not hired out. */
    private static boolean freeToGo(CitizenSession mate, CitizenPartyQuests runs) {
        return (mate.activity == Activity.TOWN || mate.activity == Activity.TRAIN) && !runs.holds(mate.characterId)
                && !mate.citizen.hired();
    }

    /**
     * On the party-quest tick: a mate that went to a lobby for a human's LFG line and now leads (or is in) a party
     * forming there has its leader invite the human; given up after {@link #LFG_WAIT_MS}, or once the human is in a
     * party or offline.
     */
    public synchronized void tick(long now) {
        if (going.isEmpty()) {
            return;
        }
        CitizenPartyQuests runs = population.partyQuests();
        for (Iterator<Going> it = going.iterator(); it.hasNext(); ) {
            Going g = it.next();
            Character human = runs == null ? null : online(g.humanId());
            if (human == null || now > g.untilMs() || !on()) {
                it.remove();
                continue;
            }
            int[] seat = runs.formingWith(Set.of(g.mateId()), g.questId());
            if (seat == null) {
                continue;
            }
            it.remove();
            int leaderId = seat[0];
            CitizenPartyQuests.JoinAnswer answer = runs.acceptRunInvite(human, leaderId, now);
            CitizenSession mate = population.presence().session(g.mateId());
            if (answer == CitizenPartyQuests.JoinAnswer.INVITED && mate != null && mate.agent() != null
                    && mate.agent().getGuildId() == human.getGuildId()) {
                say(mate, "guild.lfg.ready", human, where(leaderId), 0L, null);
            }
            log.info("guild chat: {}'s party for {} answered {}'s LFG line: {}", mate == null ? g.mateId() : mate.name(),
                    g.questId(), human.getName(), answer);
        }
    }

    private Character online(int characterId) {
        var world = population.gameWorld();
        return world == null ? null : world.getPlayerStorage().getCharacterById(characterId);
    }

    // ---- logins, levels, clears -------------------------------------------------------------------------------------

    /**
     * A human guild member logged in ({@code online}) or out (stock Guild online notice): one mate greets them, or
     * one sees them off a few seconds later if they are still gone. A login with no logout since the last one is a
     * channel change and gets nothing.
     */
    public synchronized void memberOnline(Character human, boolean online, long now) {
        if (!on() || human == null || human.getGuildId() <= 0 || AgentPresence.isAgent(human)) {
            return;
        }
        Boolean outSince = loggedOutSince.put(human.getId(), !online);
        if (online && Boolean.FALSE.equals(outSince)) {
            return; // a channel change: they never left
        }
        List<CitizenSession> mates = awake(human.getGuildId(), human.getId(), now);
        if (mates.isEmpty()) {
            return;
        }
        int humanId = human.getId();
        if (online) {
            answer(mates, human, "guild.member.hi", 1 + (random.nextInt(3) == 0 ? 1 : 0), now);
        } else if (random.nextBoolean()) {
            CitizenSession mate = pick(mates, human, 1, now).stream().findFirst().orElse(null);
            if (mate != null) {
                say(mate, "guild.member.bye", human, BYE_DELAY_MS + random.nextInt(7_000),
                        line -> Boolean.TRUE.equals(loggedOutSinceNow(humanId)) ? line : null);
            }
        }
    }

    private synchronized Boolean loggedOutSinceNow(int humanId) {
        return loggedOutSince.get(humanId);
    }

    /** A guild member (human or citizen) levelled; the stock notice is out to the guild: one or two mates say gz. */
    public synchronized void levelUp(Character chr, long now) {
        if (!on() || chr == null || chr.getGuildId() <= 0 || !humanOnline.test(chr.getGuildId())) {
            return;
        }
        List<CitizenSession> mates = awake(chr.getGuildId(), chr.getId(), now);
        if (!mates.isEmpty()) {
            answer(mates, chr, "guild.gz", 1 + (random.nextInt(3) == 0 ? 1 : 0), now);
        }
    }

    /**
     * A booked clear (ClearLog.onBooked): for each guild with a human member in it, one mate who was not says gz;
     * a guild with only citizens in it and a human online hears one of them say it is done.
     */
    public synchronized void cleared(Clear clear, long now) {
        if (!on() || clear == null) {
            return;
        }
        Map<Integer, Clear.Member> humanByGuild = new HashMap<>();
        Map<Integer, Integer> citizenByGuild = new HashMap<>();
        Set<Integer> inIt = new HashSet<>();
        for (Clear.Member member : clear.members()) {
            inIt.add(member.id());
            Character chr = member.human() ? online(member.id()) : null;
            CitizenSession session = member.human() ? null : population.presence().session(member.id());
            Character agent = session == null || session.virtual ? null : session.agent();
            if (chr != null && chr.getGuildId() > 0) {
                humanByGuild.putIfAbsent(chr.getGuildId(), member);
            } else if (agent != null && agent.getGuildId() > 0) {
                citizenByGuild.putIfAbsent(agent.getGuildId(), member.id());
            }
        }
        String quest = PopulationHooks.questName(clear.quest());
        for (Map.Entry<Integer, Clear.Member> entry : humanByGuild.entrySet()) {
            Character human = online(entry.getValue().id());
            List<CitizenSession> mates = new ArrayList<>(awake(entry.getKey(), 0, now));
            mates.removeIf(mate -> inIt.contains(mate.characterId));
            if (human != null && !mates.isEmpty()) {
                CitizenSession mate = mates.get(random.nextInt(mates.size()));
                say(mate, "guild.clear.gz", human, Map.of("pq", quest), noticeMs(), null);
            }
        }
        for (Map.Entry<Integer, Integer> entry : citizenByGuild.entrySet()) {
            if (humanByGuild.containsKey(entry.getKey()) || !humanOnline.test(entry.getKey()) || random.nextBoolean()) {
                continue;
            }
            CitizenSession self = population.presence().session(entry.getValue());
            if (self != null && !self.virtual && self.agent() != null) {
                say(self, "guild.clear.self", null, Map.of("pq", quest), noticeMs(), null);
            }
        }
    }

    // ---- views ---------------------------------------------------------------------------------------------------

    synchronized List<Going> going() {
        return List.copyOf(going);
    }
}
