package server.agents.capabilities.partyquest.opq;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 2026-10-18: who works which room of a companion run its human leader leads (the stock six-Agent layout in
 * AgentOpqCoordinator.assignSplitRooms needs six Agents and an Agent leader). Eak answers only the event leader
 * (2013001.js), so every hand-in and every puzzle check in five of the six rooms needs the human: he holds no room and
 * goes where the companions call him. The storage room needs nobody at Eak (its right box drops the piece and sets
 * statusStg2 itself), so a companion clears it alone.
 * <p>
 * The plan is a pure function of the companions the run counts (id order), the room each works now and whether it
 * carries a statue piece, and the rooms complete: a companion keeps its room until the room is complete; a companion
 * that carries a piece takes it to the statue first; every other one is free. Free companions take, in order, the
 * open rooms nobody works (walkway, storage, lobby, the way up, the lounge), then the sealed room once three are free
 * for its three platforms, then help in the lounge once the sealed room is staffed or done, as many as it has subrooms
 * open that they reach; the rest wait in the centre. With five companions the first pass gives each of them one of those
 * five rooms.
 * <p>
 * 2026-10-20 (live run: a Fighter stood at the lounge's door for 43 minutes): three of the lounge's four subrooms sit up
 * ledges a base jump cannot reach, so the lounge wants a climber (a companion whose jump with its Haste reaches
 * AgentOpqCoordinator.LOUNGE_JUMP). A climber the pass hands another room swaps with the non-climber it hands the
 * lounge, and a free climber joins a lounge none of whose workers climbs; a companion already working keeps its room.
 */
final class AgentOpqConductorPlan {
    /** The rooms a single companion works, in the order free companions take them. */
    static final List<AgentOpqDefinition.Room> SOLO_ROOMS = List.of(
            AgentOpqDefinition.Room.WALKWAY, AgentOpqDefinition.Room.STORAGE, AgentOpqDefinition.Room.LOBBY,
            AgentOpqDefinition.Room.ON_WAY_UP, AgentOpqDefinition.Room.LOUNGE);
    /** Players Eak counts on the sealed room's platforms. */
    static final int SEALED_HOLDERS = 3;

    /** What a companion does this pass. */
    enum Duty { ROOM, CARRY, WAIT }

    /**
     * One companion as the plan reads it: the room it works now (null for none), whether it carries a piece and
     * whether it climbs the lounge's ledges.
     */
    record Companion(int id, AgentOpqDefinition.Room room, boolean carrying, boolean climber) {
        /** A companion that climbs (the plan before the lounge's ledges were read). */
        Companion(int id, AgentOpqDefinition.Room room, boolean carrying) {
            this(id, room, carrying, true);
        }
    }

    /** A companion's assignment: its duty and, for ROOM, the room. */
    record Assignment(Duty duty, AgentOpqDefinition.Room room) {
        static final Assignment CARRY = new Assignment(Duty.CARRY, null);
        static final Assignment WAIT = new Assignment(Duty.WAIT, null);
    }

    /**
     * The lounge subrooms a free companion that helps there would get: {@code high} the open ones up the ledges a climber
     * reaches (920010601-603), {@code low} the low one (920010604), each nobody works or will be given first.
     */
    record LoungeSeats(int high, int low) {
        /** As many as there are helpers (the plan before the lounge's seats were counted). */
        static final LoungeSeats ANY = new LoungeSeats(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    private AgentOpqConductorPlan() {
    }

    /** Every companion's assignment, by id in id order, on a day only the lounge wants a climber. */
    static Map<Integer, Assignment> plan(List<Companion> companions, Set<AgentOpqDefinition.Room> complete) {
        return plan(companions, complete, EnumSet.of(AgentOpqDefinition.Room.LOUNGE));
    }

    /** Every companion's assignment, by id in id order, with a seat in the lounge for every helper. */
    static Map<Integer, Assignment> plan(List<Companion> companions, Set<AgentOpqDefinition.Room> complete,
                                         Set<AgentOpqDefinition.Room> climbRooms) {
        return plan(companions, complete, climbRooms, LoungeSeats.ANY);
    }

    /**
     * Every companion's assignment, by id in id order. {@code climbRooms}: the rooms that want a climber, taken in
     * SOLO_ROOMS order (2026-10-24: the lobby on a day whose LP box sits above a base jump, before the lounge, so a lone
     * climber clears the lobby first and then joins the lounge as a free climber). {@code seats}: the lounge subrooms a
     * helper would get; a free companion helps there only with a seat it reaches, a climber's first (2026-10-25 live,
     * run 6: after the sealed room three companions that cannot climb stood at the lounge's entry for eight minutes, its
     * low subroom done and the high ones out of their reach).
     */
    static Map<Integer, Assignment> plan(List<Companion> companions, Set<AgentOpqDefinition.Room> complete,
                                         Set<AgentOpqDefinition.Room> climbRooms, LoungeSeats seats) {
        Map<AgentOpqDefinition.Room, List<Integer>> owners = new EnumMap<>(AgentOpqDefinition.Room.class);
        for (AgentOpqDefinition.Room room : AgentOpqDefinition.Room.values()) owners.put(room, new ArrayList<>());
        Map<Integer, Assignment> plan = new LinkedHashMap<>();
        List<Integer> free = new ArrayList<>();
        List<Companion> ordered = companions.stream().sorted(Comparator.comparingInt(Companion::id)).toList();
        for (Companion companion : ordered) {
            boolean working = companion.room() != null && !complete.contains(companion.room());
            if (working) owners.get(companion.room()).add(companion.id());
            else if (companion.carrying()) plan.put(companion.id(), Assignment.CARRY);
            else free.add(companion.id());
        }
        Set<Integer> taken = new HashSet<>();
        for (AgentOpqDefinition.Room room : SOLO_ROOMS) {
            if (!complete.contains(room) && owners.get(room).isEmpty() && !free.isEmpty()) {
                taken.add(free.get(0));
                owners.get(room).add(free.remove(0));
            }
        }
        for (AgentOpqDefinition.Room room : SOLO_ROOMS) {
            if (climbRooms.contains(room) && !complete.contains(room)) {
                seatAClimber(room, owners, free, taken, climbers(ordered), climbRooms);
            }
        }
        List<Integer> holders = owners.get(AgentOpqDefinition.Room.SEALED);
        boolean sealedOpen = !complete.contains(AgentOpqDefinition.Room.SEALED);
        if (sealedOpen && holders.size() < SEALED_HOLDERS && holders.size() + free.size() >= SEALED_HOLDERS) {
            while (holders.size() < SEALED_HOLDERS) holders.add(free.remove(0));
        }
        if (!complete.contains(AgentOpqDefinition.Room.LOUNGE) && (!sealedOpen || holders.size() >= SEALED_HOLDERS)) {
            Set<Integer> climbers = climbers(ordered);
            int high = seats.high();
            int low = seats.low();
            for (java.util.Iterator<Integer> helpers = free.iterator(); helpers.hasNext(); ) {
                int helper = helpers.next();
                if (climbers.contains(helper) && high > 0) high--;
                else if (low > 0) low--;
                else continue;
                owners.get(AgentOpqDefinition.Room.LOUNGE).add(helper);
                helpers.remove();
            }
        }
        owners.forEach((room, ids) -> ids.forEach(id -> plan.put(id, new Assignment(Duty.ROOM, room))));
        free.forEach(id -> plan.put(id, Assignment.WAIT));
        Map<Integer, Assignment> byId = new LinkedHashMap<>();
        ordered.forEach(companion -> byId.put(companion.id(), plan.get(companion.id())));
        return byId;
    }

    private static Set<Integer> climbers(List<Companion> companions) {
        Set<Integer> ids = new HashSet<>();
        companions.stream().filter(Companion::climber).forEach(companion -> ids.add(companion.id()));
        return ids;
    }

    /**
     * {@code room} wants a climber. When none of its workers climbs, a climber this pass handed a room that wants none
     * takes it and the non-climber this pass handed it takes that room; failing that, a free climber joins it.
     * {@code taken}: the companions this pass handed a room.
     */
    private static void seatAClimber(AgentOpqDefinition.Room room, Map<AgentOpqDefinition.Room, List<Integer>> owners,
                                     List<Integer> free, Set<Integer> taken, Set<Integer> climbers,
                                     Set<AgentOpqDefinition.Room> climbRooms) {
        List<Integer> seated = owners.get(room);
        if (seated.stream().anyMatch(climbers::contains)) return;
        Integer newcomer = seated.stream().filter(taken::contains).findFirst().orElse(null);
        if (newcomer != null) {
            for (AgentOpqDefinition.Room other : SOLO_ROOMS) {
                List<Integer> workers = owners.get(other);
                if (climbRooms.contains(other) || workers.size() != 1) continue;
                Integer worker = workers.get(0);
                if (taken.contains(worker) && climbers.contains(worker)) {
                    workers.set(0, newcomer);
                    seated.set(seated.indexOf(newcomer), worker);
                    return;
                }
            }
        }
        free.stream().filter(climbers::contains).findFirst().ifPresent(climber -> {
            free.remove(climber);
            seated.add(climber);
        });
    }

    /** The role a companion working {@code room} holds (the stock roles, so the room workers run unchanged). */
    static AgentOpqMemberState.Role role(AgentOpqDefinition.Room room) {
        return switch (room) {
            case WALKWAY -> AgentOpqMemberState.Role.WALKWAY_COLLECTOR;
            case STORAGE -> AgentOpqMemberState.Role.STORAGE_RUNNER;
            case LOBBY -> AgentOpqMemberState.Role.LOBBY_RUNNER;
            case SEALED -> AgentOpqMemberState.Role.SEALED_PLATFORM;
            case LOUNGE -> AgentOpqMemberState.Role.LOUNGE_RUNNER;
            case ON_WAY_UP -> AgentOpqMemberState.Role.WAY_UP_RUNNER;
        };
    }
}
