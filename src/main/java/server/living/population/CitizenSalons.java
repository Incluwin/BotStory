package server.living.population;

import client.Character;
import client.SkinColor;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Luxury spending (docs/ECONOMY_PLAN.md E4b, section 9.6): now and then a citizen well off for its level goes to a
 * stylist: a hair salon, a plastic surgeon or a skin-care shop. It walks up, pays the stylist's 1,000,000 (what each of
 * their scripts charges for its coupon) and leaves with a new look the players around it see: a hairstyle from the
 * salon's VIP list or its own in another colour, a face from the surgeon's list in its own eye colour, or a skin tone
 * from the shop's list.
 *
 * <p>Whether it goes is the economy controller's luxury lever (EconomyController): once a UTC day for each look, a
 * loaded citizen that is no player's hired hand and no persona, holding the price over
 * economy.CONTROLLER_LUXURY_OVER x Market.expectedMeso for its level, goes with that chance. Each look's roll is kept
 * in living_luxury_roll, so a restart does not roll the same day again. A region with no stylist for a look sends its
 * citizens to the next town along the game's boats, cabins and portals that has one ({@link #NEXT}). The visit is a
 * TOWN visit to the stylist's map (PresenceScheduler); logged out, moved on or short of meso on the way, it pays
 * nothing. Then the scheduler plans its next activity.</p>
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
    /** How many towns along {@link #NEXT} a citizen looks for a stylist past its own region. */
    private static final int MAX_HOPS = 3;

    /** What a stylist changes. */
    enum Look {
        HAIR("hair salon"), FACE("plastic surgeon"), SKIN("skin-care shop");

        final String shop;

        Look(String shop) {
            this.shop = shop;
        }
    }

    /** A VIP stylist, its map, the town, the citizen home regions that go there, and the look it changes. */
    record Salon(int npcId, int mapId, String town, Set<String> regions, Look look) { }

    static final List<Salon> SALONS = List.of(
            new Salon(1012103, 100000104, "Henesys", Set.of("victoria"), Look.HAIR),
            new Salon(1052100, 103000005, "Kerning City", Set.of("victoria"), Look.HAIR),
            new Salon(2010001, 200000202, "Orbis", Set.of("orbis", "el-nath"), Look.HAIR),
            new Salon(2041007, 220000004, "Ludibrium", Set.of("ludibrium"), Look.HAIR),
            new Salon(2090100, 250000003, "Mu Lung", Set.of("mu-lung"), Look.HAIR),
            new Salon(9120100, 801000001, "Showa", Set.of("zipangu"), Look.HAIR),
            new Salon(9201015, 680000002, "Amoria", Set.of("amoria"), Look.HAIR),
            new Salon(9201064, 600000001, "New Leaf City", Set.of("new-leaf-city"), Look.HAIR),
            new Salon(1052004, 100000103, "Henesys", Set.of("victoria"), Look.FACE),
            new Salon(2010002, 200000201, "Orbis", Set.of("orbis", "el-nath"), Look.FACE),
            new Salon(2041010, 220000003, "Ludibrium", Set.of("ludibrium"), Look.FACE),
            new Salon(2090103, 250000000, "Mu Lung", Set.of("mu-lung"), Look.FACE),
            new Salon(2100008, 260000000, "Ariant", Set.of("ariant"), Look.FACE),
            new Salon(9120102, 801000002, "Showa", Set.of("zipangu"), Look.FACE),
            new Salon(9201018, 680000003, "Amoria", Set.of("amoria"), Look.FACE),
            new Salon(9201069, 600000001, "New Leaf City", Set.of("new-leaf-city"), Look.FACE),
            new Salon(9270024, 540000000, "Singapore", Set.of("singapore"), Look.FACE),
            new Salon(1012105, 100000105, "Henesys", Set.of("victoria"), Look.SKIN),
            new Salon(2012008, 200000203, "Orbis", Set.of("orbis", "el-nath"), Look.SKIN),
            new Salon(2041013, 220000005, "Ludibrium", Set.of("ludibrium"), Look.SKIN),
            new Salon(2090102, 250000000, "Mu Lung", Set.of("mu-lung"), Look.SKIN),
            new Salon(2100007, 260000000, "Ariant", Set.of("ariant"), Look.SKIN),
            new Salon(9201065, 600000001, "New Leaf City", Set.of("new-leaf-city"), Look.SKIN),
            new Salon(9270025, 540000000, "Singapore", Set.of("singapore"), Look.SKIN));

    /**
     * Where a region with no stylist for a look sends its citizens: the next region along the way a player travels from
     * it (FieldCatalogMain.region names them).
     */
    static final Map<String, String> NEXT = Map.ofEntries(
            Map.entry("ereve", "victoria"),             // the Ellinia ship
            Map.entry("rien", "victoria"),              // the Lith Harbor ship
            Map.entry("ellin-forest", "victoria"),
            Map.entry("coke-town", "victoria"),         // its guide stands on Victoria's event maps
            Map.entry("singapore", "victoria"),         // the Kerning City airport
            Map.entry("malaysia", "singapore"),
            Map.entry("aquarium", "orbis"),             // up the Orbis Tower
            Map.entry("leafre", "orbis"),               // the cabin
            Map.entry("ariant", "orbis"),               // the genie
            Map.entry("temple-of-time", "leafre"),      // the dragon ride
            Map.entry("crimsonwood", "new-leaf-city")); // through the Phantom Forest

    private final Population population;
    private final PresenceScheduler presence;
    private final Random random = new Random();
    /** citizen id x look ({@link #key}) -> the UTC day it last rolled for that look. */
    private final Map<Long, Long> rolledDay = new ConcurrentHashMap<>();
    private volatile boolean rollsLoaded;
    private volatile Set<Integer> personas;
    LongSupplier clock = System::currentTimeMillis;
    /** Runs a task after a delay (ms); a test runs them by hand. */
    BiConsumer<Runnable, Long> later = (task, delayMs) ->
            TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY, task, delayMs);
    /** Runs a store write off the scheduler's lock; a test runs it at once. */
    Consumer<Runnable> io = task -> TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY, task, 0L);
    /** The economy controller's luxury lever: the daily chance a citizen who can goes, for each look. */
    DoubleSupplier luxuryRate;
    /** economy.CONTROLLER_LUXURY_OVER: how many times the meso its level should carry it holds besides the price. */
    DoubleSupplier luxuryOver = () -> YamlConfig.config.economy.CONTROLLER_LUXURY_OVER;
    /**
     * A stylist's VIP list from its NPC script, {male, female}: the mhair_v / fhair_v or mface_v / fface_v arrays, or
     * the skin array for both.
     */
    BiFunction<Integer, Look, int[][]> styles = CitizenSalons::scriptStyles;
    /** True for a hair or face id the game has (a name in String.wz, as the stylists' scripts check). */
    IntPredicate lookExists = id -> ItemInformationProvider.getInstance().getName(id) != null;
    /**
     * The payment and new look on the citizen's own mailbox: completes with the new hair, face or skin id, or -1 when
     * nothing changed (a light skin is 0).
     */
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
     * At a plan: the stylist this citizen goes to now, or 0. Each look is rolled once a UTC day, with the luxury lever's
     * chance, for a citizen that may go ({@link #salonFor}); the looks not yet rolled today are tried in a random order,
     * so one plan sends it to one stylist at most and a later plan may roll the others.
     */
    int planned(CitizenSession session, long now) {
        double rate = luxuryRate.getAsDouble();
        if (rate <= 0.0d) {
            return 0;
        }
        long day = now / DAY_MS;
        List<Look> looks = new ArrayList<>(List.of(Look.values()));
        Collections.shuffle(looks, random);
        for (Look look : looks) {
            Salon salon = salonFor(session, look);
            if (salon != null && rollToday(session.characterId, look, day) && random.nextDouble() < rate) {
                return salon.npcId();
            }
        }
        return 0;
    }

    /** True when this citizen has not rolled for this look today: it is marked rolled, and kept across a restart. */
    private boolean rollToday(int characterId, Look look, long day) {
        loadRolls();
        Long last = rolledDay.put(key(characterId, look), day);
        if (last != null && last == day) {
            return false;
        }
        io.accept(() -> population.store().saveLuxuryRoll(characterId, look.name(), day));
        return true;
    }

    /** At the first roll after a start: the days kept in living_luxury_roll. */
    private void loadRolls() {
        if (rollsLoaded) {
            return;
        }
        synchronized (rolledDay) {
            if (rollsLoaded) {
                return;
            }
            Map<Integer, Map<String, Long>> kept = population.store().luxuryRolls();
            if (kept != null) {
                kept.forEach((characterId, days) -> days.forEach((name, day) -> {
                    Look look = Arrays.stream(Look.values()).filter(l -> l.name().equals(name)).findFirst().orElse(null);
                    if (look != null && day != null) {
                        rolledDay.merge(key(characterId, look), day, Math::max);
                    }
                }));
            }
            rollsLoaded = true;
        }
    }

    private static long key(int characterId, Look look) {
        return (long) characterId << 2 | look.ordinal();
    }

    /** Its hair salon (!citizens salon without a look). */
    Salon salonFor(CitizenSession session) {
        return salonFor(session, Look.HAIR);
    }

    /**
     * The stylist for this look this loaded citizen may go to, or null: no hired hand, no persona (its look is drawn
     * elsewhere), the price on top of what its level should carry times economy.CONTROLLER_LUXURY_OVER, and a stylist
     * in its region or the nearest one along {@link #NEXT}; among several, always its usual one.
     */
    Salon salonFor(CitizenSession session, Look look) {
        Character agent = session.virtual ? null : session.agent();
        Citizen citizen = population.citizen(session.characterId);
        if (agent == null || citizen == null || citizen.hired() || personas().contains(session.characterId)) {
            return null;
        }
        if (agent.getMeso() < PRICE + luxuryOver.getAsDouble() * Market.expectedMeso(agent.getLevel())) {
            return null;
        }
        String region = citizen.homeRegion();
        for (int hop = 0; region != null && hop <= MAX_HOPS; hop++) {
            List<Salon> near = new ArrayList<>();
            for (Salon salon : SALONS) {
                if (salon.look() == look && salon.regions().contains(region)) {
                    near.add(salon);
                }
            }
            if (!near.isEmpty()) {
                return near.get(Math.floorMod(session.characterId, near.size())); // its usual stylist
            }
            region = NEXT.get(region);
        }
        return null;
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

    /** On the stylist's map: it walks up to the stylist. False when it has nothing to go for here after all. */
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
        log.info("citizen {} goes to the {} {}", session.name(), salon.town(), salon.look().shop);
        return true;
    }

    /** It looks again: there yet, done choosing, then it pays and the stylist gives it its new look. */
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
            done(visit, -1);
            return;
        }
        result.whenComplete((look, failure) -> later.accept(() -> done(visit, failure == null && look != null ? look : -1), 0L));
    }

    /** New look or not (-1), the visit is over and the scheduler plans its next activity. */
    void done(Visit visit, int look) {
        CitizenSession session = visit.session;
        if (presence.session(session.characterId) != session) {
            return; // logged out meanwhile
        }
        if (look >= 0) {
            log.info("citizen {} had its {} done at the {} {} for {} meso: now {}", session.name(),
                    visit.salon.look().name().toLowerCase(), visit.salon.town(), visit.salon.look().shop, PRICE, look);
        } else {
            log.info("citizen {} left the {} {} without a new look", session.name(), visit.salon.town(),
                    visit.salon.look().shop);
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

    private int[] vip(int npcId, Look look, int gender) {
        int[][] lists = styles.apply(npcId, look);
        return lists == null || gender < 0 || gender >= lists.length || lists[gender] == null ? new int[0] : lists[gender];
    }

    /**
     * The new hair for {@code hair} at this salon, or 0 when there is none: half the time one of its VIP styles in the
     * citizen's own colour, else its own style in another colour, the other way round when the first has nothing.
     */
    int newHair(int npcId, int hair, int gender, Random draw) {
        List<Integer> styled = new ArrayList<>();
        for (int style : vip(npcId, Look.HAIR, gender)) {
            int candidate = style / 10 * 10 + hair % 10;
            if (candidate != hair && lookExists.test(candidate)) {
                styled.add(candidate);
            }
        }
        List<Integer> dyed = new ArrayList<>();
        for (int colour = 0; colour < 8; colour++) {
            int candidate = hair / 10 * 10 + colour;
            if (candidate != hair && lookExists.test(candidate)) {
                dyed.add(candidate);
            }
        }
        List<Integer> first = draw.nextBoolean() ? styled : dyed;
        List<Integer> pick = first.isEmpty() ? (first == styled ? dyed : styled) : first;
        return pick.isEmpty() ? 0 : pick.get(draw.nextInt(pick.size()));
    }

    /**
     * The new face for {@code face} at this surgeon, or 0 when there is none: one of its VIP faces in the citizen's own
     * eye colour (the hundreds digit), or in the face's own colour when the game lacks that one, as the scripts do.
     */
    int newFace(int npcId, int face, int gender, Random draw) {
        int colour = face % 1000 / 100;
        List<Integer> faces = new ArrayList<>();
        for (int style : vip(npcId, Look.FACE, gender)) {
            int base = style - style % 1000 / 100 * 100;
            int candidate = lookExists.test(base + colour * 100) ? base + colour * 100 : base;
            if (candidate != face && lookExists.test(candidate) && !faces.contains(candidate)) {
                faces.add(candidate);
            }
        }
        return faces.isEmpty() ? 0 : faces.get(draw.nextInt(faces.size()));
    }

    /** The new skin for {@code skin} at this shop, or -1 when there is none: another tone from its list. */
    int newSkin(int npcId, int skin, Random draw) {
        List<Integer> tones = new ArrayList<>();
        for (int tone : vip(npcId, Look.SKIN, 0)) {
            if (tone != skin && SkinColor.getById(tone) != null && !tones.contains(tone)) {
                tones.add(tone);
            }
        }
        return tones.isEmpty() ? -1 : tones.get(draw.nextInt(tones.size()));
    }

    private CompletableFuture<Integer> restyleOnMailbox(AgentRuntimeEntry entry, Integer npcId) {
        Salon salon = salon(npcId);
        return AgentMailboxRuntime.dispatch(entry, runtime -> {
            Character agent = AgentRuntimeIdentityRuntime.bot(runtime);
            if (agent == null || salon == null || agent.getMeso() < PRICE) {
                return -1;
            }
            int look = switch (salon.look()) {
                case HAIR -> newHair(npcId, agent.getHair(), agent.getGender(), random);
                case FACE -> newFace(npcId, agent.getFace(), agent.getGender(), random);
                case SKIN -> newSkin(npcId, agent.getSkinColor().getId(), random);
            };
            if (look < 0 || look == 0 && salon.look() != Look.SKIN) {
                return -1;
            }
            try (var ledgerScope = MesoFlows.as(MesoAccount.SERVICE)) {
                agent.gainMeso((int) -PRICE, false);
            }
            switch (salon.look()) {
                case HAIR -> {
                    agent.setHair(look);
                    agent.updateSingleStat(Stat.HAIR, look);
                }
                case FACE -> {
                    agent.setFace(look);
                    agent.updateSingleStat(Stat.FACE, look);
                }
                case SKIN -> {
                    agent.setSkinColor(SkinColor.getById(look));
                    agent.updateSingleStat(Stat.SKIN, look);
                }
            }
            agent.equipChanged(); // the players around it see the new look
            return look;
        });
    }

    private static final Pattern VIP = Pattern.compile("\\b([mf])(hair|face)_v\\s*=\\s*Array\\(([^)]*)\\)");
    private static final Pattern SKINS = Pattern.compile("\\bskin\\s*=\\s*Array\\(([^)]*)\\)");

    /** The stylist's VIP list from scripts/npc/{npcId}.js, {male, female}; empty lists when it cannot be read. */
    static int[][] scriptStyles(int npcId, Look look) {
        int[][] out = {new int[0], new int[0]};
        try {
            String script = Files.readString(Path.of("scripts/npc/" + npcId + ".js"));
            if (look == Look.SKIN) {
                Matcher matcher = SKINS.matcher(script);
                if (matcher.find()) {
                    out[0] = ids(matcher.group(1));
                    out[1] = out[0];
                }
                return out;
            }
            Matcher matcher = VIP.matcher(script);
            while (matcher.find()) {
                if (matcher.group(2).equals(look == Look.HAIR ? "hair" : "face")) {
                    out[matcher.group(1).equals("m") ? 0 : 1] = ids(matcher.group(3));
                }
            }
        } catch (IOException | NumberFormatException failure) {
            log.warn("stylist styles for {} not read", npcId, failure);
        }
        return out;
    }

    private static int[] ids(String list) {
        return Arrays.stream(list.split(",")).map(String::trim).filter(id -> !id.isEmpty()).mapToInt(Integer::parseInt).toArray();
    }
}
