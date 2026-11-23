package server.living.roster;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.agents.perception.AgentMapPerception;
import server.integration.AgentPresence;
import server.life.LifeFactory;
import server.life.Monster;
import server.life.NPC;
import server.living.LivingWorld;
import server.living.crew.CrewBanter;
import server.living.store.LivingStore.RosterEntry;
import server.maps.Foothold;
import server.maps.MapleMap;
import server.maps.Portal;
import server.living.story.DuelGround;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The crew camp (docs/WORLD_SYSTEMS_PLAN.md N7): a private copy of one stock indoor map (CREW_CAMP_MAP_ID, decision
 * D10) per owner, made when they go there and dropped when they leave, where their benched crew idles while they visit.
 *
 * <ul>
 *   <li><b>Getting there.</b> "Crew camp..." in the Crew window and on any needs board ({@link #enter}). Not while dead,
 *       nor from an event instance (every party quest, boss run, duel and coliseum bout is one), a run that holds the
 *       owner, a map with a live boss on it, the Free Market or the coliseum ({@link #refusal}). The owner's companions
 *       out with them on their map come along; everyone goes back to the very spot they stood on ({@link #leave}).</li>
 *   <li><b>The place.</b> A {@link DuelGround} copy (the story scenes' SceneInstance) with every portal closed, its exit
 *       the town the owner came from: a logout there saves that town, and a restart finds no camp. A stock Bulletin
 *       Board NPC (9040011, a template every client has) stands as the camp notice.</li>
 *   <li><b>Who is there.</b> The free benched companions, loaded under the CAMP pool before the owner arrives
 *       ({@link BenchCampForm}); the rest are away, and the notice says where. Looked at again each tick: one called,
 *       released or sent on a live duty leaves, one benched meanwhile arrives.</li>
 *   <li><b>Life.</b> One timer for every camp ({@link #TICK_MS}): each companion now and then walks, sits or stands, and
 *       every few minutes two of them trade an exchange of banter.yaml's camp moment in map chat (CrewBanter.camp: their
 *       tie and traits pick it; CREW_BANTER_ENABLED and the owner's "quiet" hold for it too).</li>
 *   <li><b>Gone.</b> The owner leaving any other way (a return scroll, a GM warp, a disconnect) is heard from the copy
 *       and tears the camp down the same way: everyone left inside back to their spot, every camp companion unloaded,
 *       the copy let go. A companion that does not unload keeps its lease and is unloaded again next tick. A server stop
 *       unloads them all ({@link #endAll}).</li>
 * </ul>
 *
 * CREW_CAMP_ENABLED is the kill switch: off, nothing is offered and nothing can be entered. Every change runs on
 * companion-ops; the hooks from the game hand over to it.
 */
public final class CrewCamp {
    private static final Logger log = LoggerFactory.getLogger(CrewCamp.class);
    /** The one look at every camp. */
    public static final long TICK_MS = 15_000L;
    /** The first exchange comes this long after the owner arrives, the next ones this far apart plus up to the spread. */
    static final long FIRST_BANTER_MS = 30_000L;
    static final long BANTER_GAP_MS = 150_000L;
    static final long BANTER_SPREAD_MS = 120_000L;
    /** The camp notice: the stock Bulletin Board (it stands in the Guild PQ maps; only ours opens the notice). */
    public static final int NOTICE_NPC = 9040011;

    static final String CLOSED = "The crew camp is closed.";
    static final String DEAD = "Not while you're down.";
    static final String BUSY = "Finish what you're in first.";
    static final String BOSS = "Not with a boss around.";
    static final String MARKET = "Not from the Free Market.";
    static final String COLISEUM = "Not from the coliseum.";
    static final String NOT_READY = "The camp isn't ready. Try again in a moment.";

    /** What an entry gate sees of where the owner stands. */
    record Place(int mapId, boolean alive, boolean instance, boolean committed, boolean boss) { }

    /** The camp's private copy, as the camp uses it; the game's is a DuelGround. */
    interface Ground {
        MapleMap map();

        /** Where someone entering is set down. */
        Point landing();

        /** {@code chr} onto the copy at {@code at}; {@link #close} returns it to where it stands now. */
        void enter(Character chr, Point at);

        /** Everyone still inside back to their own spot, {@code followers} told to follow again; the copy let go. */
        void close(Collection<Integer> followers);
    }

    /** What the camp does to the game; tests bring their own. */
    interface Ports {
        boolean on();

        int campMapId();

        long now();

        Place place(Character owner);

        /** The town the owner's map returns to (the map itself when it is one): a logout at camp saves it. */
        int exitMapId(Character owner);

        /** A private copy of {@code mapId}, portals closed; {@code onLeft} hears whoever leaves it on its own. */
        Ground open(Character owner, int mapId, int exitMapId, Consumer<Character> onLeft);

        /** The owner's companions out with them, on their map, alive. */
        List<Character> partyBeside(Character owner);

        /** The camp notice put up on the copy: its object id (0 when it could not be). */
        int putUpNotice(MapleMap map, Point near);

        /** A pink line to the owner. */
        void tell(Character owner, String line);

        void submit(String what, Runnable work);

        /** {@code work} on companion-ops after {@code delayMs}. */
        void later(String what, long delayMs, Runnable work);

        /** A camp companion's line in map chat. */
        void say(Character bot, String line);

        /** An exchange for two or more of these camp companions; null for none. */
        CrewBanter.CampExchange exchange(Character owner, List<Character> bots);
    }

    /** One owner's visit. Changed on companion-ops only; read anywhere. */
    static final class Visit {
        final int ownerId;
        final Character owner;
        final Ground ground;
        final Map<String, BenchCampForm.Seat> seats = new ConcurrentHashMap<>();
        final Set<Integer> followers = ConcurrentHashMap.newKeySet();
        volatile List<BenchCampForm.Absent> away = List.of();
        volatile int noticeId;
        long nextBanterAtMs;

        Visit(Character owner, Ground ground) {
            this.ownerId = owner.getId();
            this.owner = owner;
            this.ground = ground;
        }
    }

    private final LivingWorld world;
    private final Ports ports;
    private final BenchCampForm form;
    private final Random random;
    private final Map<Integer, Visit> visits = new ConcurrentHashMap<>();
    /** Camp companions whose unload failed: unloaded again each tick until they are offline. */
    private final Map<Integer, BenchCampForm.Seat> stranded = new ConcurrentHashMap<>();

    public CrewCamp(LivingWorld world) {
        this(world, new LivePorts(world), new BenchCampForm(world), new Random());
    }

    CrewCamp(LivingWorld world, Ports ports, BenchCampForm form, Random random) {
        this.world = world;
        this.ports = ports;
        this.form = form;
        this.random = random;
    }

    public boolean on() {
        return ports.on();
    }

    // ---- the gates -------------------------------------------------------------------------------

    /** Why the owner cannot go to camp from {@code place}, as their own line; null when they can. */
    static String refusal(Place place) {
        if (!place.alive()) {
            return DEAD;
        }
        if (place.instance() || place.committed()) {
            return BUSY;
        }
        if (place.boss()) {
            return BOSS;
        }
        if (freeMarket(place.mapId())) {
            return MARKET;
        }
        if (coliseum(place.mapId())) {
            return COLISEUM;
        }
        return null;
    }

    /** The Free Market entrance and its rooms. */
    static boolean freeMarket(int mapId) {
        return mapId >= 910000000 && mapId <= 910000022;
    }

    /** The Ariant coliseum's lobby and arenas (docs/PVP_PLAN.md V4). */
    static boolean coliseum(int mapId) {
        return mapId >= 980010000 && mapId <= 980010999;
    }

    // ---- entry and exit (companion-ops) ---------------------------------------------------------------

    /** The owner asks to go to camp: through the gates, their bench seated on a fresh copy, then them and theirs in. */
    public void enter(Character owner) {
        if (owner == null) {
            return;
        }
        if (!ports.on()) {
            ports.tell(owner, CLOSED);
            return;
        }
        if (visits.containsKey(owner.getId())) {
            return; // already there
        }
        String no = refusal(ports.place(owner));
        if (no != null) {
            ports.tell(owner, no);
            return;
        }
        int ownerId = owner.getId();
        Ground ground = ports.open(owner, ports.campMapId(), ports.exitMapId(owner),
                chr -> ports.submit("camp.left " + ownerId, () -> left(ownerId, chr.getId())));
        if (ground == null) {
            ports.tell(owner, NOT_READY);
            return;
        }
        Visit visit = new Visit(owner, ground);
        visits.put(ownerId, visit);
        Point landing = ground.landing();
        visit.noticeId = ports.putUpNotice(ground.map(), landing);
        List<Character> party = ports.partyBeside(owner);
        for (Character companion : party) {
            visit.followers.add(companion.getId());
        }
        try {
            seatBench(visit); // they are there before their owner comes in
        } catch (RuntimeException failure) {
            log.warn("crew camp of {} could not seat its bench", ownerId, failure);
        }
        ground.enter(owner, landing);
        for (Character companion : party) {
            ground.enter(companion, landing);
        }
        visit.nextBanterAtMs = ports.now() + FIRST_BANTER_MS;
        ports.tell(owner, arrival(visit));
        world.store().event(ownerId, null, "camp.entered", visit.seats.size() + " here, " + visit.away.size() + " away");
    }

    /** The owner asks to leave: everyone back to their spot, the camp companions unloaded, the copy let go. */
    public void leave(Character owner) {
        Visit visit = owner == null ? null : visits.get(owner.getId());
        if (visit != null) {
            tearDown(visit, "left");
        }
    }

    /** Someone left the copy on its own (a scroll, a warp, a disconnect): the owner takes the camp down with them. */
    void left(int ownerId, int characterId) {
        Visit visit = visits.get(ownerId);
        if (visit == null) {
            return;
        }
        if (characterId == ownerId) {
            tearDown(visit, "owner gone");
        } else {
            visit.followers.remove(characterId);
        }
    }

    private void tearDown(Visit visit, String why) {
        if (!visits.remove(visit.ownerId, visit)) {
            return;
        }
        try {
            visit.ground.close(visit.followers); // the owner (if still inside) and theirs back first, then the copy goes
        } catch (RuntimeException failure) {
            log.warn("crew camp of {} failed to close", visit.ownerId, failure);
        }
        for (BenchCampForm.Seat seat : List.copyOf(visit.seats.values())) {
            visit.seats.remove(seat.personaId);
            if (!form.unseat(seat)) {
                stranded.put(seat.characterId, seat);
            }
        }
        world.store().event(visit.ownerId, null, "camp.left", why);
    }

    /** The server stops: every camp torn down, every camp companion unloaded (on companion-ops, waited for). */
    public void endAll() {
        for (Visit visit : List.copyOf(visits.values())) {
            tearDown(visit, "server shutdown");
        }
        retryStranded();
    }

    /**
     * The persona's camp copy, unloaded now (RosterService, before it loads it as a companion: a call from the camp or
     * the Crew window). True when it was at camp.
     */
    public boolean release(String personaId) {
        for (Visit visit : visits.values()) {
            BenchCampForm.Seat seat = visit.seats.remove(personaId);
            if (seat != null) {
                if (!form.unseat(seat)) {
                    stranded.put(seat.characterId, seat);
                }
                return true;
            }
        }
        return false;
    }

    // ---- the tick (companion-ops) ----------------------------------------------------------------

    /** Every {@link #TICK_MS}: the stranded unloaded again; at each camp the bench looked at, idle life, maybe banter. */
    public void tick() {
        retryStranded();
        long now = ports.now();
        for (Visit visit : List.copyOf(visits.values())) {
            try {
                if (!ports.on()) {
                    tearDown(visit, "switched off");
                    continue;
                }
                seatBench(visit);
                for (BenchCampForm.Seat seat : visit.seats.values()) {
                    form.idle(seat, visit.ground.map());
                }
                if (now >= visit.nextBanterAtMs) {
                    visit.nextBanterAtMs = now + BANTER_GAP_MS + (long) (random.nextDouble() * BANTER_SPREAD_MS);
                    banter(visit);
                }
            } catch (RuntimeException failure) {
                log.warn("crew camp tick of {} failed", visit.ownerId, failure);
            }
        }
    }

    private void retryStranded() {
        for (BenchCampForm.Seat seat : List.copyOf(stranded.values())) {
            if (form.unseat(seat)) {
                stranded.remove(seat.characterId, seat);
            }
        }
    }

    /** Seats the free bench not seated yet, unloads those no longer free, and notes who is away and where. */
    private void seatBench(Visit visit) {
        Set<Integer> seated = new HashSet<>();
        for (BenchCampForm.Seat seat : visit.seats.values()) {
            seated.add(seat.characterId);
        }
        BenchCampForm.Plan plan = form.plan(visit.owner, seated);
        Set<String> free = new HashSet<>();
        for (RosterEntry row : plan.here()) {
            free.add(row.personaId());
        }
        for (BenchCampForm.Seat seat : List.copyOf(visit.seats.values())) {
            if (!free.contains(seat.personaId)) { // called, released, or out on a live duty since
                visit.seats.remove(seat.personaId);
                if (!form.unseat(seat)) {
                    stranded.put(seat.characterId, seat);
                }
            }
        }
        List<BenchCampForm.Absent> away = new ArrayList<>(plan.away());
        for (RosterEntry row : plan.here()) {
            if (visit.seats.containsKey(row.personaId())) {
                continue;
            }
            BenchCampForm.Seat seat = form.seat(visit.owner, visit.ground.map(), row);
            if (seat != null) {
                visit.seats.put(row.personaId(), seat);
            } else {
                away.add(new BenchCampForm.Absent(row.personaId(), name(row.personaId()), BenchCampForm.POOL_FULL));
            }
        }
        visit.away = List.copyOf(away);
    }

    private void banter(Visit visit) {
        List<Character> bots = new ArrayList<>();
        for (BenchCampForm.Seat seat : visit.seats.values()) {
            if (seat.bot.getMap() == visit.ground.map()) {
                bots.add(seat.bot);
            }
        }
        if (bots.size() < 2 || visit.owner.getMap() != visit.ground.map()) {
            return;
        }
        CrewBanter.CampExchange exchange = ports.exchange(visit.owner, bots);
        if (exchange == null) {
            return;
        }
        Character caller = botOf(visit, exchange.caller().characterId());
        if (caller == null) {
            return;
        }
        ports.say(caller, exchange.call());
        int ownerId = visit.ownerId;
        ports.later("camp.banter.answer " + ownerId, exchange.answerDelayMs(), () -> {
            Visit now = visits.get(ownerId);
            Character answerer = now == null ? null : botOf(now, exchange.answerer().characterId());
            if (answerer != null) {
                ports.say(answerer, exchange.answer());
            }
        });
    }

    private static Character botOf(Visit visit, int characterId) {
        for (BenchCampForm.Seat seat : visit.seats.values()) {
            if (seat.characterId == characterId && seat.bot.getMap() == visit.ground.map()) {
                return seat.bot;
            }
        }
        return null;
    }

    /** "Your crew's camp. Mira and Olwen are here; Fenna is out to train at Ant Tunnel I." */
    String arrival(Visit visit) {
        List<String> here = new ArrayList<>();
        for (BenchCampForm.Seat seat : visit.seats.values()) {
            here.add(name(seat.personaId));
        }
        java.util.Collections.sort(here);
        StringBuilder line = new StringBuilder("Your crew's camp.");
        if (here.isEmpty()) {
            line.append(" Nobody from your bench is here right now");
        } else {
            line.append(' ').append(join(here)).append(here.size() == 1 ? " is here" : " are here");
        }
        List<String> away = new ArrayList<>();
        for (BenchCampForm.Absent absent : visit.away) {
            away.add(absent.name() + " is " + absent.where());
        }
        if (!away.isEmpty()) {
            line.append("; ").append(String.join(", ", away.subList(0, Math.min(3, away.size()))));
            if (away.size() > 3) {
                line.append(" and ").append(away.size() - 3).append(" more are away (see the notice)");
            }
        }
        return line.append('.').toString();
    }

    private static String join(List<String> names) {
        if (names.size() <= 1) {
            return names.isEmpty() ? "" : names.get(0);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
    }

    private String name(String personaId) {
        var persona = world.content() == null ? null : world.content().persona(personaId);
        return persona == null ? personaId : persona.name();
    }

    // ---- what the camp shows (any thread) ---------------------------------------------------------

    /** Whether any camp is open, or a camp companion still waits to be unloaded (the timer hands over nothing else). */
    public boolean anyOpen() {
        return !visits.isEmpty() || !stranded.isEmpty();
    }

    /** Whether {@code ownerId} is at their camp now. */
    public boolean inCamp(int ownerId) {
        return visits.containsKey(ownerId);
    }

    /** The persona ids of the owner's companions seated at their camp now (empty when they are not there). */
    public List<String> seated(int ownerId) {
        Visit visit = visits.get(ownerId);
        return visit == null ? List.of() : List.copyOf(visit.seats.keySet());
    }

    /** Who of the owner's bench is away, and where (empty when they are not at camp). */
    public List<BenchCampForm.Absent> away(int ownerId) {
        Visit visit = visits.get(ownerId);
        return visit == null ? List.of() : visit.away;
    }

    /** Camp companions loaded now, server-wide (the CAMP pool's use; the stranded still count). */
    public int liveCount() {
        int count = stranded.size();
        for (Visit visit : visits.values()) {
            count += visit.seats.size();
        }
        return count;
    }

    /** The click on an NPC was the owner's camp notice. */
    public boolean isNotice(Character clicker, int objectId) {
        Visit visit = clicker == null ? null : visits.get(clicker.getId());
        return visit != null && visit.noticeId != 0 && visit.noticeId == objectId && clicker.getMap() == visit.ground.map();
    }

    /** The persona of the camp companion {@code target} the owner {@code clicker} double-clicked at camp; null if none. */
    public String campCompanion(Character clicker, Character target) {
        Visit visit = clicker == null || target == null ? null : visits.get(clicker.getId());
        if (visit == null || clicker.getMap() != visit.ground.map()) {
            return null;
        }
        for (BenchCampForm.Seat seat : visit.seats.values()) {
            if (seat.characterId == target.getId()) {
                return seat.personaId;
            }
        }
        return null;
    }

    // ---- the game's ports ---------------------------------------------------------------------------

    static final class LivePorts implements Ports {
        private final LivingWorld world;

        LivePorts(LivingWorld world) {
            this.world = world;
        }

        @Override
        public boolean on() {
            return world.config().CREW_CAMP_ENABLED;
        }

        @Override
        public int campMapId() {
            return world.config().CREW_CAMP_MAP_ID;
        }

        @Override
        public long now() {
            return System.currentTimeMillis();
        }

        @Override
        public Place place(Character owner) {
            MapleMap map = owner.getMap();
            boolean boss = false;
            if (map != null) {
                for (Monster mob : AgentMapPerception.monsters(map)) {
                    if (mob.isBoss() && mob.isAlive()) {
                        boss = true;
                        break;
                    }
                }
            }
            return new Place(owner.getMapId(), owner.isAlive(),
                    owner.getEventInstance() != null || map != null && map.getEventInstance() != null,
                    world.commitments() != null && world.commitments().ownerCommitted(owner.getId()), boss);
        }

        @Override
        public int exitMapId(Character owner) {
            MapleMap map = owner.getMap();
            if (map == null || map.isTown()) {
                return owner.getMapId();
            }
            int town = map.getReturnMapId();
            return town > 0 && town != 999999999 ? town : owner.getMapId();
        }

        @Override
        public Ground open(Character owner, int mapId, int exitMapId, Consumer<Character> onLeft) {
            DuelGround ground = DuelGround.open(owner, mapId, exitMapId, onLeft);
            if (ground == null) {
                return null;
            }
            for (Portal portal : ground.map().getPortals()) {
                portal.setPortalStatus(false); // a door would lead into the stock map's town, not back where you were
            }
            return new Ground() {
                @Override
                public MapleMap map() {
                    return ground.map();
                }

                @Override
                public Point landing() {
                    Portal spawn = ground.map().getPortal(0);
                    return spawn != null && spawn.getPosition() != null ? new Point(spawn.getPosition()) : new Point(0, 0);
                }

                @Override
                public void enter(Character chr, Point at) {
                    ground.enter(chr, at);
                }

                @Override
                public void close(Collection<Integer> followers) {
                    ground.close(followers);
                }
            };
        }

        @Override
        public List<Character> partyBeside(Character owner) {
            List<Character> out = new ArrayList<>();
            Party party = owner.getParty();
            MapleMap map = owner.getMap();
            if (party == null || map == null) {
                return out;
            }
            for (PartyCharacter member : party.getMembers()) {
                int id = member.getId();
                if (id == owner.getId() || world.directory().ownerOfCharacter(id) != owner.getId()) {
                    continue;
                }
                Character bot = map.getCharacterById(id);
                if (bot != null && bot.isAlive() && AgentPresence.isAgent(bot)) {
                    out.add(bot);
                }
            }
            return out;
        }

        @Override
        public int putUpNotice(MapleMap map, Point near) {
            NPC npc = LifeFactory.getNPC(NOTICE_NPC);
            Point at = new Point(near.x + 60, near.y);
            Point ground = map.getPointBelow(new Point(at.x, at.y - 30));
            Foothold foothold = ground == null ? null : map.getFootholds().findBelow(new Point(at.x, at.y - 30));
            if (npc == null || ground == null || foothold == null) {
                return 0;
            }
            npc.setPosition(ground);
            npc.setCy(ground.y);
            npc.setRx0(ground.x + 50);
            npc.setRx1(ground.x - 50);
            npc.setFh(foothold.getId());
            map.addMapObject(npc);
            map.broadcastMessage(PacketCreator.spawnNPC(npc));
            return npc.getObjectId();
        }

        @Override
        public void tell(Character owner, String line) {
            owner.dropMessage(5, line);
        }

        @Override
        public void submit(String what, Runnable work) {
            world.executor().submit(what, work);
        }

        @Override
        public void later(String what, long delayMs, Runnable work) {
            TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY,
                    () -> world.executor().submit(what, work), Math.max(0L, delayMs));
        }

        @Override
        public void say(Character bot, String line) {
            AgentDialogueTransportRuntime.sayMapNow(bot, line);
        }

        @Override
        public CrewBanter.CampExchange exchange(Character owner, List<Character> bots) {
            return world.crew() == null ? null : world.crew().campExchange(owner.getId(), owner.getName(), bots);
        }
    }
}
