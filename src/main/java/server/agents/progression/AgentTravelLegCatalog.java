package server.agents.progression;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ships, trains, Hak's crane, the dolphin, the subway and the plane: NPC rides the walking graphs cannot see
 * (npc-travel-legs.json; every leg's dialogue, fare and ticket checked against its NPC script by
 * AgentTravelLegScriptTest). The router takes one only for a caller that lets it ride (AgentVictoriaRouteRuntime.RideBudget)
 * and only when no portal path reaches the destination.
 */
public final class AgentTravelLegCatalog {
    private static final String RESOURCE = "/agents/catalogs/npc-travel-legs.json";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final List<Leg> LEGS = load();
    private static final Map<Integer, List<Leg>> FROM = byOrigin(LEGS);
    private static final Set<Integer> RIDE_MAPS = rideMaps(LEGS);
    private static final Set<Integer> STATIONS = stations(LEGS);

    /** A ticket bought from {@code sellerNpcId} on {@code sellerMapId} with these dialogue selections. */
    public record Ticket(int itemId, int sellerMapId, int sellerNpcId, List<Integer> selections, int price) {
        public Ticket {
            if (itemId <= 0 || sellerMapId <= 0 || sellerNpcId <= 0 || price < 0) {
                throw new IllegalArgumentException("a ticket needs an item, a seller and a price");
            }
            selections = selections == null ? List.of() : List.copyOf(selections);
        }
    }

    /**
     * Boards at {@code fromMapId} by talking to {@code npcId} with {@code selections} (or, with {@code portal} and NPC 0,
     * by walking into that portal: Kerning Square Station's way back to the subway), paying {@code fare} there (and
     * holding {@code ticket}, when there is one); lands on {@code toMapId} at once or after waiting on one of
     * {@code rideMapIds}. The NPC lets nobody below {@code minLevel} on (Jeff at Ice Valley II, 50). With
     * {@code savedLocation} the script lands the rider where that saved location points (Spinel's and Audrey's
     * WORLDTOUR, Pison's FLORINA): {@code toMapId} is where it lands when nothing is saved, and every other landing
     * counts as the ride done.
     */
    public record Leg(String id, int fromMapId, int npcId, List<Integer> selections, int toMapId, int fare,
                      List<Integer> rideMapIds, Ticket ticket, int minLevel, String savedLocation, String portal) {
        public Leg {
            boolean byPortal = portal != null && !portal.isBlank();
            if (id == null || id.isBlank() || fromMapId <= 0 || (byPortal ? npcId != 0 : npcId <= 0) || toMapId <= 0
                    || fromMapId == toMapId || fare < 0 || minLevel < 0) {
                throw new IllegalArgumentException("a travel leg needs an id, two maps, an NPC or a portal and a fare");
            }
            if (byPortal && (ticket != null || selections != null && !selections.isEmpty())) {
                throw new IllegalArgumentException("a portal leg has no ticket and no dialogue: " + id);
            }
            selections = selections == null ? List.of() : List.copyOf(selections);
            rideMapIds = rideMapIds == null ? List.of() : List.copyOf(rideMapIds);
            savedLocation = savedLocation == null || savedLocation.isBlank() ? null : savedLocation;
            portal = byPortal ? portal : null;
        }

        /** What boarding costs an Agent that does not hold the ticket yet. */
        public int cost() {
            return fare + (ticket == null ? 0 : ticket.price());
        }

        int[] selectionArray() {
            return selections.stream().mapToInt(Integer::intValue).toArray();
        }
    }

    record Catalog(int schemaVersion, List<Leg> legs) {
    }

    private AgentTravelLegCatalog() {
    }

    public static List<Leg> legs() {
        return LEGS;
    }

    static List<Leg> from(int mapId) {
        return FROM.getOrDefault(mapId, List.of());
    }

    /** True on a waiting room or ride map of some leg: the rider waits there until the server moves it on. */
    public static boolean rideMap(int mapId) {
        return RIDE_MAPS.contains(mapId);
    }

    /**
     * True where a leg boards or lands or its ticket is sold (Orbis Station and its platforms, Ellinia Station,
     * Kerning City...): a traveller standing there may board any moment.
     */
    public static boolean station(int mapId) {
        return STATIONS.contains(mapId);
    }

    private static List<Leg> load() {
        try (InputStream input = AgentTravelLegCatalog.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("missing travel leg catalog: " + RESOURCE);
            }
            Catalog catalog = MAPPER.readValue(input, Catalog.class);
            if (catalog.legs() == null || catalog.legs().isEmpty()) {
                throw new IllegalStateException("travel leg catalog lists no leg: " + RESOURCE);
            }
            Set<String> ids = new HashSet<>();
            for (Leg leg : catalog.legs()) {
                if (!ids.add(leg.id())) {
                    throw new IllegalStateException("duplicate travel leg " + leg.id());
                }
            }
            return List.copyOf(catalog.legs());
        } catch (IOException failure) {
            throw new IllegalStateException("could not load travel leg catalog: " + RESOURCE, failure);
        }
    }

    private static Map<Integer, List<Leg>> byOrigin(List<Leg> legs) {
        Map<Integer, List<Leg>> from = new HashMap<>();
        for (Leg leg : legs) {
            from.computeIfAbsent(leg.fromMapId(), ignored -> new ArrayList<>()).add(leg);
        }
        Map<Integer, List<Leg>> result = new HashMap<>();
        from.forEach((mapId, list) -> result.put(mapId, List.copyOf(list)));
        return Map.copyOf(result);
    }

    private static Set<Integer> stations(List<Leg> legs) {
        Set<Integer> maps = new HashSet<>();
        for (Leg leg : legs) {
            maps.add(leg.fromMapId());
            maps.add(leg.toMapId());
            if (leg.ticket() != null) {
                maps.add(leg.ticket().sellerMapId());
            }
        }
        return Set.copyOf(maps);
    }

    private static Set<Integer> rideMaps(List<Leg> legs) {
        Set<Integer> maps = new HashSet<>();
        for (Leg leg : legs) {
            maps.addAll(leg.rideMapIds());
        }
        return Set.copyOf(maps);
    }
}
