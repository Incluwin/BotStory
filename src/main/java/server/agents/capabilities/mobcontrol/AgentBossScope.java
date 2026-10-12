package server.agents.capabilities.mobcontrol;

import client.BotClient;
import client.Character;
import net.server.services.task.channel.ServerMobAutonomyService;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;
import server.life.Monster;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The control-steal block for boss parts that a human client moves (P8 G2), such as Zakum's arms. A guarded part never
 * goes to Agent physics on an Agent's hit (ServerMobAutonomyService.blocksAgentPhysicsInstance), so its human
 * controller keeps it and gets no STOP, and an Agent's far hit on it raises no DISTANCE_HACK and no position resend
 * (AbstractDealDamageHandler.checkAttackDistance).
 *
 * <p>{@link #guards} covers a part while ENABLED, when the server does not own it, no Agent controls it, and one of
 * four cases holds: a boss in an event of a listed expedition type (EXPEDITION_TYPES), a listed id on a map with no
 * event (BOSS_IDS), a listed id in an event with an open companion run (P12: a party-instance boss such as Papulatus,
 * or an add inside an expedition), or a part on the GM trial map. Memory only, any thread.</p>
 */
public final class AgentBossScope {
    private static volatile Trial trial;
    private static volatile Types types = new Types("", Set.of());
    private static volatile Ids ids = new Ids("", new int[0]);

    private AgentBossScope() {
    }

    public static boolean guards(Monster monster) {
        if (monster == null || !enabled() || !covers(monster)
                || ServerMobAutonomyService.isActiveInstance(monster)) {
            return false;
        }
        Character controller = monster.getController();
        return controller == null || !(controller.getClient() instanceof BotClient);
    }

    /**
     * True while a human really drives the part: its controller is a logged-in client, alive, not hidden, on the
     * part's map and in the part's event. A hidden or dead controller counts as no human.
     */
    public static boolean engaged(Monster monster) {
        MapleMap map = monster == null ? null : monster.getMap();
        Character controller = map == null ? null : monster.getController();
        return controller != null && !(controller.getClient() instanceof BotClient)
                && controller.isLoggedinWorld() && controller.isAlive() && !controller.isHidden()
                && controller.getMap() == map
                && controller.getEventInstance() == map.getEventInstance();
    }

    /** Guards every part on {@code map} until {@link #endTrial}: one GM's bounded field check. */
    public static void startTrial(int gmId, MapleMap map) {
        if (gmId <= 0 || map == null) {
            throw new IllegalArgumentException("a boss scope trial needs a GM and a map");
        }
        trial = new Trial(gmId, map);
    }

    public static void endTrial() {
        trial = null;
    }

    /** The trial GM's character id, or 0 while no trial runs. */
    public static int trialGm() {
        Trial current = trial;
        return current == null ? 0 : current.gmId();
    }

    /** The trial map, or null while no trial runs. */
    public static MapleMap trialMap() {
        Trial current = trial;
        return current == null ? null : current.map();
    }

    private static boolean covers(Monster monster) {
        MapleMap map = monster.getMap();
        if (map == null) {
            return false;
        }
        EventInstanceManager event = map.getEventInstance();
        if (event == null) {
            if (listedId(monster.getId())) {
                return true;
            }
        } else {
            Expedition expedition = monster.isBoss() ? event.getExpedition() : null;
            if (expedition != null && listedTypes().contains(expedition.getType())) {
                return true;
            }
            // P12 case 4: a listed id inside an event with an open companion run (a party-instance boss, an add)
            if (listedId(monster.getId()) && AgentBossRunRegistry.forEvent(event) != null) {
                return true;
            }
        }
        Trial current = trial;
        return current != null && current.map() == map;
    }

    private static boolean enabled() {
        return config.AgentTuning.booleanValue("server.agents.capabilities.mobcontrol.AgentBossScope.ENABLED");
    }

    private static Set<ExpeditionType> listedTypes() {
        String raw = config.AgentTuning.stringValue(
                "server.agents.capabilities.mobcontrol.AgentBossScope.EXPEDITION_TYPES");
        Types current = types;
        if (!current.raw().equals(raw)) {
            Set<ExpeditionType> parsed = EnumSet.noneOf(ExpeditionType.class);
            for (String name : raw.split(",")) {
                parsed.add(ExpeditionType.valueOf(name.trim()));
            }
            current = new Types(raw, Set.copyOf(parsed));
            types = current;
        }
        return current.parsed();
    }

    private static boolean listedId(int mobId) {
        String raw = config.AgentTuning.stringValue("server.agents.capabilities.mobcontrol.AgentBossScope.BOSS_IDS");
        Ids current = ids;
        if (!current.raw().equals(raw)) {
            current = new Ids(raw, parseRanges(raw));
            ids = current;
        }
        int[] ranges = current.ranges();
        for (int index = 0; index < ranges.length; index += 2) {
            if (mobId >= ranges[index] && mobId <= ranges[index + 1]) {
                return true;
            }
        }
        return false;
    }

    /** "none", or ids and id ranges such as "8800003-8800010,8810002", as {first, last} pairs. */
    private static int[] parseRanges(String raw) {
        if ("none".equals(raw)) {
            return new int[0];
        }
        List<Integer> bounds = new ArrayList<>();
        for (String entry : raw.split(",")) {
            int dash = entry.indexOf('-');
            int first = Integer.parseInt(entry.substring(0, dash < 0 ? entry.length() : dash).trim());
            int last = dash < 0 ? first : Integer.parseInt(entry.substring(dash + 1).trim());
            bounds.add(first);
            bounds.add(last);
        }
        return bounds.stream().mapToInt(Integer::intValue).toArray();
    }

    private record Trial(int gmId, MapleMap map) {
    }

    private record Types(String raw, Set<ExpeditionType> parsed) {
    }

    private record Ids(String raw, int[] ranges) {
    }
}
