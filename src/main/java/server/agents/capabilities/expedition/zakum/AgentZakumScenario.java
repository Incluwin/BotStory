package server.agents.capabilities.expedition.zakum;

import client.Character;
import client.status.MonsterStatus;
import constants.id.MobId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.combat.data.AgentMobHitboxProvider;
import server.agents.capabilities.expedition.AgentBossFight;
import server.agents.capabilities.expedition.boss.BossTargetRules;
import server.agents.capabilities.mobcontrol.AgentBossScope;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.life.Monster;
import server.maps.FootholdTree;
import server.maps.MapleMap;
import server.maps.Reactor;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Zakum fight for adopted companions (P8). Each tick it reads the live monsters on the altar, names the phase
 * ({@link BossTargetRules#phase}) and drives every fighter:
 * <ul>
 *   <li>the battle walls are the altar's foothold bounds;</li>
 *   <li>before the summon, members stand on a line left of the altar reactor, PRE_SUMMON_OFFSET_PX from it and
 *       PRE_SUMMON_SPACING_PX apart, inside the walls (a line too long for the floor starts over at its first spot),
 *       so the floor by the altar stays free for the Eye;</li>
 *   <li>while parts live, each member grinds its first target tier and falls back to the rest
 *       ({@link BossTargetRules#tiers}); a melee member whose first tier is the upper arms grinds from the
 *       PLATFORM_ANCHORS platform nearest those arms;</li>
 *   <li>with nothing to hit (no part a human drives, or only parts immune to the member) a member holds, and at the
 *       clear every member stops.</li>
 * </ul>
 * One line with the members' vitals, their choices and the parts is logged every VITALS_LOG_INTERVAL_MS.
 */
public final class AgentZakumScenario implements AgentBossFight {
    private static final Logger log = LoggerFactory.getLogger(AgentZakumScenario.class);
    private static volatile Anchors anchors = new Anchors("", List.of());

    private final Ports ports;
    private final Map<Integer, Integer> ordinals = new ConcurrentHashMap<>();
    private final Map<Integer, String> choices = new ConcurrentHashMap<>();
    private BossTargetRules.Phase phase;
    private boolean summoned;
    private long nextVitalsLogAtMs;

    AgentZakumScenario(Ports ports) {
        this.ports = ports;
    }

    /** A fresh fight for one boss run session. */
    public static AgentZakumScenario forCompanions() {
        return new AgentZakumScenario(Ports.LIVE);
    }

    @Override
    public void adopt(int characterId, int ordinal) {
        ordinals.put(characterId, ordinal);
    }

    @Override
    public void tick(List<Character> fighters, EventInstanceManager event, long nowMs) {
        MapleMap map = fighters.isEmpty() ? null : fighters.getFirst().getMap();
        if (map == null) {
            return;
        }
        List<Monster> monsters = ports.monsters(map).stream().filter(Monster::isAlive).toList();
        List<BossTargetRules.Part> parts = monsters.stream().map(this::part).toList();
        if (parts.stream().anyMatch(part -> part.role() != BossTargetRules.Role.ADD)) {
            summoned = true;
        }
        BossTargetRules.Phase next = BossTargetRules.phase(parts, summoned, event != null && event.isEventCleared());
        if (next != phase) {
            phase = next;
            log.info("Zakum combat phase={} members={} parts={}", phase, fighters.size(), describe(monsters));
        }
        PrimitiveCapabilityGateway gateway = ports.gateway();
        FootholdTree footholds = map.getFootholds();
        Point altar = next == BossTargetRules.Phase.WAITING_FOR_SUMMON ? altar(gateway, fighters.getFirst()) : null;
        for (Character member : fighters) {
            AgentRuntimeEntry entry = ports.entry(member.getId());
            if (entry == null) {
                continue;
            }
            if (footholds != null) {
                gateway.setHorizontalBoundary(entry, AgentZakumDefinition.BATTLE_MAP_ID,
                        footholds.getMinDropX(), footholds.getMaxDropX());
            }
            switch (next) {
                case WAITING_FOR_SUMMON -> lineUp(gateway, entry, member, map, altar, footholds);
                case DONE -> hold(gateway, entry, member, "done");
                default -> fight(gateway, entry, member, map, monsters, parts);
            }
        }
        if (nowMs >= nextVitalsLogAtMs) {
            nextVitalsLogAtMs = nowMs + vitalsLogIntervalMs();
            log.info("Zakum party vitals phase={} {} parts={}", phase,
                    fighters.stream().map(this::vitals).toList(), describe(monsters));
        }
    }

    @Override
    public void end(EventInstanceManager event) {
        ordinals.clear();
        choices.clear();
    }

    /** Before the summon: walk to this member's spot on the line left of the altar, then wait there. */
    private void lineUp(PrimitiveCapabilityGateway gateway, AgentRuntimeEntry entry, Character member, MapleMap map,
                        Point altar, FootholdTree footholds) {
        if (altar == null || footholds == null) {
            hold(gateway, entry, member, "no-altar");
            return;
        }
        Point spot = gateway.groundPoint(map, preSummonSpot(altar, ordinals.getOrDefault(member.getId(), 0),
                preSummonOffsetPx(), preSummonSpacingPx(), footholds.getMinDropX(), footholds.getMaxDropX()));
        if (spot == null) {
            hold(gateway, entry, member, "no-spot");
            return;
        }
        Point position = member.getPosition();
        long reachPx = Math.max(1, preSummonSpacingPx() / 2);
        if (position != null && position.distanceSq(spot) <= reachPx * reachPx) {
            hold(gateway, entry, member, "lined-up");
        } else {
            gateway.navigate(entry, spot, true);
            choices.put(member.getId(), "line-up@(" + spot.x + ',' + spot.y + ')');
        }
    }

    /** While parts live: grind the first target tier and fall back to the rest, or hold with nothing to hit. */
    private void fight(PrimitiveCapabilityGateway gateway, AgentRuntimeEntry entry, Character member, MapleMap map,
                       List<Monster> monsters, List<BossTargetRules.Part> parts) {
        boolean magic = member.getJob() != null && member.getJob().getJobNiche() == 2;
        boolean melee = !magic && !gateway.rangedWeapon(member);
        List<BossTargetRules.Tier> tiers = BossTargetRules.tiers(parts, new BossTargetRules.Member(magic, melee),
                floorReachPx(), addClearThreshold());
        if (tiers.isEmpty()) {
            hold(gateway, entry, member, "nothing");
            return;
        }
        BossTargetRules.Tier first = tiers.getFirst();
        Set<Integer> fallback = BossTargetRules.fallback(tiers);
        if (melee && first.kind() == BossTargetRules.Kind.UPPER_ARMS) {
            List<Rectangle> boxes = new ArrayList<>();
            for (Monster monster : monsters) {
                Rectangle box = first.mobIds().contains(monster.getId()) ? ports.body(monster) : null;
                if (box != null) {
                    boxes.add(box);
                }
            }
            Point authored = nearestAnchor(platformAnchors(), boxes);
            Point anchor = authored == null ? null : gateway.groundPoint(map, authored);
            if (anchor != null) {
                gateway.grindFromAnchor(entry, anchor, first.mobIds(), fallback);
                choices.put(member.getId(), first.kind() + "@(" + anchor.x + ',' + anchor.y + ')');
                return;
            }
        }
        gateway.grind(entry, first.mobIds(), fallback);
        choices.put(member.getId(), first.kind().name());
    }

    private void hold(PrimitiveCapabilityGateway gateway, AgentRuntimeEntry entry, Character member, String why) {
        gateway.stop(entry);
        choices.put(member.getId(), "hold:" + why);
    }

    private BossTargetRules.Part part(Monster monster) {
        int mobId = monster.getId();
        BossTargetRules.Role role = MobId.isZakumArm(mobId) ? BossTargetRules.Role.ARM
                : mobId >= MobId.ZAKUM_1 && mobId <= MobId.ZAKUM_3 ? BossTargetRules.Role.BODY
                : BossTargetRules.Role.ADD;
        Rectangle box = role == BossTargetRules.Role.ARM ? ports.body(monster) : null;
        Point origin = monster.getPosition();
        int reachPx = box == null || origin == null ? 0 : origin.y - (box.y + box.height);
        return new BossTargetRules.Part(mobId, role, monster.isFake(),
                role != BossTargetRules.Role.ADD && ports.engaged(monster),
                monster.isBuffed(MonsterStatus.WEAPON_IMMUNITY), monster.isBuffed(MonsterStatus.MAGIC_IMMUNITY),
                reachPx);
    }

    private static Point altar(PrimitiveCapabilityGateway gateway, Character member) {
        Collection<Reactor> reactors = gateway.reactors(member);
        if (reactors == null) {
            return null;
        }
        return reactors.stream()
                .filter(reactor -> reactor != null && reactor.getId() == AgentZakumDefinition.ALTAR_REACTOR_ID)
                .map(Reactor::getPosition)
                .filter(java.util.Objects::nonNull)
                .map(Point::new)
                .findFirst().orElse(null);
    }

    /**
     * The spot of the member with this ordinal on the line left of the altar: {@code offsetPx} left of it, then
     * {@code spacingPx} further left per ordinal, inside [minX, maxX]. A line longer than the floor starts over at
     * its first spot, so members past the floor share spots rather than crowd the wall.
     */
    static Point preSummonSpot(Point altar, int ordinal, int offsetPx, int spacingPx, int minX, int maxX) {
        int firstX = Math.max(minX, Math.min(maxX, altar.x - offsetPx));
        int slots = spacingPx <= 0 ? 1 : (firstX - minX) / spacingPx + 1;
        return new Point(firstX - Math.floorMod(ordinal, slots) * Math.max(0, spacingPx), altar.y);
    }

    /** The anchor closest to any of the boxes, the first such anchor on a tie; null with no anchor or no box. */
    static Point nearestAnchor(List<Point> candidates, List<Rectangle> boxes) {
        Point best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (Point anchor : candidates) {
            for (Rectangle box : boxes) {
                double dx = Math.max(0, Math.max(box.x - anchor.x, anchor.x - (box.x + box.width)));
                double dy = Math.max(0, Math.max(box.y - anchor.y, anchor.y - (box.y + box.height)));
                double distanceSq = dx * dx + dy * dy;
                if (distanceSq < bestDistanceSq) {
                    bestDistanceSq = distanceSq;
                    best = anchor;
                }
            }
        }
        return best == null ? null : new Point(best);
    }

    /** PLATFORM_ANCHORS as points, parsed once per value: "none", or "x,y" points separated by "|". */
    static List<Point> platformAnchors() {
        String raw = config.AgentTuning.stringValue(
                "server.agents.capabilities.expedition.zakum.AgentZakumScenario.PLATFORM_ANCHORS");
        Anchors current = anchors;
        if (!current.raw().equals(raw)) {
            current = new Anchors(raw, parseAnchors(raw));
            anchors = current;
        }
        return current.points();
    }

    static List<Point> parseAnchors(String raw) {
        if (raw == null || raw.isBlank() || "none".equalsIgnoreCase(raw.trim())) {
            return List.of();
        }
        List<Point> points = new ArrayList<>();
        for (String pair : raw.trim().split("\\|")) {
            String[] xy = pair.split(",");
            points.add(new Point(Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim())));
        }
        return List.copyOf(points);
    }

    private String vitals(Character member) {
        Point position = member.getPosition();
        return member.getName() + '=' + member.getHp() + '/' + member.getCurrentMaxHp() + "hp,"
                + member.getMp() + '/' + member.getCurrentMaxMp() + "mp@"
                + (position == null ? "(?,?)" : "(" + position.x + ',' + position.y + ')')
                + ' ' + choices.getOrDefault(member.getId(), "none");
    }

    private List<String> describe(List<Monster> monsters) {
        return monsters.stream()
                .map(monster -> monster.getId() + (monster.isFake() ? "(fake)" : "")
                        + (ports.engaged(monster) ? "" : "(no-human)")
                        + (monster.isBuffed(MonsterStatus.WEAPON_IMMUNITY) ? "(weapon-immune)" : "")
                        + (monster.isBuffed(MonsterStatus.MAGIC_IMMUNITY) ? "(magic-immune)" : "")
                        + '=' + monster.getHp() + '/' + monster.getMaxHp())
                .toList();
    }

    private record Anchors(String raw, List<Point> points) {
    }

    /** What the fight reads and drives; LIVE is the server, tests pass their own. */
    interface Ports {
        PrimitiveCapabilityGateway gateway();

        AgentRuntimeEntry entry(int characterId);

        List<Monster> monsters(MapleMap map);

        /** A human really drives the part (AgentBossScope.engaged). */
        boolean engaged(Monster part);

        /** The part's body box in map coordinates (its WZ stand frame), or null. */
        Rectangle body(Monster part);

        Ports LIVE = new Ports() {
            @Override
            public PrimitiveCapabilityGateway gateway() {
                return AgentPrimitiveCapabilityGatewayRuntime.gateway();
            }

            @Override
            public AgentRuntimeEntry entry(int characterId) {
                return AgentRuntimeRegistry.findByAgentCharacterId(characterId);
            }

            @Override
            public List<Monster> monsters(MapleMap map) {
                return AgentMapPerception.monsters(map);
            }

            @Override
            public boolean engaged(Monster part) {
                return AgentBossScope.engaged(part);
            }

            @Override
            public Rectangle body(Monster part) {
                return AgentMobHitboxProvider.getInstance().getMobBounds(part);
            }
        };
    }

    // ---- tuning (agent-engine.yaml), read at call time ----------------------------------------

    static long vitalsLogIntervalMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.expedition.zakum.AgentZakumScenario.VITALS_LOG_INTERVAL_MS");
    }

    static int preSummonOffsetPx() {
        return config.AgentTuning.intValue(
                "server.agents.capabilities.expedition.zakum.AgentZakumScenario.PRE_SUMMON_OFFSET_PX");
    }

    static int preSummonSpacingPx() {
        return config.AgentTuning.intValue(
                "server.agents.capabilities.expedition.zakum.AgentZakumScenario.PRE_SUMMON_SPACING_PX");
    }

    static int floorReachPx() {
        return config.AgentTuning.intValue(
                "server.agents.capabilities.expedition.zakum.AgentZakumScenario.FLOOR_REACH_PX");
    }

    static int addClearThreshold() {
        return config.AgentTuning.intValue(
                "server.agents.capabilities.expedition.zakum.AgentZakumScenario.ADD_CLEAR_THRESHOLD");
    }
}
