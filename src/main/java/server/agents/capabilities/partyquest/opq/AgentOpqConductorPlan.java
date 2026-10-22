package server.agents.capabilities.partyquest.opq;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
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
 * for its three platforms, then help in the lounge once the sealed room is staffed or done; the rest wait in the
 * centre. With five companions the first pass gives each of them one of those five rooms.
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

    /** One companion as the plan reads it: the room it works now (null for none) and whether it carries a piece. */
    record Companion(int id, AgentOpqDefinition.Room room, boolean carrying) { }

    /** A companion's assignment: its duty and, for ROOM, the room. */
    record Assignment(Duty duty, AgentOpqDefinition.Room room) {
        static final Assignment CARRY = new Assignment(Duty.CARRY, null);
        static final Assignment WAIT = new Assignment(Duty.WAIT, null);
    }

    private AgentOpqConductorPlan() {
    }

    /** Every companion's assignment, by id in id order. */
    static Map<Integer, Assignment> plan(List<Companion> companions, Set<AgentOpqDefinition.Room> complete) {
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
        for (AgentOpqDefinition.Room room : SOLO_ROOMS) {
            if (!complete.contains(room) && owners.get(room).isEmpty() && !free.isEmpty()) {
                owners.get(room).add(free.remove(0));
            }
        }
        List<Integer> holders = owners.get(AgentOpqDefinition.Room.SEALED);
        boolean sealedOpen = !complete.contains(AgentOpqDefinition.Room.SEALED);
        if (sealedOpen && holders.size() < SEALED_HOLDERS && holders.size() + free.size() >= SEALED_HOLDERS) {
            while (holders.size() < SEALED_HOLDERS) holders.add(free.remove(0));
        }
        if (!complete.contains(AgentOpqDefinition.Room.LOUNGE) && (!sealedOpen || holders.size() >= SEALED_HOLDERS)) {
            owners.get(AgentOpqDefinition.Room.LOUNGE).addAll(free);
            free.clear();
        }
        owners.forEach((room, ids) -> ids.forEach(id -> plan.put(id, new Assignment(Duty.ROOM, room))));
        free.forEach(id -> plan.put(id, Assignment.WAIT));
        Map<Integer, Assignment> byId = new LinkedHashMap<>();
        ordered.forEach(companion -> byId.put(companion.id(), plan.get(companion.id())));
        return byId;
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
