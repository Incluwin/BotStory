package server.living.roster;

import client.Character;
import constants.id.ItemId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.movement.AgentChairService;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.integration.cosmic.CosmicAgentOfflineLoader;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

/**
 * The crew camp's idle form (docs/WORLD_SYSTEMS_PLAN.md N7.2): the benched companions of an owner who visits their camp
 * (CrewCamp), loaded onto the camp's private copy while the owner is there and unloaded, every one, when they leave. Only
 * a BENCHED row that nothing else holds sits at camp: one out on a live bench slice, lent, on a boss run or living its
 * Resident life elsewhere is away, and the notice says where ({@link #where}). Each load is charged to the CAMP pool of
 * CompanionBudget (CREW_CAMP_MAX server-wide, never counted against BENCH_LIVE, BENCH_TOWN or COMMERCE) and held by the
 * CompanionLeases CAMP lease, so no bench slice, call or second camp can load a second copy of it; past the pool the
 * rest stay away ("resting at its own camp"). A camp companion is registered director-idle (never a grinder, never a
 * follower) on its owner's channel and only idles: it walks to another spot on the floor, sits on a relaxer or stands up,
 * now and then, at the camp's one tick. Everything runs on companion-ops.
 */
public final class BenchCampForm {
    private static final Logger log = LoggerFactory.getLogger(BenchCampForm.class);
    /** At each camp tick, the chance one seated companion gets up and walks somewhere, and the chance one sits down. */
    static final double WALK_CHANCE = 0.25d;
    static final double SIT_CHANCE = 0.2d;
    /** What a companion past the CAMP pool is said to be doing. */
    static final String POOL_FULL = "resting at its own camp";

    /** One companion loaded at camp. */
    static final class Seat {
        final String personaId;
        final int characterId;
        final Character bot;
        boolean sitting;

        Seat(String personaId, int characterId, Character bot) {
            this.personaId = personaId;
            this.characterId = characterId;
            this.bot = bot;
        }
    }

    /** One of the bench not at camp, and where it is ("out to gather for your quests at Ant Tunnel I"). */
    public record Absent(String personaId, String name, String where) { }

    /** Who of an owner's bench may sit at camp, in roster order, and who is away. */
    record Plan(List<RosterEntry> here, List<Absent> away) { }

    /** What the form does to the game; tests bring their own. */
    interface Hands {
        /** The CAMP lease and a CAMP pool admission for {@code characterId}, charged to {@code ownerId}. */
        boolean take(int characterId, int ownerId);

        void release(int characterId);

        /** Loaded on {@code owner}'s channel onto {@code map} at {@code at}, registered director-idle; null if not. */
        Character load(int characterId, Character owner, MapleMap map, Point at);

        /** Out of the registry, saved and disconnected: true once it is offline. */
        boolean unload(int characterId, Character bot);

        /** A spot on the camp's floor. */
        Point spot(MapleMap map, Random random);

        void walk(Character bot, Point to);

        void sit(Character bot);

        void stand(Character bot);
    }

    private final LivingWorld world;
    private final Hands hands;
    private final Random random;

    BenchCampForm(LivingWorld world) {
        this(world, new LiveHands(world), new Random());
    }

    BenchCampForm(LivingWorld world, Hands hands, Random random) {
        this.world = world;
        this.hands = hands;
        this.random = random;
    }

    /**
     * The pure split: BENCHED rows nothing holds ({@code awayAt} null) sit at camp, the rest of the BENCHED rows are away
     * where {@code awayAt} says; rows in any other state (out with the owner, travelling, released) are neither.
     */
    static Plan plan(List<RosterEntry> rows, Function<RosterEntry, String> awayAt, Function<String, String> names) {
        List<RosterEntry> here = new ArrayList<>();
        List<Absent> away = new ArrayList<>();
        for (RosterEntry row : rows) {
            if (row.state() != RosterState.BENCHED) {
                continue;
            }
            String where = awayAt.apply(row);
            if (where == null) {
                here.add(row);
            } else {
                away.add(new Absent(row.personaId(), names.apply(row.personaId()), where));
            }
        }
        return new Plan(here, away);
    }

    /** The owner's bench as the camp sees it now; {@code seated} are its own seats (not away for being at camp). */
    Plan plan(Character owner, java.util.Set<Integer> seated) {
        return plan(world.roster().roster(owner), row -> seated.contains(characterIdOf(row.personaId())) ? null
                : where(row), personaId -> {
            Persona persona = world.content().persona(personaId);
            return persona == null ? personaId : persona.name();
        });
    }

    /** Where a benched companion is when it is not free to sit at camp; null when it is free. */
    String where(RosterEntry row) {
        BenchDutyDispatcher.Slice slice = world.benchDuty().slice(row.personaId());
        if (slice != null) {
            return "out to " + world.roster().describeAssignment(row) + " at " + CompanionView.placeName(slice.mapId());
        }
        int characterId = characterIdOf(row.personaId());
        if (characterId == 0) {
            return "not around";
        }
        CompanionLeases.Lease lease = world.leases().holder(characterId);
        if (lease != null) {
            return switch (lease.kind()) {
                case LOAN -> "lent to a friend";
                case EXPEDITION -> "on a boss run";
                case CAMP -> "at another camp";
                case RESIDENT, POPULATION -> "at home";
                default -> "busy elsewhere";
            };
        }
        if (AgentRuntimeRegistry.hasActiveAgentCharacterId(characterId)) {
            return "busy elsewhere";
        }
        return null;
    }

    int characterIdOf(String personaId) {
        return world.residents().characterIdOf(personaId);
    }

    /**
     * Seats {@code row}'s companion on the camp's copy, charged to {@code owner}: null when the CAMP pool or its lease is
     * refused, or it did not load (its lease is let go then).
     */
    Seat seat(Character owner, MapleMap map, RosterEntry row) {
        int characterId = characterIdOf(row.personaId());
        if (characterId == 0 || !hands.take(characterId, owner.getId())) {
            return null;
        }
        Character bot = null;
        try {
            bot = hands.load(characterId, owner, map, hands.spot(map, random));
        } catch (RuntimeException failure) {
            log.warn("camp companion {} failed to load", row.personaId(), failure);
        }
        if (bot == null) {
            hands.release(characterId);
            return null;
        }
        world.store().event(owner.getId(), row.personaId(), "camp.seated", "at camp map " + map.getId());
        return new Seat(row.personaId(), characterId, bot);
    }

    /** One look at a seated companion: now and then it walks somewhere else on the floor, sits down or gets up. */
    void idle(Seat seat, MapleMap map) {
        if (seat.bot.getMap() != map) {
            return;
        }
        double roll = random.nextDouble();
        if (roll < WALK_CHANCE) {
            if (seat.sitting) {
                hands.stand(seat.bot);
                seat.sitting = false;
            }
            hands.walk(seat.bot, hands.spot(map, random));
        } else if (roll < WALK_CHANCE + SIT_CHANCE && !seat.sitting) {
            hands.sit(seat.bot);
            seat.sitting = true;
        }
    }

    /** Unloads a camp companion and lets go of its lease once it is offline; true then (false: try again next tick). */
    boolean unseat(Seat seat) {
        boolean offline;
        try {
            offline = hands.unload(seat.characterId, seat.bot);
        } catch (RuntimeException failure) {
            log.warn("camp companion {} failed to unload", seat.personaId, failure);
            offline = false;
        }
        if (offline) {
            hands.release(seat.characterId);
        }
        return offline;
    }

    /** The game's hands. */
    static final class LiveHands implements Hands {
        private final LivingWorld world;

        LiveHands(LivingWorld world) {
            this.world = world;
        }

        @Override
        public boolean take(int characterId, int ownerId) {
            if (AgentRuntimeRegistry.hasActiveAgentCharacterId(characterId)
                    || !world.leases().tryAcquire(characterId, CompanionLeases.Kind.CAMP, ownerId)) {
                return false;
            }
            if (!world.budget().admit(CompanionBudget.Kind.CAMP, ownerId, System.currentTimeMillis()).allowed()) {
                world.leases().release(characterId, CompanionLeases.Kind.CAMP);
                return false;
            }
            return true;
        }

        @Override
        public void release(int characterId) {
            world.leases().release(characterId, CompanionLeases.Kind.CAMP);
        }

        @Override
        public Character load(int characterId, Character owner, MapleMap map, Point at) {
            world.benchDuty().forgetVisits(characterId); // a visit checkpointed elsewhere is not restored at camp
            Character bot;
            try {
                bot = CosmicAgentOfflineLoader.loadOfflineAgent(characterId, world.config().WORLD,
                        owner.getClient().getChannel(), map, at);
            } catch (java.sql.SQLException failure) {
                log.warn("camp companion {} did not load", characterId, failure);
                return null;
            }
            if (bot == null) {
                return null;
            }
            bot.healHpMp(); // it has rested on the bench
            if (AgentInteractionRuntime.registerDirectorIdleAgent(bot) == null) { // no grind, no follow
                unload(characterId, bot);
                return null;
            }
            return bot;
        }

        @Override
        public boolean unload(int characterId, Character bot) {
            AgentRuntimeCleanupService.removeAgentByCharacterId(characterId);
            world.benchDuty().forgetVisits(characterId);
            if (bot == null || bot.getClient() == null) {
                return true;
            }
            bot.getClient().forceDisconnect(); // the unload saves it in full (Client.disconnectInternal)
            return true;
        }

        @Override
        public Point spot(MapleMap map, Random random) {
            Rectangle area = map.getMapArea();
            if (area != null && area.width > 0 && area.height > 0) {
                for (int tries = 0; tries < 12; tries++) {
                    Point at = new Point(area.x + random.nextInt(area.width), area.y + random.nextInt(area.height));
                    Point ground = map.getPointBelow(at);
                    if (ground != null && area.contains(ground.x, ground.y - 1)) {
                        return new Point(ground.x, ground.y - 1);
                    }
                }
            }
            Portal first = map.getPortal(0);
            return first != null && first.getPosition() != null ? new Point(first.getPosition()) : new Point(0, 0);
        }

        @Override
        public void walk(Character bot, Point to) {
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(bot.getId());
            if (entry != null) {
                AgentMovementCommandRuntime.moveTo(entry, to, false);
            }
        }

        @Override
        public void sit(Character bot) {
            onMailbox(bot, (entry, agent) -> AgentChairService.sit(entry, agent, ItemId.RELAXER));
        }

        @Override
        public void stand(Character bot) {
            onMailbox(bot, AgentChairService::stand);
        }

        private static void onMailbox(Character bot, java.util.function.BiConsumer<AgentRuntimeEntry, Character> work) {
            AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(bot.getId());
            if (runtime != null) {
                AgentMailboxRuntime.dispatch(runtime, entry -> {
                    work.accept(entry, bot);
                    return null;
                });
            }
        }
    }
}
