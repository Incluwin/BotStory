package server.living.townlife;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import server.agents.capabilities.movement.AgentGroundingService;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.capabilities.navigation.AgentPortalApproachService;
import server.agents.capabilities.townlife.AgentTownLifeAdmissionMode;
import server.agents.capabilities.townlife.AgentTownLifeProfile;
import server.agents.capabilities.townlife.AgentTownLifeProfile.Affordance;
import server.agents.capabilities.townlife.AgentTownLifeProfile.FacilityType;
import server.agents.capabilities.townlife.AgentTownLifeProfile.TrafficZone;
import server.agents.capabilities.townlife.AgentTownLifeProfile.TrafficZoneType;
import server.agents.capabilities.townlife.AgentTownLifeProfileValidator;
import server.agents.capabilities.townlife.AgentTownLifeState.District;
import server.agents.capabilities.townlife.AgentTownLifeState.PlatformKind;
import server.maps.Portal;
import server.maps.Rope;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IntSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/** Builds a schema-v2 TownLife profile from a town's WZ geometry and nav graph; the same map always yields the same file. */
public final class TownLifeProfileGenerator {
    private static final int SPAWN_PORTAL = 0;
    private static final int SCRIPT_PORTAL = 7;
    private static final int HIDDEN_PORTAL = 10;
    private static final Pattern MARKET_SCRIPT = Pattern.compile("market\\d+");
    private static final Pattern OUT_PORTAL = Pattern.compile("out\\d+");
    private static final int FREE_MARKET_MAP = 910000000;
    private static final int EDGE_INSET_PX = 30;
    private static final int SLOT_SPACING_PX = 80;
    private static final int STREET_STEP_PX = 85;
    private static final int[] LATTICE_RETRIES = {15, -15, 30, -30};
    private static final int MIN_RETRY_GAP_PX = 60;
    private static final int MINI_MAX_WIDTH = 260;
    private static final int MIN_LEDGE_WIDTH = 100;
    private static final int MIN_PLATFORM_WIDTH = 48;
    private static final int MAX_SLOTS_PER_PLATFORM = 8;
    private static final int SLOPE_PROBE_PX = 10;
    private static final double MAX_SLOPE = 0.6;
    private static final int LADDER_HALF_WIDTH = 45;
    private static final int LADDER_FLOOR_REACH_PX = 120;
    private static final int STEP_DY = 60;
    private static final int STEP_DX = 20;
    private static final int DOORSTEP_PX = 30;
    private static final int DOORSTEP_DY = 60;
    private static final int STREET_LEVEL_PX = 10;
    private static final int SEAT_GAP_PX = 120;
    private static final int MAX_SEATS_PER_VENUE = 8;
    private static final int MINI_GROUP_DX = 250;
    private static final int MINI_GROUP_DY = 80;
    private static final int MAX_MINI_GROUP = 4;
    private static final int MAX_LEDGE_VENUES = 2;
    private static final int MAX_STREET_VENUE = 4;
    private static final int LEVEL_DY = 60;
    private static final int VENUE_GAP_PX = 150;
    private static final int VENUE_STACK_DY = 250;
    private static final int LENGTH_PER_VENUE_PX = 1000;
    private static final int MIN_VENUES = 14;
    private static final int MAX_VENUES = 20;
    private static final int BROWSE_RESERVE = 4;
    private static final int FOCUS_DX = 300;
    private static final int NEAR_DY = 100;
    private static final int POI_RADIUS_PX = 250;
    private static final int LABEL_RADIUS_PX = 400;
    private static final int DOOR_STEP_PX = 60;
    private static final int DOOR_APPROACH_PX = 100;
    private static final int APPROACH_STEP_PX = 40;
    private static final int[] MINI_OFFSETS = {0, 10, -10, 20, -20, 30, -30};
    private static final int OCCUPIED_DX = 40;
    private static final int OCCUPIED_DY = 30;
    private static final int NPC_OFFSET_PX = 65;
    private static final int NPC_LANE_PAD_PX = 20;
    private static final int NPC_LANE_BELOW_PX = 40;
    private static final int MAX_NPC_SPOTS = 8;
    private static final int EXIT_EDGE_PX = 400;
    private static final int INNER_INSET_PX = 150;
    private static final int MIN_ROAM_SPOTS = 6;
    private static final int MAX_ROAM_SPOTS = 8;
    private static final int MIN_USABLE_SEATS = 4;
    private static final int MAX_STREET_REST_SPOTS = 8;
    private static final int LENGTH_PER_AGENT_PX = 120;
    private static final int HOTSPOT_EDGE_PX = 300;
    private static final int HOTSPOT_GAP_PX = 400;
    private static final int HOTSPOT_FOCUS_DX = 600;
    private static final int HOTSPOT_FOCUS_DY = 250;
    private static final Set<FacilityType> SERVICES = EnumSet.of(FacilityType.TAXI, FacilityType.STORAGE,
            FacilityType.PARTY_QUEST);
    /** The Free Market and the services: their browse venues are never trimmed and each wants a street venue near it. */
    private static final Set<FacilityType> NEVER_TRIMMED = EnumSet.of(FacilityType.FREE_MARKET, FacilityType.TAXI,
            FacilityType.STORAGE, FacilityType.PARTY_QUEST);
    private static final Comparator<Point> X_THEN_Y = Comparator.comparingInt((Point point) -> point.x)
            .thenComparingInt(point -> point.y);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TownLifeProfileGenerator() {
    }

    public record Result(String json, List<String> report, List<String> warnings, List<String> problems) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    public static Result generate(TownMapData data, String profileId, AgentTownLifeAdmissionMode admissionMode) {
        return new Run(data, profileId, admissionMode).build();
    }

    static String slug(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("['\u2019]", "")
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    /** A WZ name fit for a label: single spaces, 'Enterence' spelled right, no trailing '(...)' qualifier. */
    static String clean(String name) {
        return name.replace("Enterence", "Entrance").replaceAll("\\s+", " ").trim()
                .replaceAll("\\s*\\([^()]*\\)$", "");
    }

    /** Pretty-prints a profile tree the way the hand-authored profiles read: one list element per line. */
    static String render(ObjectNode root) {
        StringJoiner fields = new StringJoiner(",\n", "{\n", "\n}\n");
        root.properties().forEach(field -> fields.add("  " + quote(field.getKey()) + ": " + block(field.getValue())));
        return fields.toString();
    }

    private static String block(JsonNode value) {
        if (value.isObject() && value.size() > 1) {
            StringJoiner lines = new StringJoiner(",\n", "{\n", "\n  }");
            value.properties().forEach(field -> lines.add("    " + quote(field.getKey()) + ": " + inline(field.getValue())));
            return lines.toString();
        }
        if (value.isArray() && !value.isEmpty() && value.get(0).isObject()) {
            StringJoiner lines = new StringJoiner(",\n", "[\n", "\n  ]");
            value.forEach(element -> lines.add("    " + element(element)));
            return lines.toString();
        }
        return inline(value);
    }

    private static String element(JsonNode element) {
        boolean nested = element.properties().stream()
                .anyMatch(field -> field.getValue().isArray() && !field.getValue().isEmpty()
                        && field.getValue().get(0).isObject());
        if (!nested) {
            return inline(element);
        }
        StringJoiner head = new StringJoiner(", ", "{ ", ",");
        List<String> tails = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : element.properties()) {
            String text = quote(field.getKey()) + ": ";
            if (field.getValue().isArray()) {
                tails.add("      " + text + wrapped(field.getValue()));
            } else {
                head.add(text + inline(field.getValue()));
            }
        }
        return head + "\n" + String.join(",\n", tails) + " }";
    }

    private static String wrapped(JsonNode array) {
        if (array.size() <= 4) {
            return inline(array);
        }
        StringBuilder text = new StringBuilder("[\n        ");
        for (int i = 0; i < array.size(); i++) {
            text.append(i == 0 ? "" : i % 4 == 0 ? ",\n        " : ", ").append(inline(array.get(i)));
        }
        return text + "\n      ]";
    }

    private static String inline(JsonNode value) {
        if (value.isObject()) {
            StringJoiner fields = new StringJoiner(", ", "{ ", " }").setEmptyValue("{}");
            value.properties().forEach(field -> fields.add(quote(field.getKey()) + ": " + inline(field.getValue())));
            return fields.toString();
        }
        if (value.isArray()) {
            StringJoiner items = new StringJoiner(", ", "[", "]");
            value.forEach(item -> items.add(inline(item)));
            return items.toString();
        }
        return value.toString();
    }

    private static String quote(String text) {
        return TextNode.valueOf(text).toString();
    }

    private enum Kind {
        INTERNAL,
        FIELD,
        INTERIOR
    }

    private record Slot(int regionId, int width, Point point) {
    }

    /** What draws a venue; weight 2 marks arrivals and the services crowds gather at. */
    private enum PoiType {
        NPC(1),
        DOOR(1),
        ARRIVAL(2),
        FREE_MARKET(2),
        SERVICE(2);

        final int weight;

        PoiType(int weight) {
            this.weight = weight;
        }
    }

    private record Poi(PoiType type, Point point, String label) {
    }

    /** A place a crowd should find a venue near: an arrival portal or a service facility. */
    private record Focus(String what, Point point) {
    }

    private record NpcTarget(TownMapData.Npc npc, int offsetX, Point point) {
    }

    /** {@code door} is the portal's approach target, or where the facility NPC is drawn. */
    private record FacilityDraft(String name, FacilityType type, int npcId, int destinationMapId, Point door,
                                 Slot approach, String browseLabel) {
    }

    /** A venue under construction; affordances stay mutable until the guarantees have run. */
    private static final class Draft {
        final String word;
        final int width;
        final List<AgentTownLifeProfile.VenueSpot> spots;
        final EnumSet<Affordance> affordances;
        final District district;
        int score;
        String label;
        String id;

        Draft(String word, int width, List<AgentTownLifeProfile.VenueSpot> spots, EnumSet<Affordance> affordances,
              District district) {
            this.word = word;
            this.width = width;
            this.spots = spots;
            this.affordances = affordances;
            this.district = district;
        }

        int capacity() {
            return spots.size();
        }

        Point median() {
            return spots.get(spots.size() / 2).point();
        }

        int west() {
            return spots.stream().mapToInt(AgentTownLifeProfile.VenueSpot::x).min().orElseThrow();
        }

        int east() {
            return spots.stream().mapToInt(AgentTownLifeProfile.VenueSpot::x).max().orElseThrow();
        }

        /** Horizontal distance from x to the venue's spots; 0 when x lies between them. */
        int distance(int x) {
            return Math.max(0, Math.max(west() - x, x - east()));
        }

        /** Horizontal gap between two venues' spans; negative when they overlap. */
        int gap(Draft other) {
            return Math.max(west() - other.east(), other.west() - east());
        }

        PlatformKind kind() {
            return width <= MINI_MAX_WIDTH ? PlatformKind.MINI : PlatformKind.MAIN;
        }
    }

    private static final class Run {
        private final TownMapData data;
        private final AgentNavigationGraph graph;
        private final String profileId;
        private final AgentTownLifeAdmissionMode admissionMode;
        private final List<String> report = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final Map<Portal, Kind> kinds = new LinkedHashMap<>();
        private Set<Integer> reachable = Set.of();
        private List<AgentNavigationGraph.Region> walkable = List.of();
        private Set<Integer> walkableIds = Set.of();
        private Portal anchorPortal;
        private List<TrafficZone> zones = List.of();
        private List<TrafficZone> ladderBands = List.of();
        private int upperMaxY;
        private int middleMaxY;
        private int minX;
        private int maxX;
        private long totalLength;
        private int westExit = Integer.MIN_VALUE;
        private int eastExit = Integer.MAX_VALUE;
        private final List<Poi> pois = new ArrayList<>();
        /** Seats, facility approaches, Free Market spots and npc targets: no candidate spot stacks on them. */
        private final List<Point> occupied = new ArrayList<>();

        Run(TownMapData data, String profileId, AgentTownLifeAdmissionMode admissionMode) {
            this.data = data;
            this.graph = data.graph();
            this.profileId = profileId;
            this.admissionMode = admissionMode;
        }

        Result build() {
            if (data.swim()) {
                return refused("swim map: the nav graph does not model swimming");
            }
            data.portals().forEach(portal -> kinds.put(portal, kind(portal)));
            if (!chooseWalkable()) {
                return refused("no field or spawn portal approach lies on a walkable region");
            }
            zones = trafficZones();
            levels();

            List<AgentTownLifeProfile.RestSpot> restSpots = new ArrayList<>();
            Map<Integer, List<AgentTownLifeProfile.RestSpot>> seatsByRegion = new TreeMap<>();
            Set<Point> seatPoints = new HashSet<>();
            int skippedSeats = 0;
            for (TownMapData.Seat seat : data.seats()) {
                TownLifeGeometryCheck.Ground ground = TownLifeGeometryCheck.ground(data, seat.point());
                if (!seatPoints.add(seat.point()) || !ground.ok() || !walkableIds.contains(ground.regionId())
                        || !free(seat.point())) {
                    skippedSeats++;
                    continue;
                }
                AgentTownLifeProfile.RestSpot spot =
                        new AgentTownLifeProfile.RestSpot(seat.point().x, seat.point().y, seat.id());
                restSpots.add(spot);
                seatsByRegion.computeIfAbsent(ground.regionId(), ignored -> new ArrayList<>()).add(spot);
                occupied.add(seat.point());
            }
            int usableSeats = restSpots.size();

            Map<Portal, Integer> arrivals = arrivalPortals();
            if (arrivals.isEmpty()) {
                return refused("no portal approach lies on the walkable set");
            }
            Portal primary = arrivals.containsKey(anchorPortal) ? anchorPortal : arrivals.keySet().iterator().next();
            Point anchorPoint = landing(primary);
            List<FacilityDraft> facilities = facilities();
            collectPois(arrivals.keySet(), facilities);
            List<Draft> browse = new ArrayList<>();
            facilities.forEach(facility -> browse.add(browseVenue(facility)));
            List<NpcTarget> npcTargets = npcTargets();
            List<Draft> others = new ArrayList<>();
            seatsByRegion.forEach((region, seats) -> seatVenues(region, seats, others));
            List<Slot> miniSlots = miniVenues(others);
            List<Slot> mainSlots = streetVenues(others);
            List<Focus> focuses = new ArrayList<>();
            arrivals.keySet().forEach(portal -> focuses.add(new Focus("arrival portal " + portal.getName(),
                    landing(portal))));
            facilities.stream().filter(facility -> NEVER_TRIMMED.contains(facility.type()))
                    .forEach(facility -> focuses.add(new Focus("facility " + facility.name(), facility.approach().point())));
            int neverTrimmed = (int) facilities.stream().filter(facility -> NEVER_TRIMMED.contains(facility.type())).count();
            List<Draft> venues = trim(others, browse, neverTrimmed, focuses);
            List<Draft> platforms = venues.stream().filter(venue -> venue.word != null).toList();
            guarantee(venues, platforms);
            if (usableSeats < MIN_USABLE_SEATS) {
                // REST without a free venue spot falls back to restSpots only, so a town short of seats lends the middle
                // spot of its best street REST venues, one per venue.
                for (Draft venue : venues.stream().filter(venue -> "street".equals(venue.word)
                                && venue.affordances.contains(Affordance.REST))
                        .sorted(Comparator.comparingInt((Draft venue) -> -venue.score)
                                .thenComparingInt(venue -> venue.median().x)).toList()) {
                    Point point = venue.median();
                    if (restSpots.size() - usableSeats < MAX_STREET_REST_SPOTS
                            && !near(point, restSpots.stream().map(AgentTownLifeProfile.RestSpot::point).toList())) {
                        restSpots.add(new AgentTownLifeProfile.RestSpot(point.x, point.y, -1));
                    }
                }
            }
            restSpots.sort(Comparator.comparing(AgentTownLifeProfile.RestSpot::point, X_THEN_Y)
                    .thenComparingInt(AgentTownLifeProfile.RestSpot::seatId));
            Set<String> venueIds = new HashSet<>();
            Set<String> labels = new HashSet<>();
            for (Draft venue : venues) {
                venue.label = unique(venue.label != null ? venue.label : label(venue, labels), " ", labels);
                String base = slug(venue.label);
                base = base.startsWith(profileId + "-") ? base.substring(profileId.length() + 1) : base;
                venue.id = unique(base.isEmpty() ? slug(fallbackLabel(venue)) : base, "-", venueIds);
            }
            List<Draft> hotspots = hotspots(venues, facilities, anchorPoint);
            List<Point> taken = new ArrayList<>();
            restSpots.forEach(spot -> taken.add(spot.point()));
            npcTargets.forEach(target -> taken.add(target.point()));
            facilities.forEach(facility -> taken.add(facility.approach().point()));
            venues.forEach(venue -> venue.spots.forEach(spot -> taken.add(spot.point())));
            List<Point> roam = roamSpots(mainSlots, miniSlots, hotspots.isEmpty() ? anchorPoint : hotspots.getFirst().median(),
                    taken);

            ObjectNode root = MAPPER.createObjectNode();
            root.put("schemaVersion", 2);
            root.put("profileId", profileId);
            root.put("mapId", data.mapId());
            ObjectNode admission = root.putObject("admission");
            admission.put("mode", admissionMode.name());
            admission.put("maxAmbientAgents", Math.clamp(Math.round(totalLength / (double) LENGTH_PER_AGENT_PX), 12, 36));
            admission.put("minVisitMs", 60000);
            admission.put("maxVisitMs", 900000);
            admission.put("revisitCooldownMs", 120000);
            ObjectNode geometry = root.putObject("geometry");
            geometry.put("upperMaxY", upperMaxY);
            geometry.put("middleMaxY", middleMaxY);
            geometry.put("miniPlatformMaxWidth", MINI_MAX_WIDTH);
            geometry.put("minimumPlatformWidth", MIN_PLATFORM_WIDTH);
            geometry.put("edgeInsetPx", EDGE_INSET_PX);
            geometry.put("slotSpacingPx", SLOT_SPACING_PX);
            geometry.put("maxSlotsPerPlatform", MAX_SLOTS_PER_PLATFORM);
            Set<District> districts = venues.stream().map(venue -> venue.district).collect(Collectors.toSet());
            if (!districts.contains(District.LOWER)) {
                warnings.add("no venue lies in the LOWER district");
            }
            int miniCapacity = platforms.stream().filter(venue -> venue.kind() == PlatformKind.MINI)
                    .mapToInt(Draft::capacity).sum();
            ObjectNode distribution = root.putObject("distribution");
            distribution.put("upperWeight", districts.contains(District.UPPER) ? 20 : 0);
            distribution.put("middleWeight", districts.contains(District.MIDDLE) ? 35 : 0);
            distribution.put("lowerWeight", districts.contains(District.LOWER) ? 35 : 0);
            distribution.put("anyWeight", 10);
            distribution.put("miniPlatformPercent", Math.clamp(Math.round(100.0 * miniCapacity
                    / Math.max(1, platforms.stream().mapToInt(Draft::capacity).sum())), 0, 35));
            distribution.put("crossDistrictEveryActivities", 5);
            root.putObject("extensions").putArray("activityHandlers");
            ObjectNode weights = root.putObject("activityWeights");
            weights.put("REST", seatsByRegion.isEmpty() ? 90 : 110);
            weights.put("SOCIALIZE", 100);
            weights.put("LINGER", 115);
            weights.put("STROLL", 95);
            weights.put("BROWSE", browse.stream().anyMatch(venues::contains) ? 80 : 30);
            weights.put("SHOW_OFF", 100);
            ArrayNode arrivalNodes = root.putArray("arrivalPortals");
            arrivals.forEach((portal, weight) -> arrivalNodes.addObject().put("name", portal.getName()).put("weight", weight));
            root.set("restSpots", MAPPER.valueToTree(restSpots));
            ArrayNode roamNodes = root.putArray("roamFallbackSpots");
            roam.forEach(point -> roamNodes.addObject().put("x", point.x).put("y", point.y));
            ArrayNode npcNodes = root.putArray("npcSpots");
            npcTargets.forEach(target ->
                    npcNodes.addObject().put("npcId", target.npc().id()).put("offsetX", target.offsetX()));
            root.set("trafficZones", MAPPER.valueToTree(zones));
            root.putArray("platformPolicies");
            ArrayNode venueNodes = root.putArray("venues");
            for (Draft venue : venues) {
                ObjectNode node = venueNodes.addObject().put("id", venue.id).put("label", venue.label)
                        .put("district", venue.district.name()).put("platformKind", venue.kind().name())
                        .put("capacity", venue.capacity());
                node.set("affordances", MAPPER.valueToTree(venue.affordances));
                node.set("spots", MAPPER.valueToTree(venue.spots));
            }
            ArrayNode facilityNodes = root.putArray("facilities");
            Set<String> facilityIds = new HashSet<>();
            for (FacilityDraft facility : facilities) {
                String name = slug(facility.name());
                String base = name.isEmpty() ? profileId + "-" + slug(facility.type().name())
                        : name.startsWith(profileId + "-") ? name : profileId + "-" + name;
                ObjectNode node = facilityNodes.addObject().put("id", unique(base, "-", facilityIds))
                        .put("type", facility.type().name()).put("npcId", facility.npcId())
                        .put("destinationMapId", facility.destinationMapId());
                Point approach = facility.approach().point();
                node.putArray("approachPoints").addObject().put("x", approach.x).put("y", approach.y);
                node.set("services", MAPPER.valueToTree(services(facility.type())));
            }
            ArrayNode hotspotNodes = root.putArray("hotspots");
            for (int i = 0; i < hotspots.size(); i++) {
                Draft venue = hotspots.get(i);
                hotspotNodes.addObject().put("id", venue.id + "-crowd").put("venueId", venue.id)
                        .put("attractionWeight", i == 0 ? 150 : 110)
                        .put("softCapacity", (2 * venue.capacity() + 2) / 3).put("hardCapacity", venue.capacity());
            }

            long seatVenues = venues.stream().filter(venue -> "benches".equals(venue.word)).count();
            long ledgeVenues = venues.stream().filter(venue -> "ledges".equals(venue.word)).count();
            long browseVenues = venues.stream().filter(venue -> venue.word == null).count();
            report.add(String.format("zones %d, venues %d (street %d, seat %d, ledge %d, browse %d), rest spots %d"
                            + " (seats %d, seats skipped %d), npcSpots %d, facilities %d, hotspots %d, roam %d, arrival %s",
                    zones.size(), venues.size(), venues.size() - seatVenues - ledgeVenues - browseVenues, seatVenues,
                    ledgeVenues, browseVenues, restSpots.size(), usableSeats, skippedSeats, npcNodes.size(),
                    facilities.size(), hotspots.size(), roam.size(),
                    arrivals.entrySet().stream().map(arrival -> arrival.getKey().getName() + "=" + arrival.getValue()).toList()));
            return selfCheck(root);
        }

        private Result refused(String problem) {
            return new Result(null, List.copyOf(report), List.of(), List.of(problem));
        }

        private Result selfCheck(ObjectNode root) {
            String json = render(root);
            List<String> problems = new ArrayList<>();
            try {
                AgentTownLifeProfile profile = MAPPER.readValue(json, AgentTownLifeProfile.class);
                warnings.addAll(AgentTownLifeProfileValidator.validate(profile).warnings());
                TownLifeGeometryCheck.Result check = TownLifeGeometryCheck.check(profile, data);
                problems.addAll(check.errors());
                warnings.addAll(check.warnings());
                Set<String> labels = new HashSet<>();
                profile.venues().stream().filter(venue -> !labels.add(venue.label()))
                        .forEach(venue -> warnings.add("duplicate venue label '" + venue.label() + "'"));
            } catch (JsonProcessingException failure) {
                problems.add("generated profile does not parse: " + failure.getOriginalMessage());
            }
            return new Result(json, List.copyOf(report), List.copyOf(warnings), List.copyOf(problems));
        }

        /**
         * FIELD leads out of town: a non-town map, or a walk-through gate into a town-flagged map that leads on elsewhere.
         * INTERIOR is a building or station door; INTERNAL stays on this map or has no target.
         */
        private Kind kind(Portal portal) {
            TownMapData.Target info = data.targets().get(portal.getTargetMapId());
            if (info == null) {
                return Kind.INTERNAL;
            }
            if (!info.town()) {
                return Kind.FIELD;
            }
            if (typeFromName(clean(info.name())) == FacilityType.TAXI) {
                return Kind.INTERIOR;
            }
            boolean onward = info.exits().stream().anyMatch(exit -> exit != data.mapId());
            return onward && !OUT_PORTAL.matcher(String.valueOf(portal.getTarget())).matches() ? Kind.FIELD : Kind.INTERIOR;
        }

        private boolean chooseWalkable() {
            long best = 0;
            List<Predicate<Portal>> candidates = List.of(portal -> kinds.get(portal) == Kind.FIELD,
                    portal -> portal.getType() == SPAWN_PORTAL);
            for (Predicate<Portal> candidate : candidates) {
                for (Portal portal : data.portals().stream().filter(candidate).toList()) {
                    int anchor = TownLifeGeometryCheck.portalRegion(data, portal);
                    if (anchor < 0) {
                        continue;
                    }
                    Set<Integer> set = graph.regions.stream().map(region -> region.id)
                            .filter(id -> TownLifeGeometryCheck.mutuallyReachable(graph, anchor, id))
                            .collect(Collectors.toSet());
                    long length = set.stream().map(graph::getRegion).filter(region -> !region.isRopeRegion)
                            .mapToLong(Run::length).sum();
                    boolean visibleTie = length == best && anchorPortal != null && portal.getType() == Portal.MAP_PORTAL
                            && anchorPortal.getType() != Portal.MAP_PORTAL;
                    if (length > best || visibleTie) {
                        best = length;
                        anchorPortal = portal;
                        reachable = set;
                    }
                }
                if (best > 0) {
                    break;
                }
            }
            walkable = graph.regions.stream().filter(region -> !region.isRopeRegion && reachable.contains(region.id))
                    .toList();
            walkableIds = walkable.stream().map(region -> region.id).collect(Collectors.toSet());
            if (walkable.isEmpty()) {
                return false;
            }
            List<AgentNavigationGraph.Region> dropped = graph.regions.stream()
                    .filter(region -> !region.isRopeRegion && !reachable.contains(region.id)).toList();
            report.add(String.format("walkable set: %d regions, %d px (anchor %s, region %d); dropped %d regions, %d px",
                    walkable.size(), best, anchorPortal.getName(),
                    TownLifeGeometryCheck.portalRegion(data, anchorPortal), dropped.size(),
                    dropped.stream().mapToLong(Run::length).sum()));
            dropped.stream().filter(region -> length(region) >= MIN_PLATFORM_WIDTH).forEach(region ->
                    report.add(String.format("  dropped region %d x %d..%d y %d..%d (%d px)", region.id,
                            region.minX, region.maxX, region.minY, region.maxY, length(region))));
            return true;
        }

        /** District bounds from the walkable surface, the walkable x-extent and the town's edge exits. */
        private void levels() {
            List<Integer> ys = new ArrayList<>();
            for (AgentNavigationGraph.Region region : walkable) {
                for (AgentNavigationGraph.Segment segment : region.segments) {
                    for (int x = segment.minX; x < segment.maxX; x++) {
                        ys.add(segment.pointAt(x).y);
                    }
                }
            }
            Collections.sort(ys);
            int p70 = ys.get((ys.size() - 1) * 70 / 100);
            long street = ys.stream().filter(y -> Math.abs(y - p70) <= STREET_LEVEL_PX).count();
            // A street level holding a fifth of the town is its LOWER district and the ledges above it are MIDDLE.
            middleMaxY = street * 5 >= ys.size() ? p70 - STREET_LEVEL_PX - 1 : p70;
            upperMaxY = Math.min(ys.get((ys.size() - 1) * 25 / 100), middleMaxY - 60);
            minX = walkable.stream().mapToInt(region -> region.minX).min().orElse(0);
            maxX = walkable.stream().mapToInt(region -> region.maxX).max().orElse(0);
            totalLength = walkable.stream().mapToLong(Run::length).sum();
            IntSummaryStatistics exits = data.portals().stream()
                    .filter(portal -> kinds.get(portal) == Kind.FIELD && onWalkable(portal))
                    .mapToInt(portal -> portal.getPosition().x).summaryStatistics();
            if (exits.getCount() > 0 && exits.getMin() - minX <= EXIT_EDGE_PX) {
                westExit = exits.getMin();
            }
            if (exits.getCount() > 0 && maxX - exits.getMax() <= EXIT_EDGE_PX) {
                eastExit = exits.getMax();
            }
        }

        private List<TrafficZone> trafficZones() {
            List<TrafficZone> raw = new ArrayList<>();
            Set<String> landings = data.portals().stream().filter(portal -> portal.getTargetMapId() == data.mapId())
                    .map(Portal::getTarget).collect(Collectors.toSet());
            for (Portal portal : data.portals()) {
                Point at = portal.getPosition();
                String name = slug(portal.getName());
                switch (kinds.get(portal)) {
                    case FIELD -> raw.add(zone("portal-" + name, TrafficZoneType.PORTAL, at, 70, 70, 55));
                    case INTERIOR -> raw.add(zone("door-" + name, TrafficZoneType.DOOR, at, 60, 65, 50));
                    case INTERNAL -> {
                        if (portal.getType() == Portal.MAP_PORTAL || portal.getType() == SCRIPT_PORTAL) {
                            raw.add(zone("portal-" + name, TrafficZoneType.PORTAL, at, 55, 60, 50));
                        } else if (portal.getTargetMapId() == data.mapId() || landings.contains(portal.getName())) {
                            raw.add(zone("portal-" + name, TrafficZoneType.PORTAL, at, 35, 60, 40));
                        }
                    }
                }
            }
            List<TrafficZone> bands = new ArrayList<>();
            List<Rope> ropes = data.map().getRopes();
            for (int i = 0; i < ropes.size(); i++) {
                Rope rope = ropes.get(i);
                // The band reaches the lowest floor within 120 px under the rope's foot, probed across its width.
                int bottom = rope.bottomY() + 10;
                for (int dx = -LADDER_HALF_WIDTH; dx <= LADDER_HALF_WIDTH; dx += LADDER_HALF_WIDTH) {
                    for (Point floor = data.map().getPointBelow(new Point(rope.x() + dx, rope.bottomY()));
                         floor != null && floor.y - rope.bottomY() <= LADDER_FLOOR_REACH_PX;
                         floor = data.map().getPointBelow(new Point(floor.x, floor.y + 1))) {
                        bottom = Math.max(bottom, floor.y + 10);
                    }
                }
                TrafficZone band = new TrafficZone("ladder-" + i, TrafficZoneType.LADDER, rope.x() - LADDER_HALF_WIDTH,
                        rope.topY() - 60, rope.x() + LADDER_HALF_WIDTH, bottom, true);
                bands.add(band);
                if (reachable.contains(ropeRegion(rope))) {
                    raw.add(band);
                }
            }
            ladderBands = bands;
            // An NPC lane is the sprite plus a body's half-width, from above its head to just under a step below it.
            for (TownMapData.Npc npc : walkableNpcs()) {
                int half = npc.laneHalfWidth() + NPC_LANE_PAD_PX;
                raw.add(new TrafficZone("npc-" + npc.id(), TrafficZoneType.NPC_LANE, npc.x() - half,
                        npc.cy() - 90, npc.x() + half, npc.cy() + NPC_LANE_BELOW_PX, true));
            }
            List<TrafficZone> merged = new ArrayList<>(raw);
            for (int i = 0; i < merged.size(); i++) {
                for (int j = i + 1; j < merged.size(); j++) {
                    TrafficZone first = merged.get(i);
                    TrafficZone second = merged.get(j);
                    if (first.type() == second.type() && first.type() != TrafficZoneType.LADDER
                            && first.minX() <= second.maxX() && second.minX() <= first.maxX()
                            && first.minY() <= second.maxY() && second.minY() <= first.maxY()) {
                        merged.set(i, new TrafficZone(first.id(), first.type(),
                                Math.min(first.minX(), second.minX()), Math.min(first.minY(), second.minY()),
                                Math.max(first.maxX(), second.maxX()), Math.max(first.maxY(), second.maxY()), true));
                        merged.remove(j);
                        i = -1; // the grown zone may now overlap an earlier one: rescan from the start
                        break;
                    }
                }
            }
            Set<String> ids = new HashSet<>();
            return merged.stream().map(zone -> new TrafficZone(unique(zone.id(), "-", ids), zone.type(), zone.minX(),
                    zone.minY(), zone.maxX(), zone.maxY(), true)).toList();
        }

        private static TrafficZone zone(String id, TrafficZoneType type, Point at, int halfWidth, int above, int below) {
            return new TrafficZone(id, type, at.x - halfWidth, at.y - above, at.x + halfWidth, at.y + below, true);
        }

        /** The rope's own region, matched on x, top and bottom like AgentNavigationGraphService.findRopeFromRegion. */
        private int ropeRegion(Rope rope) {
            return graph.regions.stream().filter(region -> region.isRopeRegion && region.minX == rope.x()
                            && region.minY == rope.topY() && region.maxY == rope.bottomY())
                    .mapToInt(region -> region.id).findFirst().orElse(-1);
        }

        private List<TownMapData.Npc> walkableNpcs() {
            return data.npcs().stream().filter(npc -> !npc.hidden() && walkableIds.contains(npcRegion(npc)))
                    .sorted(Comparator.comparingInt(TownMapData.Npc::x).thenComparingInt(TownMapData.Npc::id)).toList();
        }

        /** Where the NPC is drawn (its cy); its fh only when nothing grounds there. */
        private int npcRegion(TownMapData.Npc npc) {
            int region = TownLifeGeometryCheck.ground(data, new Point(npc.x(), npc.cy())).regionId();
            return region >= 0 ? region : graph.regionIdByFootholdId.getOrDefault(npc.fh(), -1);
        }

        private boolean onWalkable(Portal portal) {
            return walkableIds.contains(TownLifeGeometryCheck.portalRegion(data, portal));
        }

        private String targetName(Portal portal) {
            return clean(data.targets().get(portal.getTargetMapId()).name());
        }

        /** Where a player coming through the portal stands: the ground straight below it (spawn portals float). */
        private Point landing(Portal portal) {
            Point ground = AgentGroundingService.findGroundPoint(data.map(), portal.getPosition());
            return ground != null ? ground : AgentPortalApproachService.navigableTarget(data.map(), portal);
        }

        private District district(int y) {
            return y <= upperMaxY ? District.UPPER : y <= middleMaxY ? District.MIDDLE : District.LOWER;
        }

        private int third(int x) {
            int third = (maxX - minX) / 3;
            return x < minX + third ? 0 : x > maxX - third ? 2 : 1;
        }

        private boolean beyondExit(int x) {
            return x < westExit || x > eastExit;
        }

        /** 150 px inside the walkable extent and not past an edge exit: where street crowds and logins belong. */
        private boolean inner(int x) {
            return x >= minX + INNER_INSET_PX && x <= maxX - INNER_INSET_PX && !beyondExit(x);
        }

        private boolean free(Point point) {
            return zones.stream().noneMatch(zone -> zone.contains(point));
        }

        private static boolean near(Point point, List<Point> points) {
            return points.stream().anyMatch(other -> Math.abs(other.x - point.x) < OCCUPIED_DX
                    && Math.abs(other.y - point.y) < OCCUPIED_DY);
        }

        /** Grounded on the region in the runtime's arrival window, outside every zone and off every occupied point. */
        private boolean standable(Point point, int regionId) {
            TownLifeGeometryCheck.Ground ground = TownLifeGeometryCheck.ground(data, point);
            return ground.ok() && ground.regionId() == regionId && free(point) && !near(point, occupied);
        }

        /** A clear standing point at x on the region, on the runtime's (truncated) surface, or null. */
        private Point slot(AgentNavigationGraph.Region region, int x) {
            if (x < region.minX + EDGE_INSET_PX || x > region.maxX - EDGE_INSET_PX) {
                return null;
            }
            Point surface = region.pointAt(x);
            int rise = Math.abs(region.pointAt(x + SLOPE_PROBE_PX).y - region.pointAt(x - SLOPE_PROBE_PX).y);
            Point ground = AgentGroundingService.findGroundPoint(data.map(), surface);
            if (surface.x != x || rise > MAX_SLOPE * 2 * SLOPE_PROBE_PX || ground == null) {
                return null;
            }
            Point point = new Point(x, ground.y);
            return standable(point, region.id) && ladderBands.stream().noneMatch(band -> band.contains(point)) ? point : null;
        }

        /**
         * A narrow region with another walkable region within 60 px of its height that overlaps it or ends within 20 px
         * of it: a stair step, a shelf stacked over another, a stall counter.
         */
        private boolean step(AgentNavigationGraph.Region region) {
            return region.width() <= MINI_MAX_WIDTH && walkable.stream()
                    .filter(other -> other != region && Math.max(other.minX - region.maxX, region.minX - other.maxX) <= STEP_DX)
                    .anyMatch(other -> {
                        int x = Math.clamp((region.minX + region.maxX) / 2, other.minX, other.maxX);
                        return Math.abs(other.pointAt(x).y - region.pointAt(x).y) <= STEP_DY;
                    });
        }

        /** A portal at the region's height within 30 px of its ends: a doorstep, teleport pad or hidden-portal nook. */
        private boolean doorstep(AgentNavigationGraph.Region region) {
            return data.portals().stream().map(Portal::getPosition).anyMatch(at -> at.x >= region.minX - DOORSTEP_PX
                    && at.x <= region.maxX + DOORSTEP_PX && Math.abs(at.y - region.pointAt(at.x).y) <= DOORSTEP_DY);
        }

        /** Resident NPCs, interior doors, Free Market portals, arrivals and services, each where one stands by it. */
        private void collectPois(Set<Portal> arrivals, List<FacilityDraft> facilities) {
            for (TownMapData.Npc npc : walkableNpcs()) {
                if (!npc.global()) {
                    pois.add(new Poi(PoiType.NPC, new Point(npc.x(), npc.cy()),
                            npc.name().isBlank() ? null : "By " + clean(npc.name())));
                }
            }
            for (Portal portal : data.portals()) {
                if (!onWalkable(portal)) {
                    continue;
                }
                Point at = landing(portal);
                if (kinds.get(portal) == Kind.INTERIOR) {
                    String name = targetName(portal);
                    pois.add(new Poi(PoiType.DOOR, at, name.isBlank() ? null : name + " door"));
                } else if (market(portal)) {
                    pois.add(new Poi(PoiType.FREE_MARKET, at, null));
                }
                if (arrivals.contains(portal)) {
                    pois.add(new Poi(PoiType.ARRIVAL, at, null));
                }
            }
            facilities.stream().filter(facility -> SERVICES.contains(facility.type())).forEach(facility ->
                    pois.add(new Poi(PoiType.SERVICE, facility.approach().point(), null)));
        }

        /**
         * Nearness of the venue's median to the map centre plus, per POI type, of its span to the nearest POI of that
         * type within 100 px up or down.
         */
        private int poiScore(Draft venue) {
            Point median = venue.median();
            int score = Math.max(0, POI_RADIUS_PX - Math.abs(median.x - (minX + maxX) / 2));
            for (PoiType type : PoiType.values()) {
                score += type.weight * pois.stream()
                        .filter(poi -> poi.type() == type && Math.abs(poi.point().y - median.y) <= NEAR_DY)
                        .mapToInt(poi -> Math.max(0, POI_RADIUS_PX - venue.distance(poi.point().x))).max().orElse(0);
            }
            return score;
        }

        private Draft venue(String word, List<AgentTownLifeProfile.VenueSpot> spots, int width,
                            EnumSet<Affordance> affordances) {
            List<Integer> ys = spots.stream().map(AgentTownLifeProfile.VenueSpot::y).sorted().toList();
            Draft draft = new Draft(word, width, List.copyOf(spots), affordances, district(ys.get(ys.size() / 2)));
            draft.score = poiScore(draft);
            return draft;
        }

        private static List<AgentTownLifeProfile.VenueSpot> spots(List<Point> points) {
            return points.stream().map(point -> new AgentTownLifeProfile.VenueSpot(point.x, point.y, -1)).toList();
        }

        /** Seats in runs with gaps <= 120 px; a run longer than 8 splits into near-equal venues (9 -> 5 + 4). */
        private void seatVenues(int regionId, List<AgentTownLifeProfile.RestSpot> seats, List<Draft> drafts) {
            int width = graph.getRegion(regionId).width();
            List<List<AgentTownLifeProfile.RestSpot>> runs = new ArrayList<>();
            for (AgentTownLifeProfile.RestSpot seat : seats.stream()
                    .sorted(Comparator.comparingInt(AgentTownLifeProfile.RestSpot::x)
                            .thenComparingInt(AgentTownLifeProfile.RestSpot::seatId)).toList()) {
                if (runs.isEmpty() || seat.x() - runs.getLast().getLast().x() > SEAT_GAP_PX) {
                    runs.add(new ArrayList<>());
                }
                runs.getLast().add(seat);
            }
            for (List<AgentTownLifeProfile.RestSpot> run : runs) {
                int chunks = (run.size() + MAX_SEATS_PER_VENUE - 1) / MAX_SEATS_PER_VENUE;
                for (int i = 0, start = 0; i < chunks; i++) {
                    int size = run.size() / chunks + (i < run.size() % chunks ? 1 : 0);
                    List<AgentTownLifeProfile.VenueSpot> spots = run.subList(start, start + size).stream()
                            .map(seat -> new AgentTownLifeProfile.VenueSpot(seat.x(), seat.y(), seat.seatId())).toList();
                    drafts.add(venue("benches", spots, width, spots.size() >= 2
                            ? EnumSet.of(Affordance.REST, Affordance.SOCIALIZE, Affordance.LINGER)
                            : EnumSet.of(Affordance.REST, Affordance.LINGER)));
                    start += size;
                }
            }
        }

        /** Ledge venues of 2-4 real ledges (>= 100 px, no step, no doorstep), every pair within 250 x 80 px. */
        private List<Slot> miniVenues(List<Draft> drafts) {
            List<Slot> slots = new ArrayList<>();
            for (AgentNavigationGraph.Region region : walkable) {
                if (region.width() > MINI_MAX_WIDTH || region.width() < MIN_LEDGE_WIDTH || step(region) || doorstep(region)) {
                    continue;
                }
                Arrays.stream(MINI_OFFSETS).mapToObj(offset -> slot(region, region.minX + region.width() / 2 + offset))
                        .filter(point -> point != null).findFirst()
                        .ifPresent(point -> slots.add(new Slot(region.id, region.width(), point)));
            }
            slots.sort(Comparator.comparing(Slot::point, X_THEN_Y));
            Set<Slot> grouped = new HashSet<>();
            for (int i = 0; i < slots.size(); i++) {
                Slot seed = slots.get(i);
                if (grouped.contains(seed)) {
                    continue;
                }
                List<Slot> group = new ArrayList<>(List.of(seed));
                for (int j = i + 1; j < slots.size() && group.size() < MAX_MINI_GROUP; j++) {
                    Slot other = slots.get(j);
                    if (!grouped.contains(other) && district(other.point().y) == district(seed.point().y)
                            && group.stream().allMatch(member -> Math.abs(other.point().x - member.point().x) <= MINI_GROUP_DX
                            && Math.abs(other.point().y - member.point().y) <= MINI_GROUP_DY)) {
                        group.add(other);
                    }
                }
                if (group.size() < 2) {
                    continue;
                }
                grouped.addAll(group);
                List<Point> points = group.stream().map(Slot::point).toList();
                drafts.add(venue("ledges", spots(points), group.stream().mapToInt(Slot::width).max().orElse(0),
                        EnumSet.of(Affordance.LINGER, Affordance.STROLL, Affordance.SHOW_OFF, Affordance.SOCIALIZE)));
            }
            return slots;
        }

        /**
         * Lattice slots every 85 px on each MAIN region, 150 px inside the town; a run ends at a gap, or where a portal,
         * door or ladder lies between two slots, so no venue straddles one.
         */
        private List<Slot> streetVenues(List<Draft> drafts) {
            List<Slot> slots = new ArrayList<>();
            for (AgentNavigationGraph.Region region : walkable) {
                if (region.width() <= MINI_MAX_WIDTH) {
                    continue;
                }
                List<Slot> run = new ArrayList<>();
                Point previous = null;
                for (int x = region.minX + EDGE_INSET_PX; x <= region.maxX - EDGE_INSET_PX; x += STREET_STEP_PX) {
                    Point point = inner(x) ? latticeSlot(region, x, previous) : null;
                    if (point == null || !run.isEmpty() && barrier(run.getLast().point(), point)) {
                        streetRun(run, drafts);
                        run = new ArrayList<>();
                    }
                    if (point == null) {
                        continue;
                    }
                    x = point.x;
                    previous = point;
                    Slot slot = new Slot(region.id, region.width(), point);
                    run.add(slot);
                    slots.add(slot);
                }
                streetRun(run, drafts);
            }
            return slots;
        }

        /** A portal, door or ladder zone across the street between two points. */
        private boolean barrier(Point first, Point second) {
            return Stream.concat(zones.stream().filter(zone -> zone.type() != TrafficZoneType.NPC_LANE), ladderBands.stream())
                    .anyMatch(zone -> zone.minX() < Math.max(first.x, second.x) && zone.maxX() > Math.min(first.x, second.x)
                            && zone.minY() <= Math.max(first.y, second.y) && zone.maxY() >= Math.min(first.y, second.y));
        }

        /** The slot at a lattice point, else 15 or 30 px either side of it, never within 60 px of the previous slot. */
        private Point latticeSlot(AgentNavigationGraph.Region region, int x, Point previous) {
            Point point = slot(region, x);
            for (int i = 0; point == null && i < LATTICE_RETRIES.length; i++) {
                int retry = x + LATTICE_RETRIES[i];
                if (previous == null || retry - previous.x >= MIN_RETRY_GAP_PX) {
                    point = slot(region, retry);
                }
            }
            return point;
        }

        /** Every window of 4 consecutive slots (the whole run when shorter, at least a pair) is a candidate for trim(). */
        private void streetRun(List<Slot> run, List<Draft> drafts) {
            int size = Math.min(MAX_STREET_VENUE, run.size());
            for (int start = 0; size >= 2 && start + size <= run.size(); start++) {
                List<Point> points = run.subList(start, start + size).stream().map(Slot::point).toList();
                Draft draft = venue("street", spots(points), run.getFirst().width(),
                        EnumSet.of(Affordance.SOCIALIZE, Affordance.LINGER, Affordance.STROLL));
                draft.affordances.add(draft.district == District.UPPER ? Affordance.SHOW_OFF : Affordance.REST);
                drafts.add(draft);
            }
        }

        /**
         * LINGER targets beside resident NPCs (never global ones): facility NPCs first, then the rest round-robin across
         * the town's thirds, a body's width past the NPC lane (at least 65 px), else 40 px farther out. The side flips
         * away from seats, approaches and earlier targets; at most 8.
         */
        private List<NpcTarget> npcTargets() {
            List<TownMapData.Npc> eligible = walkableNpcs().stream().filter(npc -> npc == data.npc(npc.id())
                    && !npc.global() && Math.abs(npc.y() - npc.cy()) <= TownLifeGeometryCheck.MAX_ABOVE_SURFACE_PX).toList();
            List<TownMapData.Npc> ordered = new ArrayList<>(eligible.stream().filter(npc -> facilityType(npc) != null).toList());
            List<List<TownMapData.Npc>> thirds = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            eligible.stream().filter(npc -> facilityType(npc) == null).forEach(npc -> thirds.get(third(npc.x())).add(npc));
            for (int round = 0; ordered.size() < eligible.size(); round++) {
                for (List<TownMapData.Npc> third : thirds) {
                    if (round < third.size()) {
                        ordered.add(third.get(round));
                    }
                }
            }
            List<NpcTarget> targets = new ArrayList<>();
            for (int i = 0; i < ordered.size() && targets.size() < MAX_NPC_SPOTS; i++) {
                TownMapData.Npc npc = ordered.get(i);
                int side = i % 2 == 0 ? 1 : -1;
                int regionId = npcRegion(npc);
                AgentNavigationGraph.Region region = graph.getRegion(regionId);
                for (int offset : Arrays.stream(offsets(beside(npc))).limit(4).map(offset -> side * offset).toArray()) {
                    Point target = new Point(npc.x() + offset, npc.y());
                    if (target.x >= region.minX + EDGE_INSET_PX && target.x <= region.maxX - EDGE_INSET_PX
                            && standable(target, regionId)) {
                        targets.add(new NpcTarget(npc, offset, target));
                        occupied.add(target);
                        break;
                    }
                }
            }
            return targets;
        }

        /** Interior doors, Free Market portals and service NPCs, each with a clear approach slot beside it. */
        private List<FacilityDraft> facilities() {
            List<FacilityDraft> facilities = new ArrayList<>();
            Set<Integer> destinations = new HashSet<>();
            for (Portal portal : data.portals()) {
                boolean market = market(portal);
                if (!market && (kinds.get(portal) != Kind.INTERIOR || destinations.contains(portal.getTargetMapId()))) {
                    continue;
                }
                String name = market ? "Free Market" : targetName(portal);
                name = name.isBlank() ? portal.getName() : name;
                Point door = AgentPortalApproachService.navigableTarget(data.map(), portal);
                int regionId = door == null ? -1 : graph.findRegionId(data.map(), door);
                if (!walkableIds.contains(regionId)) {
                    report.add("  no facility for portal " + portal.getName() + " (" + name + "): off the walkable set");
                    continue;
                }
                Slot approach = besideDoor(door, regionId, DOOR_APPROACH_PX);
                if (approach == null) {
                    report.add("  no facility for portal " + portal.getName() + " (" + name + "): no clear approach");
                    continue;
                }
                occupied.add(approach.point());
                if (!market) {
                    destinations.add(portal.getTargetMapId());
                }
                facilities.add(new FacilityDraft(name, market ? FacilityType.FREE_MARKET : typeFromName(name), 0,
                        market ? FREE_MARKET_MAP : portal.getTargetMapId(), door, approach,
                        name.toLowerCase(Locale.ROOT).endsWith("entrance") ? name : name + " entrance"));
            }
            for (TownMapData.Npc npc : walkableNpcs()) {
                FacilityType type = facilityType(npc);
                if (type == null || npc != data.npc(npc.id())) {
                    continue;
                }
                String name = npc.name().isBlank() ? "npc " + npc.id() : clean(npc.name());
                Point at = new Point(npc.x(), npc.cy());
                Slot approach = besideDoor(at, npcRegion(npc), beside(npc));
                if (approach == null) {
                    report.add("  no facility for npc " + npc.id() + " (" + name + "): no clear approach");
                    continue;
                }
                occupied.add(approach.point());
                // A global NPC says nothing about this town, so its stand is named for the service.
                String label = !npc.global() ? "Beside " + name : type == FacilityType.TAXI ? "Taxi stand" : "Party quest stand";
                facilities.add(new FacilityDraft(name, type, npc.id(), 0, at, approach, label));
            }
            facilities.sort(Comparator.comparingInt((FacilityDraft facility) -> browseRank(facility.type()))
                    .thenComparing(facility -> facility.approach().point(), X_THEN_Y)
                    .thenComparing(FacilityDraft::name));
            return facilities;
        }

        /** {@code near}, then 40 and 80 px farther, each on one side and then the other. */
        private static int[] offsets(int near) {
            return new int[]{near, -near, near + APPROACH_STEP_PX, -near - APPROACH_STEP_PX,
                    near + 2 * APPROACH_STEP_PX, -near - 2 * APPROACH_STEP_PX};
        }

        /** How far beside an NPC one stands: a body's width past its lane, at least 65 px. */
        private static int beside(TownMapData.Npc npc) {
            return Math.max(NPC_OFFSET_PX, npc.laneHalfWidth() + 2 * NPC_LANE_PAD_PX);
        }

        /** A clear slot {@code near} px (then farther) beside a door or NPC, toward the town centre first. */
        private Slot besideDoor(Point door, int doorRegion, int near) {
            int toward = door.x <= (minX + maxX) / 2 ? 1 : -1;
            for (int offset : offsets(near)) {
                Slot slot = slotNear(door, doorRegion, door.x + toward * offset);
                if (slot != null) {
                    return slot;
                }
            }
            return null;
        }

        /**
         * A clear slot at x on the door's own region, else on a non-step region within 60 px of the door's height; never
         * beyond the town's edge exits.
         */
        private Slot slotNear(Point door, int doorRegion, int x) {
            if (beyondExit(x)) {
                return null;
            }
            for (AgentNavigationGraph.Region region : walkable.stream()
                    .filter(region -> region.id == doorRegion
                            || Math.abs(region.pointAt(x).y - door.y) <= DOOR_STEP_PX && !step(region))
                    .sorted(Comparator.comparing((AgentNavigationGraph.Region region) -> region.id != doorRegion)
                            .thenComparingInt(region -> Math.abs(region.pointAt(x).y - door.y)))
                    .toList()) {
                Point point = slot(region, x);
                if (point != null) {
                    return new Slot(region.id, region.width(), point);
                }
            }
            return null;
        }

        /**
         * The facility's approach as a BROWSE venue. A Free Market entrance gathers up to 3: the free slots 85 and 170 px
         * farther from the portal with no portal, door or ladder in between, and the approach's mirror image across it.
         */
        private Draft browseVenue(FacilityDraft facility) {
            Slot approach = facility.approach();
            List<Point> points = new ArrayList<>(List.of(approach.point()));
            if (facility.type() == FacilityType.FREE_MARKET) {
                int x = approach.point().x;
                int away = Integer.signum(x - facility.door().x);
                for (int candidate : new int[]{x + away * STREET_STEP_PX, x + away * 2 * STREET_STEP_PX,
                        2 * facility.door().x - x}) {
                    Slot slot = points.size() < 3 ? slotNear(facility.door(), approach.regionId(), candidate) : null;
                    if (slot != null && (Integer.signum(candidate - facility.door().x) != away
                            || !barrier(approach.point(), slot.point()))) {
                        points.add(slot.point());
                        occupied.add(slot.point());
                    }
                }
                points.sort(Comparator.comparingInt(point -> point.x));
            }
            Draft draft = venue(null, spots(points), approach.width(), points.size() >= 2
                    ? EnumSet.of(Affordance.BROWSE, Affordance.LINGER, Affordance.SOCIALIZE)
                    : EnumSet.of(Affordance.BROWSE, Affordance.LINGER));
            draft.label = facility.browseLabel();
            return draft;
        }

        /**
         * Keeps at most clamp(S / 1000, 14, 20) venues from the candidates: first, for each arrival and service no kept
         * venue covers yet, the best street or bench venue that does; then street and bench venues of 3 or more spots by
         * score, then pairs; then at most two ledge venues; all subject to fits(). The browse reserve fills what is left.
         */
        private List<Draft> trim(List<Draft> others, List<Draft> browse, int neverTrimmed, List<Focus> focuses) {
            int cap = Math.clamp(Math.round(totalLength / (double) LENGTH_PER_VENUE_PX), MIN_VENUES, MAX_VENUES);
            int keepOthers = cap - Math.max(neverTrimmed, Math.min(browse.size(), BROWSE_RESERVE));
            Comparator<Draft> preference = Comparator.comparing((Draft draft) -> draft.capacity() < 3)
                    .thenComparingInt(draft -> -draft.score)
                    .thenComparingInt(draft -> districtRank(draft.district))
                    .thenComparingInt(draft -> -draft.width)
                    .thenComparingInt(others::indexOf);
            List<Draft> social = others.stream().filter(draft -> !"ledges".equals(draft.word)).sorted(preference).toList();
            List<Draft> kept = new ArrayList<>();
            for (Focus focus : focuses) {
                if (kept.size() < keepOthers && kept.stream().noneMatch(draft -> covers(draft, focus.point()))) {
                    social.stream().filter(draft -> covers(draft, focus.point()) && fits(draft, kept))
                            .findFirst().ifPresent(kept::add);
                }
            }
            for (Draft draft : social) {
                if (kept.size() < keepOthers && !kept.contains(draft) && fits(draft, kept)) {
                    kept.add(draft);
                }
            }
            int ledges = 0;
            for (Draft draft : others.stream().filter(draft -> "ledges".equals(draft.word)).sorted(preference).toList()) {
                if (ledges < MAX_LEDGE_VENUES && kept.size() < keepOthers && fits(draft, kept)) {
                    kept.add(draft);
                    ledges++;
                }
            }
            long capped = others.stream().filter(draft -> !kept.contains(draft) && fits(draft, kept)).count();
            report.add(String.format("  kept %d of %d candidate venues (%d places beside the browse reserve); %d other candidates still fit at the end",
                    kept.size(), others.size(), keepOthers, capped));
            kept.sort(Comparator.comparing(Draft::median, X_THEN_Y));
            int keepBrowse = Math.min(browse.size(), Math.max(neverTrimmed, cap - kept.size()));
            if (keepBrowse < browse.size()) {
                report.add("  trimmed browse venue(s) " + browse.subList(keepBrowse, browse.size()).stream()
                        .map(draft -> draft.label).toList());
            }
            for (Focus focus : focuses) {
                if (kept.stream().noneMatch(draft -> covers(draft, focus.point()))) {
                    warnings.add(focus.what() + " has no street or bench venue within " + FOCUS_DX + " px");
                }
            }
            kept.addAll(browse.subList(0, keepBrowse));
            return kept;
        }

        private static boolean covers(Draft draft, Point focus) {
            return draft.distance(focus.x) <= FOCUS_DX && Math.abs(draft.median().y - focus.y) <= NEAR_DY;
        }

        /**
         * Seat venues always fit. Other venues keep 150 px apart on one level (within 60 px up or down) and never stack:
         * no span overlaps another's within 250 px up or down.
         */
        private static boolean fits(Draft draft, List<Draft> kept) {
            return "benches".equals(draft.word) || kept.stream().filter(other -> !"benches".equals(other.word))
                    .noneMatch(other -> {
                        int dy = Math.abs(draft.median().y - other.median().y);
                        return dy < LEVEL_DY ? draft.gap(other) < VENUE_GAP_PX : dy < VENUE_STACK_DY && draft.gap(other) < 0;
                    });
        }

        private void guarantee(List<Draft> venues, List<Draft> platforms) {
            Comparator<Draft> wider = Comparator.comparingInt((Draft draft) -> -draft.width);
            ensure(venues, Affordance.SHOW_OFF, 2, platforms.stream().filter(venue -> venue.capacity() >= 2)
                    .sorted(Comparator.comparing((Draft draft) -> draft.kind() != PlatformKind.MAIN).thenComparing(wider))
                    .toList());
            ensure(venues, Affordance.REST, 1, platforms.stream()
                    .sorted(Comparator.comparingInt((Draft draft) -> districtRank(draft.district)).thenComparing(wider))
                    .toList());
            ensure(venues, Affordance.SOCIALIZE, 2, platforms.stream().filter(venue -> venue.capacity() >= 2)
                    .sorted(wider).toList());
        }

        private void ensure(List<Draft> venues, Affordance affordance, int count, List<Draft> candidates) {
            long have = venues.stream().filter(venue -> venue.affordances.contains(affordance)).count();
            for (Draft candidate : candidates) {
                if (have < count && candidate.affordances.add(affordance)) {
                    have++;
                }
            }
            if (have < count) {
                report.add("  only " + have + " venue(s) can offer " + affordance);
            }
        }

        /**
         * The label of the POI nearest the venue's span (within 250 px across, 100 px up or down); if taken, the
         * next-nearest within 400 px; else the district label, which unique() numbers.
         */
        private String label(Draft venue, Set<String> taken) {
            List<Poi> near = pois.stream().filter(poi -> poi.label() != null
                            && Math.abs(poi.point().y - venue.median().y) <= NEAR_DY
                            && venue.distance(poi.point().x) <= LABEL_RADIUS_PX)
                    .sorted(Comparator.comparingInt((Poi poi) -> venue.distance(poi.point().x))
                            .thenComparingInt(poi -> Math.abs(poi.point().x - venue.median().x))
                            .thenComparingInt(poi -> poi.point().x))
                    .toList();
            if (near.isEmpty() || venue.distance(near.getFirst().point().x) > POI_RADIUS_PX) {
                return fallbackLabel(venue);
            }
            return near.stream().map(Poi::label).filter(label -> !taken.contains(label)).findFirst()
                    .orElse(fallbackLabel(venue));
        }

        private String fallbackLabel(Draft venue) {
            String side = List.of("west", "central", "east").get(third(venue.median().x));
            String district = venue.district.name().charAt(0) + venue.district.name().substring(1).toLowerCase(Locale.ROOT);
            return district + " " + side + " " + (venue.word == null ? "shopfront" : venue.word);
        }

        /**
         * Arrivals: FIELD portals (hidden ones only when no visible one exists), station doors and the spawn portal, all
         * on the walkable set. Weights: spawn 30, stations share 30, FIELD portals share 40, rescaled to 100 over the groups
         * present; the remainder goes to the first.
         */
        private Map<Portal, Integer> arrivalPortals() {
            List<Portal> onWalkable = data.portals().stream().filter(this::onWalkable)
                    .sorted(Comparator.comparingInt((Portal portal) -> portal.getPosition().x).thenComparingInt(Portal::getId))
                    .toList();
            List<Portal> field = onWalkable.stream().filter(portal -> kinds.get(portal) == Kind.FIELD).toList();
            if (field.stream().anyMatch(portal -> !hidden(portal))) {
                field = field.stream().filter(portal -> !hidden(portal)).toList();
            }
            List<Portal> stations = onWalkable.stream().filter(portal -> kinds.get(portal) == Kind.INTERIOR
                    && typeFromName(targetName(portal)) == FacilityType.TAXI).toList();
            List<Portal> spawn = onWalkable.stream().filter(portal -> portal.getId() == 0).toList();
            Set<String> names = new HashSet<>();
            List<List<Portal>> groups = Stream.of(field, stations, spawn)
                    .map(group -> group.stream().filter(portal -> names.add(portal.getName())).toList()).toList();
            int[] shares = {40, 30, 30};
            int total = IntStream.range(0, shares.length).filter(i -> !groups.get(i).isEmpty()).map(i -> shares[i]).sum();
            Map<Portal, Integer> weights = new LinkedHashMap<>();
            for (int i = 0; i < shares.length; i++) {
                for (Portal portal : groups.get(i)) {
                    weights.put(portal, Math.max(1, shares[i] * 100 / total / groups.get(i).size()));
                }
            }
            int sum = weights.values().stream().mapToInt(Integer::intValue).sum();
            if (!weights.isEmpty() && sum < 100) {
                weights.merge(weights.keySet().iterator().next(), 100 - sum, Integer::sum);
            }
            return weights;
        }

        private static boolean hidden(Portal portal) {
            return portal.getType() == Portal.TELEPORT_PORTAL || portal.getType() == HIDDEN_PORTAL;
        }

        /**
         * Citizen login points: free MAIN street slots in the LOWER district (MIDDLE ones only when fewer than 6, ledge
         * slots only when still fewer), 150 px inside the town and not beyond its edge exits, off every other spot; spread
         * along x by farthest-point sampling from the primary hotspot.
         */
        private List<Point> roamSpots(List<Slot> mainSlots, List<Slot> miniSlots, Point seed, List<Point> taken) {
            Predicate<Point> roomy = point -> district(point.y) != District.UPPER && inner(point.x) && !near(point, taken);
            List<Point> pool = new ArrayList<>(mainSlots.stream().map(Slot::point)
                    .filter(point -> district(point.y) == District.LOWER && roomy.test(point)).toList());
            if (pool.size() < MIN_ROAM_SPOTS) {
                mainSlots.stream().map(Slot::point).filter(point -> district(point.y) == District.MIDDLE && roomy.test(point))
                        .forEach(pool::add);
            }
            if (pool.size() < MIN_ROAM_SPOTS) {
                miniSlots.stream().map(Slot::point).filter(roomy).forEach(pool::add);
            }
            if (pool.size() < MIN_ROAM_SPOTS) {
                warnings.add("only " + pool.size() + " roam spot candidates");
            }
            if (pool.isEmpty()) {
                return List.of();
            }
            List<Point> chosen = new ArrayList<>();
            chosen.add(pool.stream().min(Comparator.comparingDouble(point -> point.distance(seed))).orElseThrow());
            while (chosen.size() < Math.min(MAX_ROAM_SPOTS, pool.size())) {
                Point best = null;
                int bestDistance = -1;
                for (Point point : pool) {
                    int nearest = chosen.stream().mapToInt(other -> Math.abs(other.x - point.x)).min().orElse(0);
                    if (!chosen.contains(point) && !near(point, chosen) && nearest > bestDistance) {
                        best = point;
                        bestDistance = nearest;
                    }
                }
                if (best == null) {
                    break;
                }
                chosen.add(best);
            }
            chosen.sort(X_THEN_Y);
            return chosen;
        }

        /**
         * Hotspot candidates are MAIN street or bench venues of 3 or more spots, 300 px inside the town (a tenth of its
         * width when that is less), ranked lowest district first, then by score. The primary is the first; the secondary is the one nearest
         * the Free Market, a party quest or taxi, else the primary arrival, at least 400 px from the primary, else the
         * next ranked that far away.
         */
        private List<Draft> hotspots(List<Draft> venues, List<FacilityDraft> facilities, Point arrival) {
            int edge = Math.min(HOTSPOT_EDGE_PX, (maxX - minX) / 10);
            List<Draft> candidates = venues.stream()
                    .filter(venue -> ("street".equals(venue.word) || "benches".equals(venue.word))
                            && venue.kind() == PlatformKind.MAIN && venue.capacity() >= 3
                            && venue.west() >= minX + edge && venue.east() <= maxX - edge)
                    .sorted(Comparator.comparingInt((Draft venue) -> districtRank(venue.district))
                            .thenComparingInt(venue -> -venue.score)
                            .thenComparingInt(venue -> -venue.capacity())
                            .thenComparingInt(venue -> venue.median().x))
                    .toList();
            if (candidates.isEmpty()) {
                return List.of();
            }
            Draft primary = candidates.getFirst();
            Point focus = facilities.stream().filter(facility -> facility.type() == FacilityType.FREE_MARKET).findFirst()
                    .or(() -> facilities.stream().filter(facility -> facility.type() == FacilityType.PARTY_QUEST
                            || facility.type() == FacilityType.TAXI).findFirst())
                    .map(facility -> facility.approach().point()).orElse(arrival);
            List<Draft> apart = candidates.stream().filter(venue -> venue.gap(primary) >= HOTSPOT_GAP_PX).toList();
            Draft secondary = apart.stream().filter(venue -> Math.abs(venue.median().y - focus.y) <= HOTSPOT_FOCUS_DY)
                    .min(Comparator.comparingInt((Draft venue) -> venue.distance(focus.x))
                            .thenComparingInt(venue -> venue.median().x))
                    .filter(venue -> venue.distance(focus.x) <= HOTSPOT_FOCUS_DX)
                    .orElse(apart.isEmpty() ? null : apart.getFirst());
            return secondary == null ? List.of(primary) : List.of(primary, secondary);
        }

        private static int districtRank(District district) {
            return switch (district) {
                case LOWER -> 0;
                case MIDDLE -> 1;
                default -> 2;
            };
        }

        private static long length(AgentNavigationGraph.Region region) {
            return region.segments.stream().mapToLong(segment -> segment.maxX - segment.minX).sum();
        }
    }

    private static boolean market(Portal portal) {
        return MARKET_SCRIPT.matcher(String.valueOf(portal.getScriptName())).matches();
    }

    /** The base, else base + separator + 2, 3, ...: the first not yet used, which is then marked used. */
    private static String unique(String base, String separator, Set<String> used) {
        String id = base;
        for (int suffix = 2; !used.add(id); suffix++) {
            id = base + separator + suffix;
        }
        return id;
    }

    private static FacilityType typeFromName(String name) {
        Set<String> words = Arrays.stream(name.toLowerCase(Locale.ROOT).split("[^a-z]+")).collect(Collectors.toSet());
        if (words.contains("weapon")) {
            return FacilityType.WEAPON_SHOP;
        }
        if (words.contains("armor") || words.contains("armour")) {
            return FacilityType.ARMOR_SHOP;
        }
        if (words.contains("potion") || words.contains("pharmacy") || words.contains("drug")) {
            return FacilityType.POTION_SHOP;
        }
        if (words.stream().anyMatch(Set.of("department", "general", "store", "shop", "market")::contains)) {
            return FacilityType.GENERAL_SHOP;
        }
        if (words.stream().anyMatch(word -> word.startsWith("ticket")
                || Set.of("station", "dock", "harbor", "ship", "airport", "wharf", "subway").contains(word))) {
            return FacilityType.TAXI;
        }
        return FacilityType.OTHER;
    }

    /** The service an NPC offers, from its String.wz func (or a 'taxi' name); global NPCs count only as taxi or PQ. */
    private static FacilityType facilityType(TownMapData.Npc npc) {
        FacilityType type = typeFromFunc(npc.func());
        if (type == null && npc.name().toLowerCase(Locale.ROOT).contains("taxi")) {
            type = FacilityType.TAXI;
        }
        return npc.global() && type != FacilityType.TAXI && type != FacilityType.PARTY_QUEST ? null : type;
    }

    private static FacilityType typeFromFunc(String func) {
        String text = func.toLowerCase(Locale.ROOT);
        if (has(text, "storage", "warehouse")) {
            return FacilityType.STORAGE;
        }
        if (has(text, "tour guide", "transportation", "ambassador", "ticket", "station", "crew", "taxi")) {
            return FacilityType.TAXI;
        }
        if (text.contains("weapon") && has(text, "seller", "merchant")) {
            return FacilityType.WEAPON_SHOP;
        }
        if (text.contains("armor") && has(text, "seller", "merchant")) {
            return FacilityType.ARMOR_SHOP;
        }
        if (has(text, "grocer", "potion seller", "chef", "cook")) {
            return FacilityType.POTION_SHOP;
        }
        if (has(text, "general dealer", "local special", "merchant", "seller")) {
            return FacilityType.GENERAL_SHOP;
        }
        if (text.contains("monster carnival")) {
            return FacilityType.PARTY_QUEST;
        }
        if (has(text, "chief knight", "instructor")) {
            return FacilityType.JOB_INSTRUCTOR;
        }
        return null;
    }

    private static boolean has(String text, String... keywords) {
        return Arrays.stream(keywords).anyMatch(text::contains);
    }

    /** Browse reserve order: Free Market, taxi, storage, party quest, shops, the rest. */
    private static int browseRank(FacilityType type) {
        return switch (type) {
            case FREE_MARKET -> 0;
            case TAXI -> 1;
            case STORAGE -> 2;
            case PARTY_QUEST -> 3;
            case GENERAL_SHOP, WEAPON_SHOP, ARMOR_SHOP, POTION_SHOP -> 4;
            default -> 5;
        };
    }

    private static List<String> services(FacilityType type) {
        return switch (type) {
            case GENERAL_SHOP, WEAPON_SHOP, ARMOR_SHOP, POTION_SHOP -> List.of("browse", "buy", "sell");
            case TAXI -> List.of("travel");
            case STORAGE -> List.of("storage");
            case PARTY_QUEST -> List.of("party-quest-recruitment");
            case FREE_MARKET -> List.of("free-market");
            case JOB_INSTRUCTOR -> List.of("job-services");
            default -> List.of("enter");
        };
    }
}
