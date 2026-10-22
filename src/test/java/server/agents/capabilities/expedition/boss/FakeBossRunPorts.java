package server.agents.capabilities.expedition.boss;

import client.Character;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.AgentBossFight;
import server.agents.runtime.simulation.AgentSimulationState;
import server.expeditions.Expedition;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Boss-run ports for tests: Agents are Mockito characters placed by id, NPC and portal positions are fixed, and every
 * port call is written to {@link #calls} in order. {@link Host} writes the host callbacks to the same list, so a test
 * can check that a seat comes before the Mu Young conversation.
 */
final class FakeBossRunPorts implements AgentBossRunPorts {
    /** What a fake Mu Young conversation does; it may throw. */
    @FunctionalInterface
    interface RunNpc {
        boolean run(Character agent, int npcId, int... selections);
    }

    final List<String> calls = new ArrayList<>();
    final Map<Integer, Character> agents = new HashMap<>();
    final Map<Integer, Integer> mapOf = new HashMap<>();
    final Map<Integer, Point> positionOf = new HashMap<>();
    final Map<Integer, Integer> levelOf = new HashMap<>();
    final Map<Integer, Point> npcs = new HashMap<>();
    final Map<Integer, Point> portals = new HashMap<>();
    final Set<Integer> restocking = new HashSet<>();
    final Set<Integer> restockPending = new HashSet<>();
    final Map<Integer, EventInstanceManager> eventOf = new HashMap<>();
    final Set<Integer> dead = new HashSet<>();
    final Map<Integer, Character> humans = new HashMap<>();
    final Set<Integer> away = new HashSet<>();
    final Set<Integer> changingMaps = new HashSet<>();
    /** For an Agent listed here clearBoundary, moveOut and follow throw once the call is logged. */
    final Set<Integer> broken = new HashSet<>();
    final List<Runnable> scheduled = new ArrayList<>();
    final List<Long> scheduledDelays = new ArrayList<>();
    /** What {@link #supplies} answers per Agent; nothing by default. */
    final Map<Integer, String> supplies = new HashMap<>();
    /** Each placed Agent's simulation state, made on first ask. */
    final Map<Integer, AgentSimulationState> simulation = new HashMap<>();
    boolean stillTheChannels = true;
    boolean adoptionAnswer = true;
    RunNpc runNpc = (agent, npcId, selections) -> true;
    /** What a fake leave from the entry NPC's list does once the call is logged; nothing by default. */
    BiConsumer<Expedition, Character> leaveList = (expedition, agent) -> { };

    /**
     * A loaded Agent standing at (x, y) on {@code mapId} with {@code level}; it is logged in, in the event
     * {@link #eventOf} names (none by default) and alive unless {@link #dead} lists it.
     */
    Character place(int agentId, int mapId, int x, int y, int level) {
        mapOf.put(agentId, mapId);
        positionOf.put(agentId, new Point(x, y));
        levelOf.put(agentId, level);
        return agents.computeIfAbsent(agentId, id -> {
            Character agent = mock(Character.class);
            when(agent.getId()).thenReturn(id);
            when(agent.getName()).thenReturn("Agent" + id);
            when(agent.getMapId()).thenAnswer(invocation -> mapOf.getOrDefault(id, 0));
            when(agent.getPosition()).thenAnswer(invocation -> new Point(positionOf.get(id)));
            when(agent.getLevel()).thenAnswer(invocation -> levelOf.getOrDefault(id, 1));
            when(agent.getEventInstance()).thenAnswer(invocation -> eventOf.get(id));
            when(agent.isAlive()).thenAnswer(invocation -> !dead.contains(id));
            // A loaded Agent is logged in (Character.newClient, setEnteredChannelWorld): only headless() keeps it
            // out of the human count.
            when(agent.isLoggedinWorld()).thenReturn(true);
            return agent;
        });
    }

    /**
     * A human at a keyboard: alive unless {@link #dead} lists it, logged in unless {@link #away} lists it, and changing
     * maps while {@link #changingMaps} lists it. Every character {@link #place} made is headless.
     */
    Character human(int humanId) {
        return humans.computeIfAbsent(humanId, id -> {
            Character human = mock(Character.class);
            when(human.getId()).thenReturn(id);
            when(human.getName()).thenReturn("Human" + id);
            when(human.isAlive()).thenAnswer(invocation -> !dead.contains(id));
            when(human.isLoggedinWorld()).thenAnswer(invocation -> !away.contains(id));
            when(human.isChangingMaps()).thenAnswer(invocation -> changingMaps.contains(id));
            return human;
        });
    }

    long count(String prefix) {
        return calls.stream().filter(call -> call.startsWith(prefix)).count();
    }

    @Override
    public Character agent(int agentId) {
        return agents.get(agentId);
    }

    @Override
    public AgentSimulationState simulation(int agentId) {
        return agents.containsKey(agentId) ? simulation.computeIfAbsent(agentId, id -> new AgentSimulationState()) : null;
    }

    @Override
    public boolean headless(Character character) {
        return character != null && !humans.containsKey(character.getId());
    }

    /** Only a {@link #human} is online, while {@link #away} does not list it. */
    @Override
    public boolean online(int characterId) {
        Character human = humans.get(characterId);
        return human != null && human.isLoggedinWorld();
    }

    @Override
    public Point npcPosition(Character agent, int npcId) {
        Point npc = npcs.get(npcId);
        return npc == null ? null : new Point(npc);
    }

    @Override
    public Point portalPosition(Character agent, int portalId) {
        Point portal = portals.get(portalId);
        return portal == null ? null : new Point(portal);
    }

    @Override
    public boolean runNpc(Character agent, int npcId, int... selections) {
        calls.add("runNpc " + agent.getId() + " " + npcId + " " + Arrays.stream(selections)
                .mapToObj(Integer::toString).collect(Collectors.joining(",")));
        return runNpc.run(agent, npcId, selections);
    }

    @Override
    public void leaveList(Expedition expedition, Character agent) {
        calls.add("leaveList " + agent.getId());
        leaveList.accept(expedition, agent);
    }

    @Override
    public void navigate(int agentId, Point destination, boolean precise) {
        calls.add("navigate " + agentId + " " + destination.x + "," + destination.y);
    }

    @Override
    public void stop(int agentId) {
        calls.add("stop " + agentId);
    }

    @Override
    public void clearBoundary(int agentId) {
        calls.add("clearBoundary " + agentId);
        failIfBroken(agentId);
    }

    @Override
    public boolean enterPortal(Character agent, int portalId) {
        calls.add("enterPortal " + agent.getId() + " " + portalId);
        return true;
    }

    @Override
    public void moveOut(Character agent, int mapId) {
        calls.add("moveOut " + agent.getId() + " " + mapId);
        failIfBroken(agent.getId());
    }

    @Override
    public void follow(int agentId) {
        calls.add("follow " + agentId);
        failIfBroken(agentId);
    }

    private void failIfBroken(int agentId) {
        if (broken.contains(agentId)) {
            throw new IllegalStateException("broken port for " + agentId);
        }
    }

    @Override
    public boolean prepareAdoption(int agentId, String reason, long nowMs) {
        calls.add("prepareAdoption " + agentId);
        return adoptionAnswer;
    }

    @Override
    public boolean restocking(int agentId, long nowMs) {
        return restocking.contains(agentId);
    }

    @Override
    public boolean restockPending(int agentId, long nowMs) {
        return restockPending.contains(agentId);
    }

    @Override
    public boolean stillTheChannels(Expedition expedition) {
        return stillTheChannels;
    }

    @Override
    public void prepareScripts(int... npcIds) {
        calls.add("prepareScripts " + Arrays.stream(npcIds).mapToObj(Integer::toString)
                .collect(Collectors.joining(",")));
    }

    @Override
    public void schedule(Runnable action, long delayMs) {
        scheduled.add(action);
        scheduledDelays.add(delayMs);
    }

    @Override
    public String supplies(Character agent) {
        return supplies.getOrDefault(agent.getId(), "");
    }

    /** A host that writes each callback to the shared call list and keeps every ended run. */
    static final class Host implements AgentBossRunHost.Rule {
        final List<String> calls;
        final Set<Integer> busy = new HashSet<>();
        /** The members a granted seat tied to the run; a test removes one to let go of its tie. */
        final Set<Integer> held = new HashSet<>();
        final List<AgentBossRunHost.RunEnded> ended = new ArrayList<>();
        /** The members the run reported as gone into the fight (entered), in order; apart from the call list. */
        final List<Integer> entered = new ArrayList<>();
        /**
         * The members the run reported as down outside when their owner left before the clear (ownerLeftWhileDown), in
         * order; apart from the call list.
         */
        final List<Integer> ownerLeftWhileDown = new ArrayList<>();
        /**
         * The seats of the list a companion leaves free for players (roomFor, logged as "room id listed/max"); below 0
         * (the default) the host does not answer it, as a rule that leaves every seat to the stock list.
         */
        int reserve = -1;

        Host(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public boolean roomFor(AgentBossRunHost.Seat seat, int listed, int maxSize) {
            if (reserve < 0) {
                return true;
            }
            calls.add("room " + seat.agentCharacterId() + " " + listed + "/" + maxSize);
            return listed < maxSize - reserve;
        }

        @Override
        public boolean seat(AgentBossRunHost.Seat seat) {
            calls.add("seat " + seat.agentCharacterId());
            if (busy.contains(seat.agentCharacterId())) {
                return false;
            }
            held.add(seat.agentCharacterId());
            return true;
        }

        @Override
        public void registered(AgentBossRunHost.Seat seat) {
            calls.add("registered " + seat.agentCharacterId());
        }

        @Override
        public void refused(AgentBossRunHost.Seat seat, AgentBossRunHost.Refusal refusal) {
            calls.add("refused " + seat.agentCharacterId() + " " + refusal);
        }

        @Override
        public void memberOut(AgentBossRunHost.Seat seat, AgentBossRunHost.Out out) {
            calls.add("out " + seat.agentCharacterId() + " " + out);
        }

        @Override
        public void runEnded(AgentBossRunHost.RunEnded run) {
            calls.add("ended " + run.reason());
            ended.add(run);
        }

        @Override
        public void reentering(AgentBossRunHost.Seat seat) {
            calls.add("reentering " + seat.agentCharacterId());
        }

        @Override
        public void entered(AgentBossRunHost.Seat seat) {
            entered.add(seat.agentCharacterId());
        }

        @Override
        public void ownerLeftWhileDown(AgentBossRunHost.Seat seat) {
            ownerLeftWhileDown.add(seat.agentCharacterId());
        }
    }

    /** A fight that only records what the session asked of it; a set {@link #tickFailure} is thrown from tick. */
    static final class Fight implements AgentBossFight {
        final List<String> calls = new ArrayList<>();
        /** The maps it plays on after the clear (none: it ends at its clear, as every boss but CWKPQ). */
        final Set<Integer> afterClearMaps = new HashSet<>();
        RuntimeException tickFailure;
        /** What it measured (null: none, as every boss but CWKPQ); a set {@link #paceFailure} is thrown instead. */
        server.agents.capabilities.partyquest.pace.AgentPqPace measured;
        RuntimeException paceFailure;

        @Override
        public boolean playsAfterClear(int mapId) {
            return afterClearMaps.contains(mapId);
        }

        @Override
        public server.agents.capabilities.partyquest.pace.AgentPqPace pace(long sessionId, long startedAtMs,
                                                                          long endedAtMs) {
            calls.add("pace " + startedAtMs + " " + endedAtMs);
            if (paceFailure != null) {
                throw paceFailure;
            }
            return measured;
        }

        @Override
        public void tickAfterClear(List<Character> members, EventInstanceManager event, long nowMs) {
            calls.add("afterClear " + members.stream().map(Character::getId).toList());
        }

        @Override
        public void adopt(int characterId, int ordinal) {
            calls.add("adopt " + characterId + " " + ordinal);
        }

        @Override
        public void tick(List<Character> fighters, EventInstanceManager event, long nowMs) {
            calls.add("tick " + fighters.stream().map(Character::getId).toList());
            if (tickFailure != null) {
                throw tickFailure;
            }
        }

        @Override
        public void end(EventInstanceManager event) {
            calls.add("end");
        }
    }
}
