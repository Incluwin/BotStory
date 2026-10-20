package server.agents.capabilities.expedition.cwkpq;

import client.Character;
import scripting.event.EventInstanceManager;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 6, the Grandmaster Council Hall (CWKPQ plan B7, phase C7). Only the event leader, the human who made the
 * expedition at Jack, can talk to the Battle Statue (9201115: the guards at the first talk, the four masters at the
 * second once the hall is empty, the clear at the third). So the companions fight what is in the hall and, whenever it
 * is empty, one of them calls him to the statue, once per step.
 * <p>
 * A magician takes the master its element hurts most (Mob.wz elemAttr): a fire/poison mage Rellik (fire-weak), an
 * ice/lightning mage Hsalf (ice-weak; his physical defence of 8000 is wasted on physical attackers), a cleric line
 * Red Nirg (the only master not holy-immune), each then the revive its element hurts. Margana resists fire, ice and
 * lightning and is immune to holy, so no mage takes her first. Everyone else, and a mage with none of its masters up,
 * fights the boss track's priority fight under boss-rules.json cwkpq (one master at a time, Red Nirg last: his revive
 * adds two more).
 */
final class AgentCwkpqCouncilPlay {
    static final int MARGANA = 9_400_590;
    static final int RED_NIRG = 9_400_591;
    static final int RELLIK = 9_400_592;
    static final int HSALF = 9_400_593;
    /** Red Nirg's revives: the ice-weak and the fire-weak one, both holy-weak. */
    static final int SCARLET_PHOENIX = 9_400_596;
    static final int AZURE_OCELOT = 9_400_597;
    static final String READY_LINE = "talk to the statue when you're ready";
    static final String CLEAR_LINE = "hall's clear, talk to the statue";

    private AgentCwkpqCouncilPlay() {
    }

    static void council(AgentCwkpqPorts ports, List<Character> members, EventInstanceManager event, AgentCwkpqRun run,
                        long nowMs) {
        int step = event.getIntProperty("glpq6");
        List<Character> bySlot = AgentCwkpqStagePlays.bySlot(members, run);
        MapleMap map = bySlot.getFirst().getMap();
        if (step >= 3 || map == null) {
            return;
        }
        if (map.countMonsters() == 0) {
            if (run.once("statue-" + step)) {
                ports.sayInParty(bySlot.getFirst(), step == 0 ? READY_LINE : CLEAR_LINE);
            }
            return;
        }
        Map<Integer, Integer> live = ports.gateway().liveMonsterCounts(bySlot.getFirst());
        List<Character> rest = new ArrayList<>();
        for (Character member : bySlot) {
            Integer first = magePreference(member.getJob().getId()).stream().filter(live::containsKey).findFirst()
                    .orElse(null);
            AgentRuntimeEntry entry = ports.entry(member.getId());
            if (first == null || entry == null) {
                rest.add(member);
                continue;
            }
            Set<Integer> fallback = new HashSet<>(live.keySet());
            fallback.remove(first);
            ports.gateway().grind(entry, Set.of(first), fallback);
        }
        if (!rest.isEmpty()) {
            run.council().tick(rest, event, nowMs);
        }
    }

    /** The masters (then revives) a magician's element hurts most, in order; none for any other job. */
    static List<Integer> magePreference(int jobId) {
        return switch (jobId / 10) {
            case 21 -> List.of(RELLIK, AZURE_OCELOT);
            case 22 -> List.of(HSALF, SCARLET_PHOENIX);
            case 23 -> List.of(RED_NIRG, SCARLET_PHOENIX, AZURE_OCELOT);
            default -> List.of();
        };
    }
}
