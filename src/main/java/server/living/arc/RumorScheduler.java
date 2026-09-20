package server.living.arc;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.Content.Rumor;
import server.living.config.Content.RumorLine;
import server.living.resident.ResidentRuntime;
import server.living.resident.ResidentSpots;
import server.maps.MapleMap;

import java.awt.Point;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Performs scripted Resident-to-Resident dialogues in public when their gates and schedule
 * allow and a player is there to hear them; listeners in range get the rumor's flag.
 */
public final class RumorScheduler {
    private static final Logger log = LoggerFactory.getLogger(RumorScheduler.class);
    private static final int LISTEN_RANGE_PX = 600;
    private static final long WALK_UP_MS = 6_000L;

    private final LivingWorld world;
    private final Set<String> performing = ConcurrentHashMap.newKeySet();

    public RumorScheduler(LivingWorld world) {
        this.world = world;
    }

    public boolean performing(String personaId) {
        return performing.contains(personaId);
    }

    /** Times a rumor has been performed, from world flags {@code rumor.<id>.n<k>}. */
    public int performances(String rumorId) {
        int count = 0;
        String prefix = "rumor." + rumorId + ".n";
        for (String flag : world.store().worldFlags()) {
            if (flag.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    public void tick() {
        int hour = LocalTime.now().getHour();
        String today = LocalDate.now().toString();
        Set<String> flags = world.store().worldFlags();
        for (Rumor rumor : world.content().rumors().values()) {
            try {
                if (due(rumor, hour, today, flags)) {
                    perform(rumor, false);
                }
            } catch (RuntimeException failure) {
                log.warn("rumor {} failed", rumor.id(), failure);
            }
        }
        world.arcs().evaluateGates();
    }

    private boolean due(Rumor rumor, int hour, String today, Set<String> flags) {
        if (!rumor.serverHours().contains(hour)) {
            return false;
        }
        String lastDay = null;
        for (String flag : flags) {
            if (flag.startsWith("rumor." + rumor.id() + ".day.")) {
                String day = flag.substring(("rumor." + rumor.id() + ".day.").length());
                if (lastDay == null || day.compareTo(lastDay) > 0) {
                    lastDay = day;
                }
            }
        }
        if (lastDay != null) {
            LocalDate last = LocalDate.parse(lastDay);
            if (!LocalDate.parse(today).isAfter(last.plusDays(Math.max(1, rumor.everyDays()) - 1))) {
                return false;
            }
        }
        if (rumor.requiresArc() != null
                && !world.arcs().chapterReached(rumor.requiresArc().arc(), rumor.requiresArc().chapterAtLeast())) {
            return false;
        }
        for (String participant : rumor.participants()) {
            Persona persona = world.content().persona(participant);
            if (persona == null || !world.arcs().rumorUnlocked(persona, rumor.id())) {
                return false;
            }
            if (world.residents().agentOf(participant) == null) {
                return false; // not materialized (asleep or recruited)
            }
        }
        return true;
    }

    /** Runs the rumor now (GM {@code !world rumor play} passes force=true to skip schedule and audience checks). */
    public String perform(Rumor rumor, boolean force) {
        List<Persona> cast = rumor.participants().stream().map(world.content()::persona).toList();
        if (cast.stream().anyMatch(persona -> persona == null || world.residents().agentOf(persona.id()) == null)) {
            return "not everyone is in town";
        }
        Character first = world.residents().agentOf(cast.get(0).id());
        MapleMap map = first.getMap();
        if (map == null || ResidentRuntime.humansIn(map) == 0 && !force) {
            return "no audience";
        }
        for (Persona persona : cast) {
            Character agent = world.residents().agentOf(persona.id());
            if (agent == null || agent.getMap() == null || agent.getMap().getId() != map.getId()) {
                return persona.name() + " is not in the same map";
            }
        }
        Point spot = ResidentSpots.resolve(rumor.spot(), map, 0);
        if (spot == null) {
            spot = first.getPosition();
        }
        for (Persona persona : cast) {
            performing.add(persona.id());
        }
        int index = 0;
        for (Persona persona : cast) {
            var entry = world.residents().entryOf(persona.id());
            if (entry != null) {
                AgentMovementCommandRuntime.moveTo(entry, new Point(spot.x + 40 * index - 20 * (cast.size() - 1), spot.y), true);
            }
            index++;
        }
        long delay = WALK_UP_MS;
        for (RumorLine line : rumor.lines()) {
            Persona speaker = world.content().persona(line.by());
            TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY,
                    () -> world.residents().say(speaker, line.text()), delay);
            delay += world.config().RUMOR_LINE_PACING_MS;
        }
        Point listenAt = spot;
        TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY, () -> finish(rumor, map, listenAt, cast), delay);
        return "performing " + rumor.id() + " with " + cast.size() + " participants";
    }

    private void finish(Rumor rumor, MapleMap map, Point spot, List<Persona> cast) {
        try {
            int listeners = 0;
            for (Character chr : map.getAllPlayers()) {
                if (AgentPresence.isAgent(chr) || chr.getPosition().distance(spot) > LISTEN_RANGE_PX) {
                    continue;
                }
                listeners++;
                if (rumor.setsFlagOnListeners() != null) {
                    world.bonds().setPlayerFlag(chr.getId(), rumor.setsFlagOnListeners());
                    for (Persona persona : cast) {
                        world.bonds().setFlag(chr.getId(), persona.id(), rumor.setsFlagOnListeners());
                    }
                }
                world.quests().onRumorHeard(chr, rumor.id());
            }
            String today = LocalDate.now().toString();
            world.store().setWorldFlag("rumor." + rumor.id() + ".day." + today, 0);
            world.store().setWorldFlag("rumor." + rumor.id() + ".n" + (performances(rumor.id()) + 1), 0);
            world.store().event(0, cast.get(0).id(), "rumor.performed", rumor.id() + " listeners=" + listeners);
            world.arcs().evaluateGates();
        } finally {
            for (Persona persona : cast) {
                performing.remove(persona.id());
            }
        }
    }
}
