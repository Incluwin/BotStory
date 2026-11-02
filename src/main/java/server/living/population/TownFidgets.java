package server.living.population;

import client.Character;
import server.integration.AgentPresence;
import server.living.population.CitizenSession.Activity;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small things citizens in town do that SoloMapling's SocialHotPotatoManager did and nothing else here does
 * (docs/SOLOMAPLING_PORT.md): a chalkboard up for a few minutes ("LF> guild", "brb"), a face pulled, and now and then
 * an avatar megaphone (birthdays, flexing, announcements), sharing the hourly megaphone cap. Its chat lines, chair
 * sitting and wandering are ChatterEngine's and the Agents' town life already. Only TOWN citizens on a map where a
 * player can see them; rides the 20 s chatter tick. TOWN_FIDGETS_ENABLED: false is the kill switch.
 */
public final class TownFidgets {
    /** Chance per tick, in tenths of a percent, for one citizen. */
    static final int CHALKBOARD_PER_MILLE = 8;
    static final int FACE_PER_MILLE = 30;
    /** Chance per tick, in percent, that one fidgeting citizen somewhere sends an avatar megaphone. */
    static final int AVATAR_PERCENT = 3;
    private static final long CHALKBOARD_MIN_MS = 180_000L;
    private static final long CHALKBOARD_SPAN_MS = 180_000L;
    private static final int CHALKBOARD_MAX_CHARS = 80;
    private static final int AVATAR_LINE_CHARS = 20;
    /** Diablo, Cloud 9 and Loveholic Messenger. */
    private static final int[] AVATAR_ITEMS = {5390000, 5390001, 5390002};

    private final Population population;
    private final Random random;
    /** Citizen id -> when its chalkboard comes down. */
    private final Map<Integer, Long> boards = new ConcurrentHashMap<>();
    private final AtomicInteger chalkboards = new AtomicInteger();
    private final AtomicInteger faces = new AtomicInteger();
    private final AtomicInteger megaphones = new AtomicInteger();

    TownFidgets(Population population) {
        this.population = population;
        this.random = new Random(population.config().SEED ^ 0xf1d6e75L);
    }

    boolean enabled() {
        return population.config().TOWN_FIDGETS_ENABLED;
    }

    /** Every 20 s (the chatter tick). */
    void tick() {
        long now = System.currentTimeMillis();
        takeDownBoards(now);
        if (!enabled()) {
            return;
        }
        Map<String, Boolean> watched = new HashMap<>();
        List<CitizenSession> fidgeting = new ArrayList<>();
        for (CitizenSession session : population.presence().sessions()) {
            Character agent = session.agent();
            if (!idleInTown(session, agent)) {
                continue;
            }
            boolean seen = watched.computeIfAbsent(session.channel + ":" + session.mapId, key -> humanOn(agent));
            if (!seen) {
                continue;
            }
            fidgeting.add(session);
            int roll = random.nextInt(1000);
            if (roll < CHALKBOARD_PER_MILLE && agent.getChalkboard() == null) {
                chalkboard(session, agent, now);
            } else if (roll < CHALKBOARD_PER_MILLE + FACE_PER_MILLE) {
                agent.changeFaceExpression(1 + random.nextInt(7));
                faces.incrementAndGet();
            }
        }
        if (!fidgeting.isEmpty() && random.nextInt(100) < AVATAR_PERCENT) {
            avatarMegaphone(fidgeting.get(random.nextInt(fidgeting.size())));
        }
    }

    static boolean idleInTown(CitizenSession session, Character agent) {
        return agent != null && !session.virtual && session.activity == Activity.TOWN && agent.getMiniGame() == null
                && agent.getTrade() == null && agent.getMap() != null && session.afkUntilMs <= System.currentTimeMillis();
    }

    private static boolean humanOn(Character agent) {
        for (Character chr : agent.getMap().getAllPlayers()) {
            if (!AgentPresence.isAgent(chr)) {
                return true;
            }
        }
        return false;
    }

    private void chalkboard(CitizenSession session, Character agent, long now) {
        String text = population.chatter().compose(ChatterEngine.Event.of("town.chalkboard", session.mapId, session.channel)
                .self(session));
        if (text == null || text.isBlank()) {
            return;
        }
        agent.setChalkboard(text.length() > CHALKBOARD_MAX_CHARS ? text.substring(0, CHALKBOARD_MAX_CHARS) : text);
        agent.getMap().broadcastMessage(PacketCreator.useChalkboard(agent, false));
        boards.put(session.characterId, now + CHALKBOARD_MIN_MS + random.nextInt((int) CHALKBOARD_SPAN_MS));
        chalkboards.incrementAndGet();
    }

    /** Boards whose time is up, or whose citizen went on to something else, come down. */
    private void takeDownBoards(long now) {
        for (Map.Entry<Integer, Long> board : new ArrayList<>(boards.entrySet())) {
            CitizenSession session = population.presence().session(board.getKey());
            Character agent = session == null ? null : session.agent();
            if (agent == null) {
                boards.remove(board.getKey());
                continue;
            }
            if (now >= board.getValue() || !enabled() || session.activity != Activity.TOWN) {
                boards.remove(board.getKey());
                agent.setChalkboard(null);
                if (agent.getMap() != null) {
                    agent.getMap().broadcastMessage(PacketCreator.useChalkboard(agent, true));
                }
            }
        }
    }

    private void avatarMegaphone(CitizenSession session) {
        String text = population.chatter().compose(ChatterEngine.Event.of("town.avatar", session.mapId, session.channel)
                .self(session));
        if (text == null || text.isBlank()) {
            return;
        }
        String rendered = population.voices().renderAnswer(session.citizen, text, System.currentTimeMillis(), false);
        if (population.chatter().avatarMegaphone(session, AVATAR_ITEMS[random.nextInt(AVATAR_ITEMS.length)], lines(rendered))) {
            megaphones.incrementAndGet();
        }
    }

    /** One line for {@code !citizens talk}. */
    public String status() {
        return "town fidgets " + (enabled() ? "on" : "off (TOWN_FIDGETS_ENABLED)") + ": " + boards.size() + " chalkboards up; "
                + chalkboards.get() + " put up, " + faces.get() + " faces, " + megaphones.get() + " avatar megaphones since boot";
    }

    /** A line split into at most four lines of about twenty characters, on word boundaries. */
    static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > AVATAR_LINE_CHARS) {
                out.add(line.toString());
                line.setLength(0);
                if (out.size() == 4) {
                    return out;
                }
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word.length() > AVATAR_LINE_CHARS ? word.substring(0, AVATAR_LINE_CHARS) : word);
        }
        if (line.length() > 0 && out.size() < 4) {
            out.add(line.toString());
        }
        return out;
    }
}
