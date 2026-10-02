package server.knowledge;

/**
 * One authored ship/taxi/continent hop from travel.yaml: board at {@code fromMap} (where {@code npc} stands)
 * and arrive at {@code toMap}.
 *
 * @param kind "ship", "taxi", ... as authored
 * @param cost meso fare, 0 when free or unknown
 */
public record TravelLeg(int fromMap, int toMap, int npc, String kind, int cost) {
}
