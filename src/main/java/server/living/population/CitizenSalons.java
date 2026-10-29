package server.living.population;

import client.Character;
import client.Stat;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.TimerManager;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.economy.ledger.MesoAccount;
import server.economy.ledger.MesoFlows;
import server.life.NPC;
import server.maps.MapleMap;

import java.awt.Point;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.DoubleSupplier;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Luxury spending (docs/ECONOMY_PLAN.md E4b): now and then a citizen well off for its level goes to its region's hair
 * salon, walks up to the stylist, pays the salon's 1,000,000 (what its script charges for a coupon) and leaves with a
 * new hairstyle from the salon's VIP list or a new colour of its own, which the players around it see.
 *
 * <p>Whether it goes is the economy controller's luxury lever (EconomyController): once a UTC day, a loaded citizen that
 * is no player's hired hand and no persona, from a region with a salon, holding the price over
 * economy.CONTROLLER_LUXURY_OVER x Market.expectedMeso for its level, goes with that chance. The visit is a TOWN visit to
 * the salon's map (PresenceScheduler); logged out, moved on or short of meso on the way, it pays nothing. Then the
 * scheduler plans its next activity.</p>
 */
final class CitizenSalons {
    private static final Logger log = LoggerFactory.getLogger(CitizenSalons.class);
    static final long PRICE = 1_000_000L;
    /** Close enough to the stylist to talk. */
    private static final int REACH_PX = 60;
    private static final long CHECK_MS = 1_000L;
    static final long WALK_MAX_MS = 60_000L;
    /** A moment at the chair, choosing. */
    static final long CHOOSE_MIN_MS = 4_000L;
    private static final long CHOOSE_SPAN_MS = 6_000L;
    private static final long DAY_MS = 86_400_000L;

    /** A VIP stylist, its salon's map, the town, and the citizen home regions that go there. */
    record Salon(int npcId, int mapId, String town, Set<String> regions) { }

    static final List<Salon> SALONS = List.of(
            new Salon(1012103, 100000104, "Henesys", Set.of("victoria")),
            new Salon(1052100, 103000005, "Kerning City", Set.of("victoria")),
            new Salon(2010001, 200000202, "Orbis", Set.of("orbis", "el-nath")),
            new Salon(2041007, 220000004, "Ludibrium", Set.of("ludibrium")),
            new Salon(2090100, 250000003, "Mu Lung", Set.of("mu-lung")),
            new Salon(9120100, 801000001, "Showa", Set.of("zipangu")),
            new Salon(9201015, 680000002, "Amoria", Set.of("amoria")),
            new Salon(9201064, 600000001, "New Leaf City", Set.of("new-leaf-city")));

    private final Population population;
    private final PresenceScheduler presence;
    private final Random random = new Random();
    private final Map<Integer, Long> rolledDay = new ConcurrentHashMap<>();
    private volatile Set<Integer> personas;
    LongSupplier clock = System::currentTimeMillis;
    /** Runs a task after a delay (ms); a test runs them by hand. */
    BiConsumer<Runnable, Long> later = (task, delayMs) ->
            TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY, task, delayMs);
    /** The economy controller's luxury lever: the daily chance a citizen who can goes. */
    DoubleSupplier luxuryRate;
    /** economy.CONTROLLER_LUXURY_OVER: how many times the meso its level should carry it holds besides the price. */
    DoubleSupplier luxuryOver = () -> YamlConfig.config.economy.CONTROLLER_LUXURY_OVER;
    /** A salon's VIP hairstyles, {male, female}, from its NPC script (the mhair_v / fhair_v arrays). */
    IntFunction<int[][]> styles = CitizenSalons::scriptStyles;
    /** True for a hair id the game has (a name in String.wz, as the salon scripts check). */
    IntPredicate hairExists = hair -> ItemInformationProvider.getInstance().getName(hair) != null;
    /** The payment and new look on the citizen's own mailbox: completes with the new hair, or 0 when nothing changed. */
    BiFunction<AgentRuntimeEntry, Integer, CompletableFuture<Integer>> restyle = this::restyleOnMailbox;

    CitizenSalons(Population population, PresenceScheduler presence) {
        this.population = population;
        this.presence = presence;
        this.luxuryRate = () -> {
            EconomyController controller = population.economyController();
            return controller == null ? 0.0d : controller.levers().luxuryRate();
        };
    }

    /** One walk up to a stylist. */
    static final class Visit {
        final CitizenSession session;
        final Salon salon;
        final Point spot;
        final long startedAtMs;
        long reachedAtMs;
        long chooseMs;

        Visit(CitizenSession session, Salon salon, Point spot, long startedAtMs) {
            this.session = session;
            this.salon = salon;
            this.spot = spot;
            this.startedAtMs = startedAtMs;
        }
    }

    /**
     * At a plan: the stylist this citizen goes to now, or 0. Rolled once a UTC day, with the luxury lever's chance, for
     * a citizen that may go ({@link #salonFor}).
     */
    int planned(CitizenSession session, long now) {
        double rate = luxuryRate.getAsDouble();
        Salon salon = rate > 0.0d ? salonFor(session) : null;
        if (salon == null) {
            return 0;
        }
        Long last = rolledDay.put(session.characterId, now / DAY_MS);
        if (last != null && last == now / DAY_MS) {
            return 0;
        }
        return random.nextDouble() < rate ? salon.npcId() : 0;
    }

    /**
     * The salon this loaded citizen may go to, or null: no hired hand, no persona (its look is drawn elsewhere), a region
     * with a salon, and the price on top of what its level should carry times economy.CONTROLLER_LUXURY_OVER.
     */
    Salon salonFor(CitizenSession session) {
        Character agent = session.virtual ? null : session.agent();
        Citizen citizen = population.citizen(session.characterId);
        if (agent == null || citizen == null || citizen.hired() || personas().contains(session.characterId)) {
            return null;
        }
        if (agent.getMeso() < PRICE + luxuryOver.getAsDouble() * Market.expectedMeso(agent.getLevel())) {
            return null;
        }
        List<Salon> near = new ArrayList<>();
        for (Salon salon : SALONS) {
            if (salon.regions().contains(citizen.homeRegion())) {
                near.add(salon);
            }
        }
        return near.isEmpty() ? null : near.get(Math.floorMod(session.characterId, near.size())); // its usual salon
    }

    private Set<Integer> personas() {
        Set<Integer> known = personas;
        if (known == null) {
            known = population.store().personaCharacterIds();
            personas = known;
        }
        return known;
    }

    static Salon salon(int npcId) {
        for (Salon salon : SALONS) {
            if (salon.npcId() == npcId) {
                return salon;
            }
        }
        return null;
    }

    /** On the salon's map: it walks up to the stylist. False when it has nothing to go for here after all. */
    boolean begin(CitizenSession session, MapleMap map) {
        Salon salon = salon(session.salonNpc);
        NPC npc = salon == null || map == null || map.getId() != salon.mapId() ? null : map.getNPCById(salon.npcId());
        if (npc == null || session.entry == null || session.agent() == null) {
            session.salonNpc = 0;
            return false;
        }
        Point spot = npc.getPosition();
        AgentMovementCommandRuntime.moveTo(session.entry, spot, false);
        Visit visit = new Visit(session, salon, spot, clock.getAsLong());
        later.accept(() -> step(visit), CHECK_MS);
        log.info("citizen {} goes to the {} hair salon", session.name(), salon.town());
        return true;
    }

    /** It looks again: there yet, done choosing, then it pays and the stylist does its hair. */
    void step(Visit visit) {
        if (!current(visit)) {
            return;
        }
        long now = clock.getAsLong();
        Point at = visit.session.agent().getPosition();
        boolean there = at != null && at.distance(visit.spot) <= REACH_PX;
        if (!there && now - visit.startedAtMs < WALK_MAX_MS) {
            later.accept(() -> step(visit), CHECK_MS);
            return;
        }
        if (visit.reachedAtMs == 0L) {
            visit.reachedAtMs = now;
            visit.chooseMs = CHOOSE_MIN_MS + (long) (random.nextDouble() * CHOOSE_SPAN_MS);
        }
        long left = visit.reachedAtMs + visit.chooseMs - now;
        if (left > 0L) {
            later.accept(() -> step(visit), left);
            return;
        }
        CompletableFuture<Integer> result = restyle.apply(visit.session.entry, visit.salon.npcId());
        if (result == null) {
            done(visit, 0);
            return;
        }
        result.whenComplete((hair, failure) -> later.accept(() -> done(visit, failure == null && hair != null ? hair : 0), 0L));
    }

    /** Restyled or not, the visit is over and the scheduler plans its next activity. */
    void done(Visit visit, int hair) {
        CitizenSession session = visit.session;
        if (presence.session(session.characterId) != session) {
            return; // logged out meanwhile
        }
        if (hair > 0) {
            log.info("citizen {} had its hair done at the {} salon for {} meso: now {}", session.name(), visit.salon.town(),
                    PRICE, hair);
        } else {
            log.info("citizen {} left the {} salon without a new look", session.name(), visit.salon.town());
        }
        session.salonNpc = 0;
        presence.replan(session, clock.getAsLong());
    }

    /** Still the same visit: its session, loaded, still on a TOWN visit to this stylist. */
    private boolean current(Visit visit) {
        CitizenSession session = visit.session;
        return presence.session(session.characterId) == session && session.loaded()
                && session.activity == CitizenSession.Activity.TOWN && session.salonNpc == visit.salon.npcId();
    }

    /**
     * The new hair for {@code hair} at this salon, or 0 when there is none: half the time one of its VIP styles in the
     * citizen's own colour, else its own style in another colour, the other way round when the first has nothing.
     */
    int newHair(int npcId, int hair, int gender, Random draw) {
        List<Integer> styled = new ArrayList<>();
        int[][] vip = styles.apply(npcId);
        for (int style : vip == null || gender < 0 || gender >= vip.length ? new int[0] : vip[gender]) {
            int candidate = style / 10 * 10 + hair % 10;
            if (candidate != hair && hairExists.test(candidate)) {
                styled.add(candidate);
            }
        }
        List<Integer> dyed = new ArrayList<>();
        for (int colour = 0; colour < 8; colour++) {
            int candidate = hair / 10 * 10 + colour;
            if (candidate != hair && hairExists.test(candidate)) {
                dyed.add(candidate);
            }
        }
        List<Integer> first = draw.nextBoolean() ? styled : dyed;
        List<Integer> pick = first.isEmpty() ? (first == styled ? dyed : styled) : first;
        return pick.isEmpty() ? 0 : pick.get(draw.nextInt(pick.size()));
    }

    private CompletableFuture<Integer> restyleOnMailbox(AgentRuntimeEntry entry, Integer npcId) {
        return AgentMailboxRuntime.dispatch(entry, runtime -> {
            Character agent = AgentRuntimeIdentityRuntime.bot(runtime);
            if (agent == null || agent.getMeso() < PRICE) {
                return 0;
            }
            int hair = newHair(npcId, agent.getHair(), agent.getGender(), random);
            if (hair == 0) {
                return 0;
            }
            try (var ledgerScope = MesoFlows.as(MesoAccount.SERVICE)) {
                agent.gainMeso((int) -PRICE, false);
            }
            agent.setHair(hair);
            agent.updateSingleStat(Stat.HAIR, hair);
            agent.equipChanged(); // the players around it see the new look
            return hair;
        });
    }

    private static final Pattern VIP = Pattern.compile("([mf])hair_v\\s*=\\s*Array\\(([^)]*)\\)");

    /** The salon's VIP styles from scripts/npc/{npcId}.js, {male, female}; empty lists when it cannot be read. */
    static int[][] scriptStyles(int npcId) {
        int[][] out = {new int[0], new int[0]};
        try {
            Matcher matcher = VIP.matcher(Files.readString(Path.of("scripts/npc/" + npcId + ".js")));
            while (matcher.find()) {
                out[matcher.group(1).equals("m") ? 0 : 1] = Arrays.stream(matcher.group(2).split(","))
                        .map(String::trim).filter(id -> !id.isEmpty()).mapToInt(Integer::parseInt).toArray();
            }
        } catch (IOException | NumberFormatException failure) {
            log.warn("salon styles for {} not read", npcId, failure);
        }
        return out;
    }
}
