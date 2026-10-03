package server.living.townlife;

import server.agents.capabilities.movement.AgentGroundingService;
import server.agents.capabilities.movement.AgentMovementPhysicsConfig;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.capabilities.navigation.AgentPortalApproachService;
import server.agents.capabilities.townlife.AgentTownLifeProfile;
import server.agents.capabilities.townlife.AgentTownLifeProfileValidator;
import server.agents.capabilities.townlife.AgentTownLifeState;
import server.maps.Foothold;
import server.maps.Portal;

import java.awt.Point;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Audits a TownLife profile's points against the real map: grounding, mutual reachability, seats and NPCs. */
public final class TownLifeGeometryCheck {
    static final int MAX_ABOVE_SURFACE_PX = 12;
    static final int MAX_SEAT_DRIFT_PX = 12;

    private TownLifeGeometryCheck() {
    }

    public record Result(List<String> errors, List<String> warnings) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    /**
     * Where a point stands: whether the runtime grounding finds a foothold, its walkable region (-1 if none) and how
     * far the runtime surface lies below the point (negative = buried).
     */
    record Ground(boolean found, int regionId, int above) {
        /** On the surface or at most 12 px above it: the window the runtime arrives in. */
        boolean ok() {
            return found && above >= 0 && above <= MAX_ABOVE_SURFACE_PX;
        }
    }

    public static Result check(AgentTownLifeProfile profile, TownMapData data) {
        List<String> errors = new ArrayList<>(AgentTownLifeProfileValidator.validate(profile).errors());
        List<String> warnings = new ArrayList<>();
        int anchor = -1;
        for (AgentTownLifeProfile.ArrivalPortal arrival : profile.arrivalPortals()) {
            Portal portal = data.map().getPortal(arrival.name());
            if (portal == null) {
                errors.add("arrival portal " + arrival.name() + " is not in the map");
                continue;
            }
            int region = portalRegion(data, portal);
            if (region < 0) {
                errors.add("arrival portal " + arrival.name() + " resolves to no walkable region");
            } else if (anchor < 0) {
                anchor = region;
            } else if (!mutuallyReachable(data.graph(), anchor, region)) {
                errors.add("arrival portal " + arrival.name() + " (region " + region
                        + ") is not mutually reachable with arrival region " + anchor);
            }
        }
        if (anchor < 0) {
            errors.add("no arrival portal resolves to a walkable region; reachability is unchecked");
        }
        Map<Point, String> points = new LinkedHashMap<>();
        profile.restSpots().forEach(spot -> points.putIfAbsent(spot.point(), "rest spot"));
        for (AgentTownLifeProfile.Venue venue : profile.venues()) {
            venue.spots().forEach(spot -> points.putIfAbsent(spot.point(), "venue " + venue.id()));
        }
        profile.roamFallbackSpots().forEach(spot -> points.putIfAbsent(spot.point(), "roam spot"));
        for (AgentTownLifeProfile.Facility facility : profile.facilities()) {
            for (AgentTownLifeProfile.PointSpec approach : facility.approachPoints()) {
                points.putIfAbsent(approach.point(), "facility " + facility.id() + " approach");
                if (!profile.allowsOccupancy(approach.point())) {
                    warnings.add("facility " + facility.id() + " approach " + text(approach.point())
                            + " lies in an excluding traffic zone");
                }
            }
        }
        for (AgentTownLifeProfile.NpcSpot spot : profile.npcSpots()) {
            TownMapData.Npc npc = data.npc(spot.npcId());
            if (npc == null) {
                errors.add("npc spot " + spot.npcId() + " is not in the map");
                continue;
            }
            if (Math.abs(npc.y() - npc.cy()) > MAX_ABOVE_SURFACE_PX) {
                warnings.add("npc " + spot.npcId() + " life y " + npc.y() + " is " + Math.abs(npc.y() - npc.cy())
                        + " px from its cy " + npc.cy());
            }
            Point target = new Point(npc.x() + spot.offsetX(), npc.y());
            points.putIfAbsent(target, "npc " + spot.npcId() + " target");
            if (!profile.allowsOccupancy(target)) {
                warnings.add("npc " + spot.npcId() + " target " + text(target)
                        + " lies in an excluding traffic zone and is never used");
            }
        }
        for (Map.Entry<Point, String> entry : points.entrySet()) {
            Ground ground = ground(data, entry.getKey());
            String where = entry.getValue() + " at " + text(entry.getKey());
            if (!ground.found()) {
                errors.add(where + " has no foothold below it or within "
                        + AgentMovementPhysicsConfig.configuredMaxSlopeUp() + " px above it");
            } else if (ground.above() > MAX_ABOVE_SURFACE_PX) {
                errors.add(where + " floats " + ground.above() + " px above the surface");
            } else if (ground.above() < -MAX_ABOVE_SURFACE_PX) {
                errors.add(where + " is buried " + -ground.above() + " px below the surface");
            } else {
                if (ground.above() < 0) {
                    warnings.add(where + " is buried " + -ground.above() + " px below the surface");
                }
                if (anchor >= 0 && !mutuallyReachable(data.graph(), anchor, ground.regionId())) {
                    errors.add(where + " (region " + ground.regionId() + ") is not mutually reachable with arrival region "
                            + anchor);
                }
            }
        }
        Map<AgentTownLifeProfile.RestSpot, String> seats = new LinkedHashMap<>();
        profile.restSpots().forEach(spot -> seats.putIfAbsent(spot, "rest spot"));
        for (AgentTownLifeProfile.Venue venue : profile.venues()) {
            venue.spots().forEach(spot -> seats.putIfAbsent(
                    new AgentTownLifeProfile.RestSpot(spot.x(), spot.y(), spot.seatId()), "venue " + venue.id()));
        }
        seats.forEach((spot, where) -> checkSeat(data, where, spot.seatId(), spot.point(), errors));
        for (AgentTownLifeProfile.Venue venue : profile.venues()) {
            List<Integer> ys = venue.spots().stream().map(AgentTownLifeProfile.VenueSpot::y).sorted().toList();
            AgentTownLifeState.District median = profile.classify(new Point(0, ys.get(ys.size() / 2)));
            if (venue.district() != AgentTownLifeState.District.ANY && venue.district() != median) {
                warnings.add("venue " + venue.id() + " is authored " + venue.district() + " but its median spot is "
                        + median);
            }
        }
        for (AgentTownLifeProfile.PointSpec roam : profile.roamFallbackSpots()) {
            if (!profile.allowsOccupancy(roam.point())) {
                warnings.add("roam spot " + text(roam.point()) + " lies in an excluding traffic zone");
            }
        }
        return new Result(List.copyOf(errors), List.copyOf(warnings));
    }

    static Ground ground(TownMapData data, Point point) {
        Foothold foothold = AgentGroundingService.findGroundFoothold(data.map(), point);
        Point surface = AgentGroundingService.findGroundPoint(data.map(), point);
        if (foothold == null || surface == null) {
            return new Ground(false, -1, 0);
        }
        return new Ground(true, data.graph().regionIdByFootholdId.getOrDefault(foothold.getId(), -1),
                surface.y - point.y);
    }

    static int portalRegion(TownMapData data, Portal portal) {
        Point target = AgentPortalApproachService.navigableTarget(data.map(), portal);
        return target == null ? -1 : data.graph().findRegionId(data.map(), target);
    }

    static boolean mutuallyReachable(AgentNavigationGraph graph, int first, int second) {
        return graph.canReach(first, second) && graph.canReach(second, first);
    }

    static String text(Point point) {
        return point.x + "," + point.y;
    }

    private static void checkSeat(TownMapData data, String where, int seatId, Point point, List<String> errors) {
        if (seatId < 0) {
            return;
        }
        TownMapData.Seat seat = data.seats().stream().filter(candidate -> candidate.id() == seatId)
                .findFirst().orElse(null);
        if (seatId >= data.seats().size() || seat == null) {
            errors.add(where + " seat " + seatId + " does not exist (the map has " + data.seats().size() + " seats)");
        } else if (seat.point().distance(point) > MAX_SEAT_DRIFT_PX) {
            errors.add(where + " seat " + seatId + " at " + text(point) + " is " + (int) seat.point().distance(point)
                    + " px from its WZ seat at " + text(seat.point()));
        }
    }
}
