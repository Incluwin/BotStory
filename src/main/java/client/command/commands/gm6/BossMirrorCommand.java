package client.command.commands.gm6;

import client.Character;
import client.Client;
import client.command.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.expedition.boss.AgentBossAttackMirror;
import server.agents.capabilities.mobcontrol.AgentBossScope;
import server.agents.runtime.AgentRuntimeRegistry;
import server.maps.MapleMap;

import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code !bossmirror} (GM 6, docs/COMPANION_BOSSING_PLAN.md G1): the boss attack mirror (AgentBossAttackMirror) and
 * its GM trial (AgentBossScope).
 * {@code status} prints the mode in force, the trial, each open run's counters and the calibration counts per attack.
 * {@code mode off|observe|apply} sets the mirror's runtime override, which beats agent-engine.yaml MODE until a
 * restart. {@code trial on} binds your map: Agents never take control of a monster on it, and the attacks of the
 * parts your client controls there hit the companions you lead. {@code trial off} ends it. One trial runs at a time,
 * so a second GM's {@code trial on} replaces the first. Memory only; each use is logged as {@code gm bossmirror ...}.
 */
public final class BossMirrorCommand extends Command {
    static final String USAGE =
            "Syntax: !bossmirror status | !bossmirror mode <off|observe|apply> | !bossmirror trial <on|off>";
    private static final Logger log = LoggerFactory.getLogger(BossMirrorCommand.class);

    {
        setDescription("Boss attack mirror: show its counters, set its mode, or run a trial on your map.");
    }

    @Override
    public void execute(Client client, String[] params) {
        Character gm = client.getPlayer();
        if (params.length == 1 && params[0].equalsIgnoreCase("status")) {
            status(gm);
        } else if (params.length == 2 && params[0].equalsIgnoreCase("mode")) {
            mode(gm, params[1]);
        } else if (params.length == 2 && params[0].equalsIgnoreCase("trial")) {
            trial(gm, params[1]);
        } else {
            gm.yellowMessage(USAGE);
        }
    }

    private static void status(Character gm) {
        AgentBossAttackMirror mirror = AgentBossAttackMirror.get();
        List<String> lines = mirror.statusLines();
        gm.yellowMessage(lines.get(0));   // the mirror's first line is its mode
        gm.yellowMessage(trialLine());
        lines.subList(1, lines.size()).forEach(gm::yellowMessage);
        log.info("gm bossmirror status: gm={} map={} mode={} trial={}", gm.getName(), gm.getMapId(), mirror.mode(),
                trialName(AgentBossScope.trialMap(), AgentBossScope.trialGm()));
    }

    private static void mode(Character gm, String value) {
        AgentBossAttackMirror.Mode chosen = null;
        for (AgentBossAttackMirror.Mode mode : AgentBossAttackMirror.Mode.values()) {
            if (mode.name().equalsIgnoreCase(value)) {
                chosen = mode;
            }
        }
        if (chosen == null) {
            gm.yellowMessage(USAGE);
            return;
        }
        AgentBossAttackMirror mirror = AgentBossAttackMirror.get();
        AgentBossAttackMirror.Mode before = mirror.mode();
        mirror.overrideMode(chosen);
        gm.yellowMessage("Boss mirror mode=" + chosen + " (was " + before
                + "). A restart goes back to agent-engine.yaml.");
        log.info("gm bossmirror mode: gm={} map={} mode={} was={}", gm.getName(), gm.getMapId(), chosen, before);
    }

    private static void trial(Character gm, String value) {
        MapleMap wasMap = AgentBossScope.trialMap();
        int wasGm = AgentBossScope.trialGm();
        String was = trialName(wasMap, wasGm);
        if (value.equalsIgnoreCase("on")) {
            MapleMap map = gm.getMap();
            AgentBossScope.startTrial(gm.getId(), map);
            String companions = companionsThere(gm.getId(), map);
            gm.yellowMessage("Boss mirror trial on: map " + map.getId() + ", mode "
                    + AgentBossAttackMirror.get().mode() + ". Agents never take control of a monster here, and the"
                    + " attacks of the parts you control hit your companions here: " + companions + "."
                    + (wasMap == null || (wasMap == map && wasGm == gm.getId()) ? ""
                    : " It replaces the trial on map " + wasMap.getId() + "."));
            gm.yellowMessage("Run !bossmirror trial off when you are done.");
            log.info("gm bossmirror trial: gm={} map={} trial=on companions={} was={}", gm.getName(), gm.getMapId(),
                    companions, was);
        } else if (value.equalsIgnoreCase("off")) {
            AgentBossScope.endTrial();
            gm.yellowMessage(wasMap == null ? "Boss mirror trial was not on."
                    : "Boss mirror trial off (it was on map " + wasMap.getId() + ").");
            log.info("gm bossmirror trial: gm={} map={} trial=off was={}", gm.getName(), gm.getMapId(), was);
        } else {
            gm.yellowMessage(USAGE);
        }
    }

    /** "Boss mirror trial off." or the trial's map, its GM and the companions the GM leads there. */
    private static String trialLine() {
        MapleMap map = AgentBossScope.trialMap();
        int gmId = AgentBossScope.trialGm();
        if (map == null || gmId <= 0) {
            return "Boss mirror trial off.";
        }
        Character trialGm = map.getCharacterById(gmId);
        return "Boss mirror trial on: map " + map.getId() + ", GM "
                + (trialGm == null ? "id " + gmId + " (not on the map)" : trialGm.getName())
                + ", companions there: " + companionsThere(gmId, map) + ".";
    }

    /** The names of the Agents the GM leads that stand on the map (the mirror's trial targets), else "none". */
    private static String companionsThere(int gmId, MapleMap map) {
        String names = AgentRuntimeRegistry.activeAgentCharactersForLeader(gmId).stream()
                .filter(agent -> agent.getMap() == map)
                .map(Character::getName)
                .collect(Collectors.joining(", "));
        return names.isEmpty() ? "none" : names;
    }

    private static String trialName(MapleMap map, int gmId) {
        return map == null || gmId <= 0 ? "off" : map.getId() + "/" + gmId;
    }
}
