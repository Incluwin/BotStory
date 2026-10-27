package server.living.population;

import client.Character;
import client.inventory.manipulator.InventoryManipulator;
import config.PopulationConfig;
import constants.id.ItemId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.agents.capabilities.movement.AgentGroundingService;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.runtime.AgentForeignWriteRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.integration.AgentPresence;
import server.living.population.CitizenSession.Activity;
import server.living.population.PopulationContent.Intent;
import server.living.population.PopulationContent.Region;
import server.living.resident.ResidentSpots;
import server.maps.FieldLimit;
import server.maps.MapObject;
import server.maps.MapleMap;
import server.maps.MiniGame;
import server.maps.MiniGame.MiniGameResult;
import server.maps.MiniGameRules;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PLAY (docs/BOT_CONCEPT_PLAN.md F5a): citizens play omok and match cards in towns whose map allows minigames
 * (population.MINIGAME_TOWNS; the Free Market forbids them). A citizen on a PLAY session walks to a bench of the town,
 * sits down in an open room near it when there is one (a human's first, then a citizen's), else says it wants a game
 * and opens its own with the omok set or match cards it carries, so humans see the balloon and may sit down. It plays
 * a few matches (MiniGameTable), says its gg in the room when a human sits across, and goes on with its day; a room
 * nobody joins closes, and two of those end the session. Records are the stock omokwins/matchcardwins columns. Its
 * strength is innate (a few play well, population.MINIGAME_STRONG_PERCENT) plus what its record says it has played.
 * Rides the chatter tick (20 s); each move is a one-shot timer step. MINIGAMES_ENABLED: false is the kill switch.
 */
public final class CitizenMiniGames {
    private static final Logger log = LoggerFactory.getLogger(CitizenMiniGames.class);
    /** The omok sets 4080000-4080011: a citizen's own set is one of these. */
    private static final int OMOK_SETS = 12;
    /** A citizen with an empty room this long changes to a human's open room nearby. */
    private static final long SWITCH_TO_HUMAN_ROOM_MS = 30_000L;
    /** A human's room is joined once it has been seen open for a tick: nobody sits down the instant it opens. */
    private static final long HUMAN_ROOM_NOTICE_MS = 15_000L;

    private final Population population;
    private final Random random;
    private final MiniGameTable.Clock clock;
    private final Map<Integer, Player> players = new ConcurrentHashMap<>();
    /** Citizen id -> no PLAY plan before this. */
    private final Map<Integer, Long> restUntil = new ConcurrentHashMap<>();
    /** Rooms a citizen is walking to: nobody else goes for them. */
    private final Set<MiniGame> targeted = ConcurrentHashMap.newKeySet();
    /** Human rooms -> when first seen open. */
    private final Map<MiniGame, Long> seenRooms = new ConcurrentHashMap<>();
    private final MiniGameTable.Listener listener = new Listener();

    /** A citizen on a PLAY session. */
    private static final class Player {
        final CitizenSession session;
        final MiniGameTable.Seat seat;
        /** Its omok set (0-11), -1 when it has none and cannot get one: it only sits down in others' rooms. */
        final int omokSet;
        final boolean hasCards;
        final boolean prefersCards;
        volatile MiniGameTable table;
        /** A walk to a room or a room about to open is under way. */
        volatile boolean pending;
        volatile long readyAtMs;
        /** Its rooms that closed with nobody in them. */
        volatile int emptyRooms;

        Player(CitizenSession session, MiniGameTable.Seat seat, int omokSet, boolean hasCards, boolean prefersCards) {
            this.session = session;
            this.seat = seat;
            this.omokSet = omokSet;
            this.hasCards = hasCards;
            this.prefersCards = prefersCards;
        }
    }

    CitizenMiniGames(Population population) {
        this(population, (delayMs, step) -> TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY,
                step, Math.max(1L, delayMs)));
    }

    CitizenMiniGames(Population population, MiniGameTable.Clock clock) {
        this.population = population;
        this.clock = clock;
        this.random = new Random(population.config().SEED ^ 0x0303_6a3eL);
    }

    private PopulationConfig config() {
        return population.config();
    }

    private boolean enabled() {
        return config().MINIGAMES_ENABLED;
    }

    /** True when a PLAY plan fits {@code citizen} now: minigames on, a town of its region allows them, not resting. */
    boolean plays(Citizen citizen, long now) {
        Long rest = restUntil.get(citizen.characterId());
        return enabled() && (rest == null || rest <= now) && !towns(citizen).isEmpty();
    }

    /** A town of the citizen's region it plays in; 0 when there is none. */
    int townFor(Citizen citizen) {
        List<Integer> towns = towns(citizen);
        return towns.isEmpty() ? 0 : towns.get(random.nextInt(towns.size()));
    }

    /** The MINIGAME_TOWNS of its region whose map allows minigames. */
    private List<Integer> towns(Citizen citizen) {
        Region region = population.fields().regionOf(citizen);
        List<Integer> out = new ArrayList<>();
        if (region == null) {
            return out;
        }
        for (String part : config().MINIGAME_TOWNS.split(",")) {
            try {
                int mapId = Integer.parseInt(part.trim());
                if (region.towns().contains(mapId) && allows(population.map(citizen.channel(), mapId))) {
                    out.add(mapId);
                }
            } catch (NumberFormatException ignored) {
                // the lint reports it
            }
        }
        return out;
    }

    static boolean allows(MapleMap map) {
        return map != null && !FieldLimit.CANNOTMINIGAME.check(map.getFieldLimit());
    }

    /** True while the citizen sits in a minigame room: it types in the room's chat there, never on the map. */
    static boolean seated(CitizenSession session) {
        Character agent = session.agent();
        return agent != null && agent.getMiniGame() != null;
    }

    /** True while the citizen is on a PLAY session here: its re-plans and AFK wait for the session to end. */
    boolean holds(CitizenSession session) {
        return players.containsKey(session.characterId);
    }

    /**
     * Its PLAY session starts on {@code map}: it gets out its sets (or gets them from the wardrobe pool) and walks to a
     * bench. False when it cannot play here; it strolls the town instead.
     */
    boolean arrive(CitizenSession session, MapleMap map, long now) {
        Character agent = session.agent();
        if (!enabled() || agent == null || session.entry == null || !allows(map) || players.containsKey(session.characterId)) {
            return false;
        }
        int omokSet = carryOmokSet(agent);
        boolean hasCards = agent.haveItem(ItemId.MATCH_CARDS)
                || InventoryManipulator.addById(agent.getClient(), ItemId.MATCH_CARDS, (short) 1);
        int games = config().MINIGAME_GAMES_MIN
                + random.nextInt(Math.max(1, config().MINIGAME_GAMES_MAX - config().MINIGAME_GAMES_MIN + 1));
        MiniGameTable.Seat seat = new MiniGameTable.Seat(agent, strength(agent), games, step -> runAs(session, step), session);
        boolean prefersCards = Math.floorMod(agent.getId() * 31 + 7, 100) < 25;
        Player player = new Player(session, seat, omokSet, hasCards, prefersCards);
        player.readyAtMs = now + 6_000L + random.nextInt(10_000);
        players.put(session.characterId, player);
        Point bench = ResidentSpots.fallback(map, session.characterId + (int) (now / 60_000L));
        AgentMovementCommandRuntime.moveTo(session.entry, bench, false);
        log.info("citizen {} plays minigames at {} (strength {}, {} games)", session.name(), map.getId(),
                String.format(Locale.ROOT, "%.2f", seat.strength), games);
        return true;
    }

    /** Every 20 s (the chatter tick): rooms to join or open, empty rooms to close, sessions that are done. */
    void tick() {
        long now = System.currentTimeMillis();
        for (Player player : players.values()) {
            try {
                step(player, now);
            } catch (RuntimeException failure) {
                log.debug("minigame step for citizen {} failed", player.session.name(), failure);
            }
        }
        seenRooms.keySet().removeIf(game -> game.getOwner() == null);
    }

    private void step(Player player, long now) {
        CitizenSession session = player.session;
        Character agent = session.agent();
        if (agent == null || !population.presence().holds(session) || session.activity != Activity.PLAY) {
            players.remove(session.characterId); // logged off or moved on: a room it sat in closed with it
            getUp(player);
            return;
        }
        if (!enabled()) {
            finish(player, now, "minigames off");
            return;
        }
        MiniGameTable table = player.table;
        if (table != null && (table.game().getOwner() == null || agent.getMiniGame() != table.game())) {
            player.table = null; // its room closed, or it left
            table = null;
        }
        if (table != null) {
            manage(player, table, agent, now);
            return;
        }
        if (player.pending || now < player.readyAtMs) {
            return;
        }
        if (player.seat.gamesLeft <= 0) {
            finish(player, now, "played enough");
            return;
        }
        MiniGame open = joinable(agent, now);
        if (open != null) {
            walkTo(player, open, now);
        } else if (player.emptyRooms >= 2 || player.omokSet < 0 && !player.hasCards) {
            finish(player, now, "nobody to play with");
        } else {
            openRoom(player, agent);
        }
    }

    /** A room it sits in: an empty one of its own closes after the wait; a room where nothing happens, it leaves. */
    private void manage(Player player, MiniGameTable table, Character agent, long now) {
        MiniGame game = table.game();
        long quiet = now - table.lastEventMs();
        if (game.isOwner(agent) && game.getVisitor() == null) {
            MiniGame human = quiet >= SWITCH_TO_HUMAN_ROOM_MS ? joinable(agent, now) : null;
            if (human != null && !AgentPresence.isAgent(human.getOwner())) {
                table.leave(player.seat); // a player opened a room nearby: it goes to play them instead
                walkTo(player, human, now);
            } else if (quiet >= config().MINIGAME_ROOM_WAIT_MS) {
                player.emptyRooms++;
                table.leave(player.seat);
                player.readyAtMs = now + 10_000L + random.nextInt(20_000);
            }
        } else if (quiet >= config().MINIGAME_IDLE_MS) {
            table.leave(player.seat); // a player who never readies or moves
        }
    }

    /** An open room on its map it could sit down in: a human's (seen open a while) first, then a citizen's. */
    private MiniGame joinable(Character agent, long now) {
        MapleMap map = agent.getMap();
        if (map == null) {
            return null;
        }
        MiniGame citizenRoom = null;
        for (MapObject object : map.getMapObjects()) {
            if (!(object instanceof MiniGame game) || targeted.contains(game)) {
                continue;
            }
            Character owner = game.getOwner();
            if (owner == null || owner == agent || !game.hasFreeSlot() || !game.getPassword().isEmpty()) {
                continue;
            }
            if (!AgentPresence.isAgent(owner)) {
                long seen = seenRooms.computeIfAbsent(game, key -> now);
                if (now - seen >= HUMAN_ROOM_NOTICE_MS) {
                    return game;
                }
            } else if (citizenRoom == null && game.getObserver() instanceof MiniGameTable table
                    && table.ownerSeat() != null) {
                citizenRoom = game;
            }
        }
        return citizenRoom;
    }

    /** It walks up beside the room's owner and sits down a moment later. */
    private void walkTo(Player player, MiniGame game, long now) {
        Character owner = game.getOwner();
        Character agent = player.session.agent();
        if (owner == null || agent == null || !targeted.add(game)) {
            return;
        }
        player.pending = true;
        Point here = owner.getPosition();
        int side = agent.getPosition().x < here.x ? -1 : 1;
        Point beside = AgentGroundingService.findGroundPoint(owner.getMap(), new Point(here.x + side * (45 + random.nextInt(30)), here.y - 1));
        AgentMovementCommandRuntime.moveTo(player.session.entry, beside != null ? beside : here, false);
        long walkMs = Math.min(15_000L, 2_500L + Math.abs(agent.getPosition().x - here.x) * 8L) + random.nextInt(3_000);
        clock.after(walkMs, () -> {
            try {
                sitDown(player, game);
            } finally {
                targeted.remove(game);
                player.pending = false;
            }
        });
    }

    private void sitDown(Player player, MiniGame game) {
        Character agent = player.session.agent();
        if (agent == null || !players.containsKey(player.session.characterId) || game.getOwner() == null
                || !game.hasFreeSlot() || agent.getMiniGame() != null) {
            return;
        }
        MiniGameTable table = game.getObserver() instanceof MiniGameTable watching ? watching
                : MiniGameTable.watch(game, null, clock, random, pace(), listener);
        player.seat.runner.accept(() -> {
            if (table.join(player.seat, "") == MiniGameRules.OK) {
                player.table = table;
                player.session.journal.add("minigame", "sat down to play " + kind(game) + " with " + game.getOwner().getName());
            } else if (table.ownerSeat() == null && game.getObserver() == table && game.getVisitor() == null) {
                game.setObserver(null); // a human's room it could not sit in after all
            }
        });
    }

    /** It says it wants a game (now and then), then opens a room with its own set a few seconds later. */
    private void openRoom(Player player, Character agent) {
        boolean cards = player.omokSet < 0 || player.hasCards && random.nextInt(100) < (player.prefersCards ? 70 : 20);
        CitizenSession session = player.session;
        player.pending = true;
        if (random.nextInt(100) < 40) {
            population.chatter().speakTo(ChatterEngine.Event.of("game.lf", session.mapId, session.channel).self(session)
                    .slot("game", cards ? "match cards" : "omok"));
        }
        clock.after(3_000L + random.nextInt(4_000), () -> {
            player.pending = false;
            if (!players.containsKey(session.characterId) || agent.getMiniGame() != null) {
                return;
            }
            player.seat.runner.accept(() -> {
                String title = title(session, cards);
                int size = random.nextInt(100) < 60 ? 0 : random.nextInt(100) < 75 ? 1 : 2;
                int status = cards ? MiniGameRules.createMatchCard(agent, title, "", size)
                        : MiniGameRules.createOmok(agent, title, "", player.omokSet);
                MiniGame game = agent.getMiniGame();
                if (status == MiniGameRules.OK && game != null) {
                    player.table = MiniGameTable.watch(game, player.seat, clock, random, pace(), listener);
                } else {
                    player.emptyRooms++;
                    player.readyAtMs = System.currentTimeMillis() + 30_000L;
                }
            });
        });
    }

    /** The PLAY session is over: it gets up and plans its next activity; no PLAY plan for MINIGAME_COOLDOWN_MS. */
    private void finish(Player player, long now, String why) {
        CitizenSession session = player.session;
        if (players.remove(session.characterId) == null) {
            return;
        }
        restUntil.put(session.characterId, now + config().MINIGAME_COOLDOWN_MS);
        getUp(player);
        log.debug("citizen {} stops playing minigames: {}", session.name(), why);
        population.presence().replan(session, now);
    }

    private void getUp(Player player) {
        MiniGameTable table = player.table;
        player.table = null;
        Character agent = player.session.agent();
        if (table != null && agent != null && agent.getMiniGame() == table.game()) {
            table.leave(player.seat);
        }
    }

    // ---- what a citizen is like at the table ---------------------------------------------------

    /**
     * Innate (most play casually, MINIGAME_STRONG_PERCENT play well), plus practice: the matches its stock record
     * shows, up to +0.15 at 400.
     */
    double strength(Character agent) {
        Random innate = new Random(agent.getId() * 0x9E3779B97F4A7C15L);
        double base = innate.nextInt(100) < config().MINIGAME_STRONG_PERCENT ? 0.8d + 0.2d * innate.nextDouble()
                : 0.1d + 0.55d * Math.pow(innate.nextDouble(), 1.5d);
        int played = 0;
        for (MiniGameResult result : MiniGameResult.values()) {
            played += agent.getMiniGamePoints(result, true) + agent.getMiniGamePoints(result, false);
        }
        return Math.min(1.0d, base + Math.min(0.15d, played / 400.0d * 0.15d));
    }

    /** The omok set it carries, else one from the wardrobe pool by its id; -1 when there is no room for it. */
    private static int carryOmokSet(Character agent) {
        for (int set = 0; set < OMOK_SETS; set++) {
            if (agent.haveItem(ItemId.MINI_GAME_BASE + set)) {
                return set;
            }
        }
        int set = Math.floorMod(agent.getId(), OMOK_SETS);
        return InventoryManipulator.addById(agent.getClient(), ItemId.MINI_GAME_BASE + set, (short) 1) ? set : -1;
    }

    /** A room title from chatter (game.title.omok / game.title.cards) in its voice. */
    private String title(CitizenSession session, boolean cards) {
        List<String> lines = new ArrayList<>();
        for (Intent intent : population.content().intents(cards ? "game.title.cards" : "game.title.omok")) {
            lines.addAll(intent.linesFor(population.voices().styleOf(session.citizen)));
        }
        String line = lines.isEmpty() ? (cards ? "match cards" : "omok") : lines.get(random.nextInt(lines.size()));
        String rendered = population.voices().render(session.citizen, line, System.currentTimeMillis());
        return rendered.length() > 24 ? rendered.substring(0, 24) : rendered;
    }

    private MiniGameTable.Pace pace() {
        return new MiniGameTable.Pace(config().MINIGAME_THINK_MIN_MS, config().MINIGAME_THINK_MAX_MS);
    }

    private static String kind(MiniGame game) {
        return game.isOmok() ? "omok" : "match cards";
    }

    /** Runs a step as the citizen: on its own Agent tick when external writes are routed there. */
    private static void runAs(CitizenSession session, Runnable step) {
        AgentRuntimeEntry entry = session.entry;
        try {
            if (entry != null && AgentForeignWriteRuntime.foreign(entry)) {
                AgentForeignWriteRuntime.defer(entry, "minigame", owner -> step.run());
            } else {
                step.run();
            }
        } catch (RuntimeException failure) {
            log.debug("minigame step for citizen {} failed", session.name(), failure);
        }
    }

    /** The journal, the room chat and the session's state, from its tables. */
    private final class Listener implements MiniGameTable.Listener {
        @Override
        public void played(MiniGameTable.Seat seat, MiniGameResult result, Character opponent) {
            if (seat.session == null || opponent == null) {
                return;
            }
            String what = result == MiniGameResult.WIN ? "beat " : result == MiniGameResult.LOSS ? "lost to " : "tied with ";
            Character agent = seat.chr;
            MiniGame game = agent.getMiniGame();
            seat.session.journal.add("minigame", what + opponent.getName() + (game == null ? "" : " at " + kind(game)));
        }

        @Override
        public void left(MiniGameTable.Seat seat) {
            Player player = seat.session == null ? null : players.get(seat.session.characterId);
            if (player != null && player.seat == seat) {
                player.table = null;
                player.readyAtMs = System.currentTimeMillis() + 5_000L + random.nextInt(15_000);
            }
        }

        @Override
        public void say(MiniGameTable.Seat seat, String trigger, Character human) {
            CitizenSession session = seat.session;
            if (session == null) {
                return;
            }
            population.chatter().speakTo(ChatterEngine.Event.of(trigger, session.mapId, session.channel).self(session)
                    .subject(human));
        }
    }
}
