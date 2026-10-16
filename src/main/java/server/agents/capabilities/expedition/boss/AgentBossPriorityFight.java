package server.agents.capabilities.expedition.boss;

import client.Character;
import client.Disease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.combat.AgentBerserkWindowPolicy;
import server.agents.capabilities.combat.AgentCombatBuffRuntime;
import server.agents.capabilities.combat.AgentCombatConfig;
import server.agents.capabilities.combat.AgentCombatReach;
import server.agents.capabilities.combat.AgentMobTouchPolicy;
import server.agents.capabilities.combat.data.AgentDefenseDataProvider;
import server.agents.capabilities.combat.data.AgentMobHitboxProvider;
import server.agents.capabilities.expedition.AgentBossFight;
import server.agents.capabilities.mobcontrol.AgentBossScope;
import server.agents.capabilities.movement.AgentMovementPhysicsConfig;
import server.agents.capabilities.supplies.AgentPotionService;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.life.Monster;
import server.life.MonsterStats;
import server.life.autonomy.BossAction;
import server.life.autonomy.ServerMobActionCatalog;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
 *
 * <p>2026-10-15: a part the boss rules list under {@code attackKeepOut} (Showa's Bodyguards) adds the reach of each of
 * its attacks that would take ATTACK_KEEP_OUT_HP_PERCENT of a member's HP in one hit to the span the member keeps out
 * of, on the side the part faces, so the member fights it from behind or from outside that reach. A part that moves
 * (Showa's Bodyguards walk the floor) keeps members MOBILE_KEEP_OUT_MARGIN_PX past its span instead of
 * KEEP_OUT_MARGIN_PX, and while any monster on its map could hit a member for more than its autopot alert leaves it,
 * the member drinks earlier ({@link #guardHp}).</p>
 *
 * <p>2026-10-16: a melee member whose attacks cannot reach its preferred part from the floor grinds it from one of the
 * boss's {@code ledges} that names it, when they reach from there ({@link #ledge}: Pink Bean's Hugin and Munin).</p>
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
    /** Fighter id -> its own HP alert while a big hit nearby holds it higher ({@link #guardHp}). */
    private final Map<Integer, Float> raisedAlerts = new HashMap<>();
    /** Fighters held on a finished stage map with no HP potion left ({@link #holdsDry}). */
    private final Set<Integer> heldDry = new HashSet<>();
    private final Set<Integer> heldLastStand = new HashSet<>();
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
                if (holdsDry(member) || holdsLastStand(member, event, rules)) {
                    gateway.stop(entry);
                } else {
                    advance(gateway, entry, member, rules.stagePortal(), nowMs);
                }
                continue;
            }
            guardHp(member, cleared ? null : map, rules);
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
            } else if (!melee || !ledge(gateway, entry, member, map, rules, preferences,
                    ordinal(fighters, member, map))) {
                gateway.grind(entry, preferences.preferred(), preferences.fallback());
            }
        }
    }

    /**
     * 2026-10-16 (P20): a melee member whose attacks cannot reach its preferred part from the floor under the part's
     * body ({@link Ports#reaches}) grinds it from one of the boss's {@code ledges} that names it and from which they
     * do, spread by {@code ordinal} over those ledges, the rest of its preferences the fallback; true when it does. A
     * member that reaches from the floor grinds as before. Pink Bean's Hugin and Munin hang 99 px above the floor: a
     * Hero's Brandish reaches 110 px up, a Paladin's Blast 60, and Orrin swung 0-3 times a minute against Hugin on the
     * floor and 25-55 on the y=-99 ledge beside him, where the fight sent no one and only luck took a member.
     */
    private boolean ledge(PrimitiveCapabilityGateway gateway, AgentRuntimeEntry entry, Character member, MapleMap map,
                          AgentBossRules.Boss rules, AgentBossRules.Preferences preferences, int ordinal) {
        if (rules.ledges().isEmpty()) {
            return false;
        }
        for (Monster part : ports.monsters(map)) {
            if (!part.isAlive() || !preferences.preferred().contains(part.getId())) {
                continue;
            }
            Point floor = floorUnder(gateway, map, part);
            if (floor == null || ports.reaches(member, part, floor)) {
                continue;
            }
            List<Point> usable = new ArrayList<>();
            for (AgentBossRules.Anchor ledge : rules.ledges()) {
                Point ground = ledge.mobIds().contains(part.getId())
                        ? gateway.groundPoint(map, new Point(ledge.x(), ledge.y())) : null;
                if (ground != null && ports.reaches(member, part, ground)) {
                    usable.add(ground);
                }
            }
            if (usable.isEmpty()) {
                continue;
            }
            Set<Integer> fallback = new LinkedHashSet<>(preferences.preferred());
            fallback.remove(part.getId());
            fallback.addAll(preferences.fallback());
            gateway.grindFromAnchor(entry, usable.get(Math.floorMod(ordinal, usable.size())), Set.of(part.getId()),
                    fallback);
            return true;
        }
        return false;
    }

    /** The point on the floor under the middle of {@code part}'s body (its origin with no body); null when unknown. */
    private Point floorUnder(PrimitiveCapabilityGateway gateway, MapleMap map, Monster part) {
        Point origin = part.getPosition();
        if (origin == null) {
            return null;
        }
        Rectangle body = ports.body(part);
        return gateway.groundPoint(map, new Point(body == null ? origin.x : (int) body.getCenterX(), origin.y));
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
            long[] swings = AgentBossClientLoad.drain(member.getId());
            List<?> buffs = member.getAllBuffs();
            members.append(members.length() == 0 ? "" : "; ").append(member.getName()).append(' ')
                    .append(member.getMapId()).append(at == null ? "" : "(" + at.x + "," + at.y + ")")
                    .append(" hp=").append(percent(member.getHp(), member.getCurrentMaxHp())).append('%')
                    .append(" buffs=").append(buffs == null ? 0 : buffs.size())
                    .append(" sealed=").append(times[0] / 1000).append('s')
                    .append(" disabled=").append(times[1] / 1000).append('s')
                    .append(" atk=").append(swings[0])
                    .append(" dmg=").append(swings[1] / 1000).append('k');
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
        int[] touch = touchBand(map, member, step == null ? null : step.side(), rules.attackKeepOut(),
                rules.keepOutHpPercent(), rules.attackThresholdPercent());
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
     * the member's max HP (Horntail's Tails, about 25,000, and Legs, about 11,000). With {@code side} ("left" or
     * "right") the areas count as one span and the member keeps to that side of it. With none (2026-10-15, P20 run 11)
     * only areas that overlap, each widened by its margin, join: the member keeps to the free gap it stands in, or,
     * standing inside a joined area, to the gap beside its nearer edge. Pink Bean's Solomon and Hugin touch apart, and
     * the whole span between them walled off the only ledge from which Hugin can be hit.
     */
    int[] touchBand(MapleMap map, Character member, String side) {
        return touchBand(map, member, side, List.of());
    }

    /**
     * {@link #touchBand(MapleMap, Character, String)} with the attacks of the live parts in {@code attackParts} (the
     * boss's attackKeepOut, 2026-10-15) in the span too: each attack box ({@link #attackArea}, on the side the part
     * faces) that reaches the member's floor, for an attack that is not deadly (a deadly hit leaves 1 HP, and the
     * member drinks back up) and whose mean hit is at least ATTACK_KEEP_OUT_HP_PERCENT of the member's max HP.
     */
    int[] touchBand(MapleMap map, Character member, String side, List<Integer> attackParts) {
        return touchBand(map, member, side, attackParts, 0);
    }

    /**
     * {@link #touchBand(MapleMap, Character, String, List)} with the boss's own threshold for both keep-outs
     * ({@code keepOutHpPercent}, boss-rules.json, 2026-10-15): a percent of the member's max HP, or 0 for the global
     * TOUCH_KEEP_OUT_HP_PERCENT and ATTACK_KEEP_OUT_HP_PERCENT.
     */
    int[] touchBand(MapleMap map, Character member, String side, List<Integer> attackParts, int keepOutHpPercent) {
        return touchBand(map, member, side, attackParts, keepOutHpPercent, keepOutHpPercent);
    }

    /**
     * {@link #touchBand(MapleMap, Character, String, List, int)} with a threshold of its own for the attacks
     * ({@code attackKeepOutHpPercent}, boss-rules.json, 2026-10-16; 0 for the global ATTACK_KEEP_OUT_HP_PERCENT).
     */
    int[] touchBand(MapleMap map, Character member, String side, List<Integer> attackParts, int keepOutHpPercent,
                    int attackKeepOutHpPercent) {
        Point at = member.getPosition();
        if (at == null) {
            return null;
        }
        Point ground = ports.gateway().groundPoint(map, at);
        Point foot = ground != null ? ground : at;
        double limit = ports.maxHp(member)
                * (double) (keepOutHpPercent > 0 ? keepOutHpPercent : touchKeepOutHpPercent()) / 100.0d;
        int sweep = ports.touchSweepPx();
        List<long[]> areas = new ArrayList<>();
        for (Monster monster : ports.monsters(map)) {
            Rectangle area = monster.isAlive() ? ports.touchArea(monster) : null;
            if (area == null || area.y > foot.y || area.y + area.height <= foot.y - sweep
                    || ports.touchDamage(member, monster) < limit) {
                continue;
            }
            areas.add(new long[] {area.x, (long) area.x + area.width - 1, ports.mobile(monster) ? 1 : 0});
        }
        double attackLimit = ports.maxHp(member)
                * (double) (attackKeepOutHpPercent > 0 ? attackKeepOutHpPercent : attackKeepOutHpPercent()) / 100.0d;
        for (Monster monster : attackParts.isEmpty() ? List.<Monster>of() : ports.monsters(map)) {
            Point origin = monster.isAlive() && attackParts.contains(monster.getId()) ? monster.getPosition() : null;
            if (origin == null) {
                continue;
            }
            for (BossAction.OrdinaryAttack attack : ports.attacks(monster)) {
                Rectangle area = attack.deadly() ? null : attackArea(origin, ports.facesLeft(monster), attack);
                if (area == null || area.y > foot.y || area.y + area.height <= foot.y - sweep
                        || ports.attackDamage(member, monster, attack) < attackLimit) {
                    continue;
                }
                areas.add(new long[] {area.x, (long) area.x + area.width - 1, ports.mobile(monster) ? 1 : 0});
            }
        }
        if (areas.isEmpty()) {
            return null;
        }
        if (side == null) {
            return gap(areas, foot.x);
        }
        long left = Long.MAX_VALUE;
        long right = Long.MIN_VALUE;
        boolean moving = false;
        for (long[] area : areas) {
            left = Math.min(left, area[0]);
            right = Math.max(right, area[1]);
            moving |= area[2] != 0;
        }
        int margin = moving ? Math.max(keepOutMarginPx(), mobileKeepOutMarginPx()) : keepOutMarginPx();
        return side.equals("left")
                ? new int[] {Integer.MIN_VALUE, clamp(left - 1 - margin)}
                : new int[] {clamp(right + 1 + margin), Integer.MAX_VALUE};
    }

    /**
     * The band of the free gap at {@code x} between the deadly {@code areas} ({x0, x1, mobile}), each widened by its
     * margin to the last x a member may stand at, overlapping ones joined; standing inside a joined area, the gap
     * beside its nearer edge (the left one at the middle).
     */
    private static int[] gap(List<long[]> areas, int x) {
        List<long[]> spans = new ArrayList<>();
        for (long[] area : areas) {
            int margin = area[2] != 0 ? Math.max(keepOutMarginPx(), mobileKeepOutMarginPx()) : keepOutMarginPx();
            spans.add(new long[] {area[0] - 1 - margin, area[1] + 1 + margin});
        }
        spans.sort(Comparator.comparingLong(span -> span[0]));
        List<long[]> joined = new ArrayList<>();
        for (long[] span : spans) {
            long[] last = joined.isEmpty() ? null : joined.get(joined.size() - 1);
            if (last != null && span[0] <= last[1]) {
                last[1] = Math.max(last[1], span[1]);
            } else {
                joined.add(span.clone());
            }
        }
        int i = 0;
        while (i < joined.size() && joined.get(i)[1] <= x) {
            i++;
        }
        if (i < joined.size() && joined.get(i)[0] < x) {
            long[] inside = joined.get(i);
            if (x > (inside[0] + inside[1]) / 2) {
                i++;
            }
        }
        long low = i == 0 ? Integer.MIN_VALUE : joined.get(i - 1)[1];
        long high = i == joined.size() ? Integer.MAX_VALUE : joined.get(i)[0];
        return new int[] {clamp(low), clamp(high)};
    }

    private static int clamp(long x) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, x));
    }

    /**
     * Where {@code attack} strikes from a part at {@code origin}: its WZ box (stored for a part facing left), mirrored
     * when the part faces right; null without a box.
     */
    static Rectangle attackArea(Point origin, boolean facingLeft, BossAction.OrdinaryAttack attack) {
        if (origin == null || attack.lt() == null || attack.rb() == null) {
            return null;
        }
        int minX = facingLeft ? origin.x + attack.lt().x : origin.x - attack.rb().x;
        int maxX = facingLeft ? origin.x + attack.rb().x : origin.x - attack.lt().x;
        int minY = origin.y + Math.min(attack.lt().y, attack.rb().y);
        int maxY = origin.y + Math.max(attack.lt().y, attack.rb().y);
        return new Rectangle(minX, minY, Math.max(1, maxX - minX + 1), Math.max(1, maxY - minY + 1));
    }

    /**
     * 2026-10-15 (P19): while a live monster on the member's map could hit it for more than its autopot alert leaves
     * it ({@link #worstHit}), its HP alert rises to that hit plus HP_FLOOR_EXTRA_PERCENT of it, as a share of its
     * max HP, at most HP_FLOOR_MAX_PERCENT, so the member drinks before one hit can find it low: Showa's Bodyguard A
     * touches for about 16,800, and a Hero with 19,776 HP who drinks at 70% died to one touch. Its own alert comes
     * back once no such monster lives (the 45 s autopot setup restores it too). A member with no HP potion key
     * (alert 0) is left alone; {@code map} null (the run cleared) restores the alert.
     */
    private void guardHp(Character member, MapleMap map, AgentBossRules.Boss rules) {
        int id = member.getId();
        float base = ports.baseHpAlert(member);
        double worst = map == null || rules == null ? 0 : worstHit(map, member, rules.attackKeepOut());
        float floor = (float) Math.min(hpFloorMaxPercent() / 100.0d,
                worst * (100 + hpFloorExtraPercent()) / 100.0d / Math.max(1, ports.maxHp(member)));
        if (base > 0 && floor > base) {
            raisedAlerts.put(id, base);
            ports.setHpAlert(member, floor);
        } else if (raisedAlerts.remove(id) != null) {
            ports.setHpAlert(member, base);
        }
    }

    /**
     * The biggest mean hit a live monster on {@code map} could deal the member now: a touch whose area reaches its
     * floor, or a non-deadly attack of a part in {@code attackParts}; 0 for none.
     */
    double worstHit(MapleMap map, Character member, List<Integer> attackParts) {
        Point at = member.getPosition();
        if (at == null) {
            return 0;
        }
        Point ground = ports.gateway().groundPoint(map, at);
        Point foot = ground != null ? ground : at;
        int sweep = ports.touchSweepPx();
        double worst = 0;
        for (Monster monster : ports.monsters(map)) {
            if (!monster.isAlive()) {
                continue;
            }
            Rectangle area = ports.touchArea(monster);
            if (area != null && area.y <= foot.y && area.y + area.height > foot.y - sweep) {
                worst = Math.max(worst, ports.touchDamage(member, monster));
            }
            if (attackParts.contains(monster.getId())) {
                for (BossAction.OrdinaryAttack attack : ports.attacks(monster)) {
                    if (!attack.deadly()) {
                        worst = Math.max(worst, ports.attackDamage(member, monster, attack));
                    }
                }
            }
        }
        return worst;
    }

    static int mobileKeepOutMarginPx() {
        return config.AgentTuning.intValue(
                "server.agents.capabilities.expedition.boss.AgentBossPriorityFight.MOBILE_KEEP_OUT_MARGIN_PX");
    }

    static int hpFloorExtraPercent() {
        return config.AgentTuning.intValue(
                "server.agents.capabilities.expedition.boss.AgentBossPriorityFight.HP_FLOOR_EXTRA_PERCENT");
    }

    static int hpFloorMaxPercent() {
        return config.AgentTuning.intValue(
                "server.agents.capabilities.expedition.boss.AgentBossPriorityFight.HP_FLOOR_MAX_PERCENT");
    }

    static int attackKeepOutHpPercent() {
        return config.AgentTuning.intValue(
                "server.agents.capabilities.expedition.boss.AgentBossPriorityFight.ATTACK_KEEP_OUT_HP_PERCENT");
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
     *
     * <p>2026-10-16: it walks to {@link Ports#walkArrivalPx} inside the edge it stands beyond (at most to the band's
     * middle, and then precisely), since a walk ends once that near its target. Aimed at the edge itself, the walk of a
     * member standing that close outside it ended where it began, and every tick sent it again instead of a fight.
     * Over the Pink Bean runs, ranged members standing up to 30 px past Solomon's or Rex's keep-out landed 0-3 attacks
     * a minute (Fenna a mean of 3 over 69 status lines), and Garrick, 28 px inside Hugin's touch margin on the ledge,
     * 0-2 in run 15, while the members just inside that margin landed 33-39.</p>
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
            long inset = Math.min(ports.walkArrivalPx(), ((long) band[1] - band[0]) / 2);
            Point inside = new Point(clamp(at.x < band[0] ? band[0] + inset : band[1] - inset), at.y);
            Point ground = gateway.groundPoint(map, inside);
            gateway.navigate(entry, ground != null ? ground : inside, inset < ports.walkArrivalPx());
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

    /**
     * 2026-10-15 (P20 run 9): true while a member of a run whose deaths count against it (REVIVE_INSIDE: Pink Bean's
     * expedition fails at its fifth death) stands on a finished stage map, the room it revives in, with no HP potion
     * left: it waits there instead of walking back in. Ingrid revived dry three times in 46 seconds and spent the
     * run's last three lives. Once it holds a potion again (its owner hands it some), it walks back in. Says so once
     * per stay ({@link Ports#heldDry}).
     */
    private boolean holdsDry(Character member) {
        AgentBossRunDefinition definition = AgentBossRunCatalog.of(key);
        boolean dry = definition != null && definition.deathMode() == AgentBossRunDefinition.DeathMode.REVIVE_INSIDE
                && ports.hpPotions(member) == 0;
        if (!dry) {
            heldDry.remove(member.getId());
        } else if (heldDry.add(member.getId())) {
            log.info("Boss run member holds dry: boss={} member={} map={}", key, member.getId(), member.getMapId());
            ports.heldDry(member);
        }
        return dry;
    }

    /**
     * 2026-10-16 (P20 runs 14-16): true once the boss's event counts its last-stand deaths ({@code lastStand},
     * boss-rules.json; Pink Bean's script calls "last stand mode" at its fourth fallen player and ends at the fifth),
     * for a member that revived on a finished stage map: it waits there for the rest of the run instead of walking back
     * in, as an expedition tells its dead to stay out once one more death would end it. Runs 14 to 16 each ended on a
     * member walking back in after the fourth death and dying again (Fenna, twice in a minute each time). Says so once
     * per stay ({@link Ports#heldLastStand}).
     */
    private boolean holdsLastStand(Character member, EventInstanceManager event, AgentBossRules.Boss rules) {
        AgentBossRules.LastStand lastStand = rules.lastStand();
        int fallen = lastStand == null || event == null ? 0 : ports.eventCount(event, lastStand.property());
        boolean hold = lastStand != null && fallen >= lastStand.at();
        if (!hold) {
            heldLastStand.remove(member.getId());
        } else if (heldLastStand.add(member.getId())) {
            log.info("Boss run member holds for the last stand: boss={} member={} map={} fallen={}", key,
                    member.getId(), member.getMapId(), fallen);
            ports.heldLastStand(member);
        }
        return hold;
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

        /** 2026-10-15: the attacks of {@code monster}, in WZ order (ServerMobActionCatalog). */
        default List<BossAction.OrdinaryAttack> attacks(Monster monster) {
            return List.of();
        }

        /** 2026-10-15: true when {@code monster} faces left now (a noFlip part always reads as left). */
        default boolean facesLeft(Monster monster) {
            return true;
        }

        /**
         * 2026-10-15: the mean damage one hit of {@code attack} of {@code monster} does to {@code member} when it lands,
         * at the boss attack mirror's damage percent.
         */
        default double attackDamage(Character member, Monster monster, BossAction.OrdinaryAttack attack) {
            return 0;
        }

        /** 2026-10-15: true when {@code monster} walks or flies (Showa's Bodyguards), not a part that stands still. */
        default boolean mobile(Monster monster) {
            return false;
        }

        /**
         * 2026-10-15: the HP fraction the member drinks below on its own (its owner's setting or the configured
         * alert, with a Berserk window), 0 with no HP potion key.
         */
        default float baseHpAlert(Character member) {
            return member.getAutopotHpAlert();
        }

        /** 2026-10-15: the member's autopot now drinks below {@code alert} of its max HP. */
        default void setHpAlert(Character member, float alert) {
            member.setAutopotHpAlert(alert);
        }

        /**
         * The member casts Magic Guard when it knows it and has it down (AgentCombatBuffRuntime's critical survival
         * buff); true when it cast now. Buffs wait on a map with no live monster, so a member walking back through a
         * finished stage map would reach the battle map without it.
         */
        default boolean castSurvivalBuff(Character member) {
            return false;
        }

        /** 2026-10-15: the HP potions the member carries (AgentPotionService's count); -1 when that cannot be told. */
        default int hpPotions(Character member) {
            return Integer.MAX_VALUE;
        }

        /** 2026-10-15: the member started waiting on a stage map with no HP potion left; its host says so. */
        default void heldDry(Character member) {
        }

        /** 2026-10-16: the event's count under {@code property} (Pink Bean's fallenPlayers), 0 while it holds none. */
        default int eventCount(EventInstanceManager event, String property) {
            try {
                return event.getIntProperty(property);
            } catch (NumberFormatException none) {
                return 0;
            }
        }

        /** 2026-10-16: the member started waiting on a stage map for the boss's last stand; its host says so. */
        default void heldLastStand(Character member) {
        }

        /**
         * 2026-10-16: true when one of the member's attacks would hit {@code part}'s body from {@code stand}
         * (AgentCombatReach); true by default, so a fight that cannot tell sends no member to a ledge.
         */
        default boolean reaches(Character member, Monster part, Point stand) {
            return true;
        }

        /** 2026-10-16: the body box of {@code part} where it stands (Mob.wz lt/rb), or null with none. */
        default Rectangle body(Monster part) {
            return null;
        }

        /** 2026-10-16: how near its target a member's walk ends (STOP_DIST). */
        default int walkArrivalPx() {
            return 30;
        }

        Ports LIVE = new Ports() {
            @Override
            public boolean reaches(Character member, Monster part, Point stand) {
                AgentRuntimeEntry entry = entry(member.getId());
                return entry == null || AgentCombatReach.reachesFrom(entry, member, part, stand);
            }

            @Override
            public Rectangle body(Monster part) {
                return AgentMobHitboxProvider.getInstance().getMobBounds(part);
            }

            @Override
            public int walkArrivalPx() {
                return AgentMovementPhysicsConfig.configuredStopDist();
            }

            @Override
            public int hpPotions(Character member) {
                return gateway().hpPotionCount(member);
            }

            @Override
            public void heldDry(Character member) {
                AgentBossRunSession session = AgentBossRunRegistry.forMember(member.getId());
                AgentBossRunMember seat = session == null ? null : session.member(member.getId());
                if (seat != null) {
                    AgentBossRunHost.installed().heldDry(seat.seat(session.definition().key(), session.sessionId()));
                }
            }

            @Override
            public void heldLastStand(Character member) {
                AgentBossRunSession session = AgentBossRunRegistry.forMember(member.getId());
                AgentBossRunMember seat = session == null ? null : session.member(member.getId());
                if (seat != null) {
                    AgentBossRunHost.installed().heldLastStand(
                            seat.seat(session.definition().key(), session.sessionId()));
                }
            }

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
            public List<BossAction.OrdinaryAttack> attacks(Monster monster) {
                return ServerMobActionCatalog.forMob(monster.getId()).attacks();
            }

            @Override
            public boolean mobile(Monster monster) {
                MonsterStats stats = monster.getStats();
                return stats != null && stats.isMobile();
            }

            @Override
            public float baseHpAlert(Character member) {
                return member.getKeymap().get(91) == null ? 0f
                        : AgentBerserkWindowPolicy.autopotHpThreshold(member, AgentPotionService.hpThreshold(member));
            }

            @Override
            public boolean facesLeft(Monster monster) {
                MonsterStats stats = monster.getStats();
                return (stats != null && stats.getFixedStance() > 0) || monster.isFacingLeft();
            }

            @Override
            public double attackDamage(Character member, Monster monster, BossAction.OrdinaryAttack attack) {
                AgentDefenseDataProvider defense = AgentDefenseDataProvider.getInstance();
                double mean = attack.magic()
                        ? defense.expectedMagicAttackDamage(member, monster,
                                attack.magicAttack() > 0 ? attack.magicAttack() : monster.getMADamage())
                        : defense.expectedPhysicalTouchDamage(member, monster,
                                attack.physicalAttack() > 0 ? attack.physicalAttack() : monster.getPADamage());
                return mean * Math.max(0, AgentBossAttackMirror.damagePercent()) / 100.0d;
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
