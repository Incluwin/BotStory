package server.agents.capabilities.partyquest.kpq;

import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Which stage-5 mob type each Agent fights first. Live (run D) every companion went for whatever was nearest, and
 * Rurik swung at King Slime 95 times without a hit; a level-29 warrior at accuracy 22 hits Jr. Necki 1% of the time
 * and Curse Eye 26%. So each Agent takes the live type it is expected to hurt most per line (hit chance times damage
 * after defense), and a type nobody chose takes the Agent best at it whose own type keeps someone. King Slime is not
 * offered: the caller leaves it for when the others are down.
 */
final class AgentKpqStageFiveTargetPolicy {
    private AgentKpqStageFiveTargetPolicy() {
    }

    /**
     * @param expectedDamage per Agent id, the expected damage of one line of its attack on each live mob type
     * @return the mob type for each Agent that has an estimate
     */
    static Map<Integer, Integer> assign(Map<Integer, Map<Integer, Double>> expectedDamage) {
        Map<Integer, Integer> choice = new TreeMap<>();
        TreeSet<Integer> types = new TreeSet<>();
        new TreeMap<>(expectedDamage).forEach((agentId, damage) -> {
            if (damage.isEmpty()) return;
            types.addAll(damage.keySet());
            choice.put(agentId, damage.keySet().stream()
                    .max(Comparator.comparingDouble((Integer mobId) -> damage.get(mobId))
                            .thenComparing(Comparator.reverseOrder()))
                    .orElseThrow());
        });
        for (int mobId : types) {
            if (choice.containsValue(mobId)) continue;
            choice.keySet().stream()
                    .filter(agentId -> expectedDamage.get(agentId).containsKey(mobId)
                            && Collections.frequency(choice.values(), choice.get(agentId)) > 1)
                    .max(Comparator.comparingDouble((Integer agentId) -> expectedDamage.get(agentId).get(mobId))
                            .thenComparingDouble(agentId -> expectedDamage.get(agentId).get(mobId)
                                    - expectedDamage.get(agentId).get(choice.get(agentId)))
                            .thenComparing(Comparator.reverseOrder()))
                    .ifPresent(agentId -> choice.put(agentId, mobId));
        }
        return choice;
    }
}
