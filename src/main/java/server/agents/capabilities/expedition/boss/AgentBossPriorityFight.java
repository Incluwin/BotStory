package server.agents.capabilities.expedition.boss;

import client.Character;
import client.Disease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.combat.AgentCombatBuffRuntime;
import server.agents.capabilities.combat.AgentCombatConfig;
import server.agents.capabilities.combat.AgentMobTouchPolicy;
import server.agents.capabilities.combat.data.AgentDefenseDataProvider;
import server.agents.capabilities.combat.data.AgentMobHitboxProvider;
import server.agents.capabilities.expedition.AgentBossFight;
import server.agents.capabilities.mobcontrol.AgentBossScope;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.life.Monster;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The fight of a boss that has no driver of its own (P12 G3; P14's party bosses and P19's Showa use it). Each tick
 * every fighter grinds the first live part of its boss's {@code targetPriority} in the boss rules catalog and falls
 * back to every other live monster on its map ({@link AgentBossRules#preferences}). A part a human must drive
 * ({@link AgentBossScope#guards}) counts only while one does ({@link AgentBossScope#engaged}), as in the Zakum fight.
 * With nothing to hit, or after the clear, a fighter holds.
 *
 * <p>P18: a melee fighter reads the boss's {@code meleePriority} when it names one (Horntail's legs and tail). On a
 * stage map before the battle map (Horntail's pre-head maps) that holds no live monster but never-targets, a fighter
 * walks to the boss's stage portal and takes it, at most every STAGE_PORTAL_EVERY_MS; the stock portal script lets it
 * through once that map's head fell.</p>
 *
 * <p>P20: while a part with {@code keepOut} boxes lives (Solomon's deadly box to his left, Rex's to his right), a
 * ranged or magic fighter keeps to the band of x past those boxes, KEEP_OUT_MARGIN_PX inside them. Outside the band it
 * walks in first; inside it, a horizontal boundary holds it there while it fights. The boundary goes once no such
 * part lives.</p>
 *
 * <p>2026-10-14: every fighter also keeps out of a deadly touch area on its floor ({@link #touchBand}: Horntail's
 * Tails and Legs), the same way. A boss with focus steps ({@link AgentBossRules.Focus}) is fought step by step: the
 * live parts of the first step with one, from the step's ledges when it names any, keeping to the step's side of a
 * deadly touch area.</p>
 */
public final class AgentBossPriorityFight implements AgentBossFight {
    private static final Logger log = LoggerFactory.getLogger(AgentBossPriorityFight.class);
    private final String key;
    private final Ports ports;
    /** Fighter id -> when it last took the stage portal. */
    private final Map<Integer, Long> portalTries = new HashMap<>();
    /** Fighter id -> the band its horizontal boundary holds now, and on which map: {minX, maxX, mapId}. */
    private final Map<Integer, int[]> bands = new HashMap<>();
    /** Fighter id -> how long it stood sealed, and how long disabled (stun, seduce, confuse), since the last status. */
    private final Map<Integer, long[]> afflicted = new HashMap<>();
    /** When the last tick ran, and when the last status line went out (-1: none yet). */
    private long lastTickMs = -1;
    private long lastStatusMs = -1;

    AgentBossPriorityFight(String key, Ports ports) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("a priority fight needs a boss key");
        }
        this.key = key;
        this.ports = ports;
    }

    /** A fresh fight per session for boss {@code key}, as a run definition takes it. */
    public static Supplier<AgentBossFight> forKey(String key) {
        return () -> new AgentBossPriorityFight(key, Ports.LIVE);
    }

    @Override
    public void adopt(int characterId, int ordinal) {
    }

    @Override
    public void tick(List<Character> fighters, EventInstanceManager event, long nowMs) {
        if (fighters.isEmpty()) {
            return;
        }
        boolean cleared = event != null && event.isEventCleared();
        AgentBossRules.Boss rules = AgentBossRules.of(key);
        status(fighters, rules, nowMs);
        PrimitiveCapabilityGateway gateway = ports.gateway();
        Map<MapleMap, Set<Integer>> liveByMap = new HashMap<>();
        for (Character member : fighters) {
            AgentRuntimeEntry entry = ports.entry(member.getId());
            MapleMap map = member.getMap();
            if (entry == null || map == null) {
                continue;
            }
            if (!cleared && rules != null && rules.stageMaps().contains(map.getId()) && stageDone(map, rules)) {
                release(gateway, entry, member.getId());
                advance(gateway, entry, member, rules.stagePortal(), nowMs);
                continue;
            }
            boolean melee = ports.melee(member);
            Set<Integer> live = cleared ? Set.of() : liveByMap.computeIfAbsent(map, this::liveIds);
            AgentBossRules.Focus step = cleared ? null : AgentBossRules.focusStep(rules, live);
            if (cleared || rules == null) {
                release(gateway, entry, member.getId());
            } else if (keepOut(gateway, entry, member, map, band(map, member, rules, melee, step))) {
                continue;
            }
            if (step != null) {
                focus(gateway, entry, member, map, rules, step, live, ordinal(fighters, member, map));
                continue;
            }
            AgentBossRules.Preferences preferences = cleared ? null
                    : AgentBossRules.preferences(rules, live, melee);
            if (preferences == null || preferences.isEmpty()) {
                gateway.stop(entry);
            } else {
                gateway.grind(entry, preferences.preferred(), preferences.fallback());
            }
        }
    }

    @Override
    public void end(EventInstanceManager event) {
    }

    /**
     * 2026-10-14 (P18, run 5 follow-up): once every STATUS_EVERY_MS, one line of the fight as it stands: each boss part's
     * HP (the parts its boss rules name, holder too) and each fighter's map, place, HP, buff count, and how long it stood sealed and disabled since the last line,
     * so a slow stage shows why (Horntail's Hands held the run at 0.6% a minute with nothing in the log to say why).
     * Sealed and disabled time is summed over ticks (at most two seconds a tick). Memory only, besides the log.
     */
    private void status(List<Character> fighters, AgentBossRules.Boss rules, long nowMs) {
        long step = lastTickMs < 0 ? 0 : Math.max(0, Math.min(2_000, nowMs - lastTickMs));
        lastTickMs = nowMs;
        for (Character member : fighters) {
            long[] times = afflicted.computeIfAbsent(member.getId(), id -> new long[2]);
            if (member.hasDisease(Disease.SEAL)) {
                times[0] += step;
            }
            if (member.hasDisease(Disease.STUN) || member.hasDisease(Disease.SEDUCE)
                    || member.hasDisease(Disease.CONFUSE)) {
                times[1] += step;
            }
        }
        if (lastStatusMs < 0) {
            lastStatusMs = nowMs;
            return;
        }
        long every = statusEveryMs();
        if (every <= 0 || nowMs - lastStatusMs < every) {
            return;
        }
        lastStatusMs = nowMs;
        if (!log.isInfoEnabled()) {
            afflicted.clear();
            return;
        }
        Set<MapleMap> maps = new LinkedHashSet<>();
        for (Character member : fighters) {
            if (member.getMap() != null) {
                maps.add(member.getMap());
            }
        }
        Set<Integer> named = new LinkedHashSet<>();
        if (rules != null) {
            named.addAll(rules.targetPriority());
            named.addAll(rules.meleePriority());
            named.addAll(rules.neverTarget());
        }
        StringBuilder parts = new StringBuilder();
        for (MapleMap map : maps) {
            for (Monster monster : ports.monsters(map)) {
                if (monster.isAlive() && named.contains(monster.getId())) {
                    parts.append(parts.length() == 0 ? "" : " ").append(monster.getId()).append(':')
                            .append(percent(monster.getHp(), monster.getMaxHp())).append('%');
                }
            }
        }
        StringBuilder members = new StringBuilder();
        for (Character member : fighters) {
            Point at = member.getPosition();
            long[] times = afflicted.getOrDefault(member.getId(), new long[2]);
            List<?> buffs = member.getAllBuffs();
            members.append(members.length() == 0 ? "" : "; ").append(member.getName()).append(' ')
                    .append(member.getMapId()).append(at == null ? "" : "(" + at.x + "," + at.y + ")")
                    .append(" hp=").append(percent(member.getHp(), member.getCurrentMaxHp())).append('%')
                    .append(" buffs=").append(buffs == null ? 0 : buffs.size())
                    .append(" sealed=").append(times[0] / 1000).append('s')
                    .append(" disabled=").append(times[1] / 1000).append('s');
        }
        afflicted.clear();
        AgentBossRules.Focus focus = rules == null || maps.size() != 1 ? null
                : AgentBossRules.focusStep(rules, liveIds(maps.iterator().next()));
        log.info("Boss fight status: boss={} step={} parts=[{}] members=[{}]", key,
                focus == null ? "-" : focus.mobIds(), parts, members);
    }

    private static long percent(long value, long maximum) {
        return maximum <= 0 ? 0 : Math.max(0, Math.min(100, value * 100 / maximum));
    }

    static long statusEveryMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.expedition.boss.AgentBossPriorityFight.STATUS_EVERY_MS");
    }

    /**
     * Focus order (2026-10-14): the member hits the live parts of {@code step}. When the step names ledges for them it
     * grinds from one, spread by {@code ordinal} over the ledges that reach a live part (Horntail's Wings and Hands, then
     * its Heads, out of reach from the floor), preferring the parts its ledge reaches; otherwise it grinds the step's
     * parts. The boss's live adds are the fallback either way.
     */
    private void focus(PrimitiveCapabilityGateway gateway, AgentRuntimeEntry entry, Character member, MapleMap map,
                       AgentBossRules.Boss rules, AgentBossRules.Focus step, Set<Integer> live, int ordinal) {
        AgentBossRules.Preferences preferences = AgentBossRules.focusPreferences(rules, step, live);
        List<AgentBossRules.Anchor> usable = new ArrayList<>();
        for (AgentBossRules.Anchor anchor : step.anchors()) {
            if (anchor.mobIds().stream().anyMatch(preferences.preferred()::contains)) {
                usable.add(anchor);
            }
        }
        if (!usable.isEmpty()) {
            AgentBossRules.Anchor anchor = usable.get(Math.floorMod(ordinal, usable.size()));
            Point ground = gateway.groundPoint(map, new Point(anchor.x(), anchor.y()));
            if (ground != null) {
                Set<Integer> reach = new LinkedHashSet<>(anchor.mobIds());
                reach.retainAll(preferences.preferred());
                Set<Integer> fallback = new LinkedHashSet<>(preferences.preferred());
                fallback.removeAll(reach);
                fallback.addAll(preferences.fallback());
                gateway.grindFromAnchor(entry, ground, reach, fallback);
                return;
            }
        }
        gateway.grind(entry, preferences.preferred(), preferences.fallback());
    }

    /** The member's place among the fighters on its map, by character id: what spreads them over a step's ledges. */
    private static int ordinal(List<Character> fighters, Character member, MapleMap map) {
        int ordinal = 0;
        for (Character other : fighters) {
            if (other.getMap() == map && other.getId() < member.getId()) {
                ordinal++;
            }
        }
        return ordinal;
    }

    /**
     * The band of x the member keeps to on {@code map}, {minX, maxX}, or null for none: its deadly touch band
     * ({@link #touchBand}), within the P20 keep-out band for a ranged or magic member where the boss names keep-out
     * boxes. When the two do not meet, the touch band wins.
     */
    int[] band(MapleMap map, Character member, AgentBossRules.Boss rules, boolean melee, AgentBossRules.Focus step) {
        int[] touch = touchBand(map, member, step == null ? null : step.side());
        int[] keep = melee || rules.keepOut().isEmpty() ? null : keepOutBand(map, rules);
        if (touch == null || keep == null) {
            return touch == null ? keep : touch;
        }
        int low = Math.max(touch[0], keep[0]);
        int high = Math.min(touch[1], keep[1]);
        return low <= high ? new int[] {low, high} : touch;
    }

    /**
     * 2026-10-14: the band of x past every deadly touch area on the member's floor, KEEP_OUT_MARGIN_PX outside it; null
     * while none lives. A live monster's touch area (the lower half of its body box, as touch damage reads it) is deadly
     * when it reaches the floor under the member and one touch there would take at least TOUCH_KEEP_OUT_HP_PERCENT of
     * the member's max HP (Horntail's Tails, about 25,000, and Legs, about 11,000). The areas count as one span; the
     * member keeps to {@code side} of it ("left" or "right"), or with none to the side nearer its middle.
     */
    int[] touchBand(MapleMap map, Character member, String side) {
        Point at = member.getPosition();
        if (at == null) {
            return null;
        }
        Point ground = ports.gateway().groundPoint(map, at);
        Point foot = ground != null ? ground : at;
        double limit = ports.maxHp(member) * (double) touchKeepOutHpPercent() / 100.0d;
        int sweep = ports.touchSweepPx();
        long left = Long.MAX_VALUE;
        long right = Long.MIN_VALUE;
        for (Monster monster : ports.monsters(map)) {
            Rectangle area = monster.isAlive() ? ports.touchArea(monster) : null;
            if (area == null || area.y > foot.y || area.y + area.height <= foot.y - sweep
                    || ports.touchDamage(member, monster) < limit) {
                continue;
            }
            left = Math.min(left, area.x);
            right = Math.max(right, (long) area.x + area.width - 1);
        }
        if (left > right) {
            return null;
        }
        int margin = keepOutMarginPx();
        boolean keepLeft = side == null ? foot.x <= (left + right) / 2 : side.equals("left");
        return keepLeft
                ? new int[] {Integer.MIN_VALUE, (int) Math.max(Integer.MIN_VALUE, left - 1 - margin)}
                : new int[] {(int) Math.min(Integer.MAX_VALUE, right + 1 + margin), Integer.MAX_VALUE};
    }

    static int touchKeepOutHpPercent() {
        return config.AgentTuning.intValue(
                "server.agents.capabilities.expedition.boss.AgentBossPriorityFight.TOUCH_KEEP_OUT_HP_PERCENT");
    }

    /**
     * P20: the band of x a ranged or magic member keeps to on {@code map}, {minX, maxX}: past every keep-out box of a
     * live part, KEEP_OUT_MARGIN_PX inside; null while no such part lives. A box to a part's left bounds the band from
     * below, one to its right from above; when the bounds cross, the band is the point between them.
     */
    int[] keepOutBand(MapleMap map, AgentBossRules.Boss rules) {
        int margin = keepOutMarginPx();
        long low = Long.MIN_VALUE;
        long high = Long.MAX_VALUE;
        for (Monster monster : ports.monsters(map)) {
            Point at = monster.isAlive() ? monster.getPosition() : null;
            if (at == null) {
                continue;
            }
            for (AgentBossRules.KeepOut box : rules.keepOut()) {
                if (box.mobId() != monster.getId()) {
                    continue;
                }
                if (box.toDx() <= 0) {
                    low = Math.max(low, (long) at.x + box.toDx() + margin);
                } else if (box.fromDx() >= 0) {
                    high = Math.min(high, (long) at.x + box.fromDx() - margin);
                }
            }
        }
        if (low == Long.MIN_VALUE && high == Long.MAX_VALUE) {
            return null;
        }
        if (low > high) {
            low = high = (low + high) / 2;
        }
        return new int[] {(int) Math.max(low, Integer.MIN_VALUE), (int) Math.min(high, Integer.MAX_VALUE)};
    }

    /**
     * P20: keeps a ranged or magic member to {@code band} (null: no keep-out part lives, so its boundary goes). Outside
     * the band it walks in and spends the tick (true); inside, its horizontal boundary is set to the band, once per
     * change, and it fights on (false).
     */
    private boolean keepOut(PrimitiveCapabilityGateway gateway, AgentRuntimeEntry entry, Character member,
                            MapleMap map, int[] band) {
        int id = member.getId();
        if (band == null) {
            if (bands.remove(id) != null) {
                gateway.clearHorizontalBoundary(entry);
            }
            return false;
        }
        Point at = member.getPosition();
        if (at != null && (at.x < band[0] || at.x > band[1])) {
            if (bands.remove(id) != null) {
                gateway.clearHorizontalBoundary(entry);
            }
            Point inside = new Point(Math.max(band[0], Math.min(band[1], at.x)), at.y);
            Point ground = gateway.groundPoint(map, inside);
            gateway.navigate(entry, ground != null ? ground : inside, false);
            return true;
        }
        int[] was = bands.put(id, new int[] {band[0], band[1], map.getId()});
        if (was == null || was[0] != band[0] || was[1] != band[1] || was[2] != map.getId()) {
            gateway.setHorizontalBoundary(entry, map.getId(), band[0], band[1]);
        }
        return false;
    }

    /**
     * Lets go of the member's horizontal boundary, if it holds one: on a stage map that is done (else a boundary from
     * that map's head would wall it off from the stage portal, as on Horntail's first map on 2026-10-14) and after the
     * clear.
     */
    private void release(PrimitiveCapabilityGateway gateway, AgentRuntimeEntry entry, int id) {
        if (bands.remove(id) != null) {
            gateway.clearHorizontalBoundary(entry);
        }
    }

    static int keepOutMarginPx() {
        return config.AgentTuning.intValue(
                "server.agents.capabilities.expedition.boss.AgentBossPriorityFight.KEEP_OUT_MARGIN_PX");
    }

    /** P18: a stage map is done once no live monster but the boss's never-targets stands on it. */
    private boolean stageDone(MapleMap map, AgentBossRules.Boss rules) {
        for (Monster monster : ports.monsters(map)) {
            if (monster.isAlive() && !rules.neverTarget().contains(monster.getId())) {
                return false;
            }
        }
        return true;
    }

    /**
     * P18: walks to the stage portal and, beside it, takes it at most every STAGE_PORTAL_EVERY_MS. 2026-10-14: beside it
     * a member first puts Magic Guard back up ({@link Ports#castSurvivalBuff}), so a Bishop walking back in after a
     * death does not reach the battle map at 5,000 HP with no guard (one head attack killed one on arrival 15 times).
     */
    private void advance(PrimitiveCapabilityGateway gateway, AgentRuntimeEntry entry, Character member,
                         String portalName, long nowMs) {
        Point portal = portalName == null ? null : ports.portalPosition(member, portalName);
        Point at = member.getPosition();
        if (portal == null || at == null) {
            gateway.stop(entry);
            return;
        }
        long reach = AgentBossRunSession.exitReachPx();
        if (at.distanceSq(portal) > reach * reach) {
            gateway.navigate(entry, portal, true);
            return;
        }
        if (ports.castSurvivalBuff(member)) {
            return;
        }
        Long last = portalTries.get(member.getId());
        if (last == null || nowMs - last >= stagePortalEveryMs()) {
            portalTries.put(member.getId(), nowMs);
            gateway.stop(entry);
            ports.enterPortal(member, portalName);
        }
    }

    static long stagePortalEveryMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.expedition.boss.AgentBossPriorityFight.STAGE_PORTAL_EVERY_MS");
    }

    /** The ids of the live monsters on {@code map} a member may hit now. */
    private Set<Integer> liveIds(MapleMap map) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (Monster monster : ports.monsters(map)) {
            if (monster.isAlive() && ports.hittable(monster)) {
                ids.add(monster.getId());
            }
        }
        return ids;
    }

    interface Ports {
        PrimitiveCapabilityGateway gateway();

        AgentRuntimeEntry entry(int characterId);

        List<Monster> monsters(MapleMap map);

        /** Not a part a human must drive, or one a human drives now. */
        boolean hittable(Monster monster);

        /** P18: hits in melee range (not with magic, not with a ranged weapon), as the Zakum fight reads it. */
        default boolean melee(Character member) {
            return false;
        }

        /** P18: where the portal named {@code name} stands on the member's map, or null. */
        default Point portalPosition(Character member, String name) {
            return null;
        }

        /** P18: the member takes the portal named {@code name} on its map, script and all. */
        default boolean enterPortal(Character member, String name) {
            return false;
        }

        /**
         * The area where {@code monster} touches an Agent: the lower half of its body box, as
         * AgentMobTouchPolicy.lowerHalfIntersects reads it; null with no box or when its touch does no damage.
         */
        default Rectangle touchArea(Monster monster) {
            return null;
        }

        /** The mean damage one touch of {@code monster} does to {@code member}, before a dodge. */
        default double touchDamage(Character member, Monster monster) {
            return 0;
        }

        /** The member's max HP as touch damage meets it (Magic Guard counted). */
        default int maxHp(Character member) {
            return Math.max(1, member.getMaxHp());
        }

        /** How far up from its feet a touch reaches an Agent (MOB_TOUCH_SWEEP_HEIGHT). */
        default int touchSweepPx() {
            return 50;
        }

        /**
         * The member casts Magic Guard when it knows it and has it down (AgentCombatBuffRuntime's critical survival
         * buff); true when it cast now. Buffs wait on a map with no live monster, so a member walking back through a
         * finished stage map would reach the battle map without it.
         */
        default boolean castSurvivalBuff(Character member) {
            return false;
        }

        Ports LIVE = new Ports() {
            @Override
            public boolean castSurvivalBuff(Character member) {
                AgentRuntimeEntry entry = entry(member.getId());
                return entry != null && AgentCombatBuffRuntime.tryCastCriticalSurvivalBuff(entry, member);
            }

            @Override
            public Rectangle touchArea(Monster monster) {
                if (AgentMobTouchPolicy.ignoresTouchDamage(monster.getId())) {
                    return null;
                }
                Rectangle box = AgentMobHitboxProvider.getInstance().getMobBounds(monster);
                if (box == null) {
                    return null;
                }
                int lower = Math.max(1, box.height / 2);
                return new Rectangle(box.x, box.y + box.height - lower, box.width, lower);
            }

            @Override
            public double touchDamage(Character member, Monster monster) {
                return AgentDefenseDataProvider.getInstance().expectedPhysicalTouchDamage(member, monster);
            }

            @Override
            public int maxHp(Character member) {
                return AgentDefenseDataProvider.effectiveMaximumHp(member);
            }

            @Override
            public int touchSweepPx() {
                return AgentCombatConfig.cfg.MOB_TOUCH_SWEEP_HEIGHT;
            }

            @Override
            public boolean melee(Character member) {
                boolean magic = member.getJob() != null && member.getJob().getJobNiche() == 2;
                return !magic && !gateway().rangedWeapon(member);
            }

            @Override
            public Point portalPosition(Character member, String name) {
                return gateway().portalPosition(member, name);
            }

            @Override
            public boolean enterPortal(Character member, String name) {
                Portal portal = member.getMap() == null ? null : member.getMap().getPortal(name);
                return portal != null && gateway().enterPortal(member, portal.getId());
            }

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
            public boolean hittable(Monster monster) {
                return !AgentBossScope.guards(monster) || AgentBossScope.engaged(monster);
            }
        };
    }
}
