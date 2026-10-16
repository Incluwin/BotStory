package server.agents.capabilities.expedition.boss;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The boss rules catalog (agents/catalogs/boss-rules.json, P11): per boss key, the mob ids its companion fight reads
 * instead of static constants. Normal Balrog (balrog_normal) is the first entry; P12 extends the file to every boss.
 *
 * <ul>
 * <li>{@code roles}: the fight's named parts (Normal Balrog: body, releasedClaw, initialClaw, releaseSeal).</li>
 * <li>{@code targetPriority}: the parts a member hits, first first (a part hits only once it is up). A fight with no
 * driver of its own reads it through {@link #preferences} (P12 G3).</li>
 * <li>{@code adds}: the boss's summons, which members also hit.</li>
 * <li>{@code neverTarget}: parts a run member never hits, as a primary, secondary or projectile target
 * ({@link AgentBossRunRegistry#neverTarget}): dummies, helpers and seals whose death would change the fight, and
 * display parts (Horntail's dead heads, Pink Bean's statues).</li>
 * <li>{@code neverTargetFakes}: a run member never hits a fake part (Zakum's body before its arms fall).</li>
 * <li>{@code minLevel} (P14): the level a companion needs for a party boss, which has no expedition type.</li>
 * <li>{@code deathWaitMap} (P15, decision 9): the map a member that died in the fight walks to and waits on once it
 * comes to (Papulatus: its gate 220080000, from Ludibrium); 0 waits where it came to.</li>
 * <li>{@code stageMaps} and {@code stagePortal} (P18): the event maps before the battle map, in order, and the
 * portal a member takes from one to the next (Horntail: its pre-head maps 240060000 and 240060100, by next00). A run
 * adopts and fights a member on any of them; once a stage map holds no live monster but never-targets, a member walks
 * on through the portal (the stock script lets it through once that map's head fell).</li>
 * <li>{@code meleePriority} (P18): the target priority of a melee member, when it differs from {@code targetPriority}
 * (Horntail: the legs and the tail from the floor, while ranged and magic members take the heads).</li>
 * <li>{@code reentryWarpFrom} (P18, plan decision 11a): in a re-enter run whose recruit map no route reaches (Horntail:
 * the Slate warps players into 240050400), the map from which a member walking back is moved to the recruit map, as
 * the waiver lets companions in without the quest item (Leafre 240000000, where it comes to); 0 for none.</li>
 * <li>{@code randomSpots} (P15): the mirror strikes an attack whose effect falls at random spots (WZ effect0 randomPos)
 * only near a spot, not across its whole box (Papulatus's Clock attack3). Off for every boss whose mirror ran live
 * before it (Zakum's attack2), until a live fight there checks it.</li>
 * <li>{@code entryItems} (P20): items the owner carries in to start the fight (Zakum's Eye of Fire, Showa's comb). A
 * boss's approach store keeps its standard only for an owner carrying one (BossEntrance.approachMeantBy), and the
 * marker line asks for it. Absent, it is {@code markerItems}; Pink Bean names none, since its marker item (the stage-5
 * drop) only ever lies on the battle map's floor.</li>
 * <li>{@code keepOut} (P20): boxes beside a part, from {@code fromDx} to {@code toDx} along x from the part, that
 * ranged and magic members keep out of while the part lives (Solomon's deadly attack1 to his left, Rex's to his
 * right). A box wholly to one side of its part bounds the member on that side.</li>
 * <li>{@code focus} (2026-10-14): the boss's focus order, step by step ({@link Focus}): every member hits the live
 * parts of the first step that has one, from a ledge the step names when it names any, and keeps to the step's side of
 * a deadly touch area (Horntail: the Legs and the Tail from the left, then the Wings and Hands, then the Heads). Where
 * no step has a live part (Horntail's pre-head maps) the priority lists apply.</li>
 * <li>{@code attackKeepOut} (2026-10-15): parts whose attacks every member keeps out of where one hit would take most
 * of its HP (AgentBossPriorityFight.ATTACK_KEEP_OUT_HP_PERCENT), past the attack's reach on the part's facing side, as
 * a player fights a boss from behind (Showa's Bodyguard A, whose attack2 hits about 26,000 up to 147 px in front).</li>
 * <li>{@code keepOutHpPercent} (2026-10-15, 0 = the global TOUCH_KEEP_OUT_HP_PERCENT and ATTACK_KEEP_OUT_HP_PERCENT):
 * this boss's threshold for both keep-outs, as a percent of the member's max HP. Showa's is 90: the HP floor
 * (AgentBossPriorityFight.guardHp) carries a member through any hit under about 82% of its HP, so a Hyper Body warrior
 * (about 31,600 HP) fights Bodyguard A at arm's length while a member a hit would kill outright keeps out.</li>
 * <li>{@code attackKeepOutHpPercent} (2026-10-16, 0 = {@code keepOutHpPercent}): this boss's threshold for the attack
 * keep-out alone. Pink Bean's is 65: his barrage (about 11,900, with a stun) is 80% of a Bowmaster's Hyper Body HP
 * (about 14,400), under the global 80, and killed Fenna twice in a minute in run 15; a warrior without Hyper Body
 * (19,776) takes 60% and still fights at his feet. Its touch keep-out stays at the global 25.</li>
 * <li>{@code lastStand} (2026-10-16, null for none): the event property counting the run's deaths and the count at
 * which the boss's script calls its last stand ({@link LastStand}). From then on a member that revives on a stage map
 * waits there instead of walking back in (AgentBossPriorityFight.holdsLastStand), as an expedition tells its dead to
 * stay out once one more death would end it. Pink Bean runs 14 to 16 each ended on a member walking back in after the
 * fourth death and dying again.</li>
 * <li>{@code companionDamagePercent} (2026-10-15, 0 = 100 = unchanged): the percent an INSIDE member's damage lines on
 * this boss's parts are scaled to (AgentBossClientLoad.scaled), so a crew overpowers a boss whose HP is out of reach of
 * level-200 Agents in its time limit (user: bosses take big numbers, never a kill in seconds). Pink Bean's 7.65 billion
 * HP over 140 minutes needs about 55 million a minute; twelve Lv200 companions dealt about 17 million (P20 run 1).
 * At most 5000; a line is clamped at Integer.MAX_VALUE.</li>
 * <li>{@code markerItems}: drops a run member leaves on the floor, items the boss's own script takes (Zakum's Eye of
 * Fire). The run catalog (AgentBossRunCatalog) reads this and {@code neverTargetFakes} from here.</li>
 * <li>{@code fieldBosses} (P12 G1): for a field group key such as field_victoria, the boss ids that fight under it on
 * a map with no event ({@link #fieldKeyOf}); the mirror's field scope and its attack floor act on them while the key
 * is on.</li>
 * </ul>
 */
public final class AgentBossRules {
    private static final String RESOURCE = "/agents/catalogs/boss-rules.json";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static volatile List<Boss> bosses = load();
    private static volatile FieldIndex fieldIndex = FieldIndex.of(bosses);

    /** One boss's rules. */
    public record Boss(String key, String label, Map<String, Integer> roles, List<Integer> targetPriority,
                       List<Integer> adds, List<Integer> neverTarget, List<Integer> fieldBosses,
                       boolean neverTargetFakes, List<Integer> markerItems, int minLevel, int deathWaitMap,
                       boolean randomSpots, List<Integer> stageMaps, String stagePortal,
                       List<Integer> meleePriority, int reentryWarpFrom, List<Integer> entryItems,
                       List<KeepOut> keepOut, List<Focus> focus, List<Integer> attackKeepOut,
                       int keepOutHpPercent, int companionDamagePercent, int attackKeepOutHpPercent,
                       LastStand lastStand) {
        public Boss {
            if (key == null || key.isBlank() || label == null || label.isBlank()) {
                throw new IllegalArgumentException("a boss rule needs a key and a label");
            }
            key = key.trim().toLowerCase(Locale.ROOT);
            roles = roles == null ? Map.of() : Map.copyOf(roles);
            targetPriority = targetPriority == null ? List.of() : List.copyOf(targetPriority);
            adds = adds == null ? List.of() : List.copyOf(adds);
            neverTarget = neverTarget == null ? List.of() : List.copyOf(neverTarget);
            fieldBosses = fieldBosses == null ? List.of() : List.copyOf(fieldBosses);
            markerItems = markerItems == null ? List.of() : List.copyOf(markerItems);
            stageMaps = stageMaps == null ? List.of() : List.copyOf(stageMaps);
            meleePriority = meleePriority == null ? List.of() : List.copyOf(meleePriority);
            entryItems = entryItems == null ? markerItems : List.copyOf(entryItems);
            keepOut = keepOut == null ? List.of() : List.copyOf(keepOut);
            focus = focus == null ? List.of() : List.copyOf(focus);
            attackKeepOut = attackKeepOut == null ? List.of() : List.copyOf(attackKeepOut);
            if (keepOutHpPercent < 0 || keepOutHpPercent > 100) {
                throw new IllegalArgumentException("keepOutHpPercent must be 0 to 100: " + keepOutHpPercent);
            }
            if (companionDamagePercent < 0 || companionDamagePercent > 5000) {
                throw new IllegalArgumentException("companionDamagePercent must be 0 to 5000: " + companionDamagePercent);
            }
            if (attackKeepOutHpPercent < 0 || attackKeepOutHpPercent > 100) {
                throw new IllegalArgumentException(
                        "attackKeepOutHpPercent must be 0 to 100: " + attackKeepOutHpPercent);
            }
        }

        /** A boss row with no last stand (every row before Pink Bean's run 17, 2026-10-16). */
        public Boss(String key, String label, Map<String, Integer> roles, List<Integer> targetPriority,
                    List<Integer> adds, List<Integer> neverTarget, List<Integer> fieldBosses,
                    boolean neverTargetFakes, List<Integer> markerItems, int minLevel, int deathWaitMap,
                    boolean randomSpots, List<Integer> stageMaps, String stagePortal,
                    List<Integer> meleePriority, int reentryWarpFrom, List<Integer> entryItems,
                    List<KeepOut> keepOut, List<Focus> focus, List<Integer> attackKeepOut, int keepOutHpPercent,
                    int companionDamagePercent, int attackKeepOutHpPercent) {
            this(key, label, roles, targetPriority, adds, neverTarget, fieldBosses, neverTargetFakes, markerItems,
                    minLevel, deathWaitMap, randomSpots, stageMaps, stagePortal, meleePriority, reentryWarpFrom,
                    entryItems, keepOut, focus, attackKeepOut, keepOutHpPercent, companionDamagePercent,
                    attackKeepOutHpPercent, null);
        }

        /** A boss row whose attack keep-out shares {@code keepOutHpPercent} (every row before Pink Bean's run 15). */
        public Boss(String key, String label, Map<String, Integer> roles, List<Integer> targetPriority,
                    List<Integer> adds, List<Integer> neverTarget, List<Integer> fieldBosses,
                    boolean neverTargetFakes, List<Integer> markerItems, int minLevel, int deathWaitMap,
                    boolean randomSpots, List<Integer> stageMaps, String stagePortal,
                    List<Integer> meleePriority, int reentryWarpFrom, List<Integer> entryItems,
                    List<KeepOut> keepOut, List<Focus> focus, List<Integer> attackKeepOut, int keepOutHpPercent,
                    int companionDamagePercent) {
            this(key, label, roles, targetPriority, adds, neverTarget, fieldBosses, neverTargetFakes, markerItems,
                    minLevel, deathWaitMap, randomSpots, stageMaps, stagePortal, meleePriority, reentryWarpFrom,
                    entryItems, keepOut, focus, attackKeepOut, keepOutHpPercent, companionDamagePercent, 0);
        }

        /**
         * This boss's threshold for the attack keep-out, as a percent of the member's max HP: its own
         * {@code attackKeepOutHpPercent}, else {@code keepOutHpPercent}, else 0 (the global
         * ATTACK_KEEP_OUT_HP_PERCENT).
         */
        public int attackThresholdPercent() {
            return attackKeepOutHpPercent > 0 ? attackKeepOutHpPercent : keepOutHpPercent;
        }

        /** A boss row whose companions hit its parts at their own strength (every row before Pink Bean's run 2). */
        public Boss(String key, String label, Map<String, Integer> roles, List<Integer> targetPriority,
                    List<Integer> adds, List<Integer> neverTarget, List<Integer> fieldBosses,
                    boolean neverTargetFakes, List<Integer> markerItems, int minLevel, int deathWaitMap,
                    boolean randomSpots, List<Integer> stageMaps, String stagePortal,
                    List<Integer> meleePriority, int reentryWarpFrom, List<Integer> entryItems,
                    List<KeepOut> keepOut, List<Focus> focus, List<Integer> attackKeepOut, int keepOutHpPercent) {
            this(key, label, roles, targetPriority, adds, neverTarget, fieldBosses, neverTargetFakes, markerItems,
                    minLevel, deathWaitMap, randomSpots, stageMaps, stagePortal, meleePriority, reentryWarpFrom,
                    entryItems, keepOut, focus, attackKeepOut, keepOutHpPercent, 0);
        }

        /**
         * The percent an INSIDE member's damage lines on one of this boss's parts are scaled to, 100 when the row sets
         * none. A part is a {@code targetPriority}, {@code meleePriority} or focus step id; adds keep the member's own
         * strength.
         */
        public int damagePercentOn(int mobId) {
            if (companionDamagePercent <= 0 || companionDamagePercent == 100) {
                return 100;
            }
            if (targetPriority.contains(mobId) || meleePriority.contains(mobId)) {
                return companionDamagePercent;
            }
            for (Focus step : focus) {
                if (step.mobIds().contains(mobId)) {
                    return companionDamagePercent;
                }
            }
            return 100;
        }

        /** A boss row that keeps the global keep-out thresholds (every row before Showa's run 4, 2026-10-15). */
        public Boss(String key, String label, Map<String, Integer> roles, List<Integer> targetPriority,
                    List<Integer> adds, List<Integer> neverTarget, List<Integer> fieldBosses,
                    boolean neverTargetFakes, List<Integer> markerItems, int minLevel, int deathWaitMap,
                    boolean randomSpots, List<Integer> stageMaps, String stagePortal,
                    List<Integer> meleePriority, int reentryWarpFrom, List<Integer> entryItems,
                    List<KeepOut> keepOut, List<Focus> focus, List<Integer> attackKeepOut) {
            this(key, label, roles, targetPriority, adds, neverTarget, fieldBosses, neverTargetFakes, markerItems,
                    minLevel, deathWaitMap, randomSpots, stageMaps, stagePortal, meleePriority, reentryWarpFrom,
                    entryItems, keepOut, focus, attackKeepOut, 0);
        }

        /** A boss row with no attack keep-out parts (every row before Showa's, 2026-10-15). */
        public Boss(String key, String label, Map<String, Integer> roles, List<Integer> targetPriority,
                    List<Integer> adds, List<Integer> neverTarget, List<Integer> fieldBosses,
                    boolean neverTargetFakes, List<Integer> markerItems, int minLevel, int deathWaitMap,
                    boolean randomSpots, List<Integer> stageMaps, String stagePortal,
                    List<Integer> meleePriority, int reentryWarpFrom, List<Integer> entryItems,
                    List<KeepOut> keepOut, List<Focus> focus) {
            this(key, label, roles, targetPriority, adds, neverTarget, fieldBosses, neverTargetFakes, markerItems,
                    minLevel, deathWaitMap, randomSpots, stageMaps, stagePortal, meleePriority, reentryWarpFrom,
                    entryItems, keepOut, focus, List.of());
        }

        /** A boss row with no focus steps (every row before Horntail's, 2026-10-14). */
        public Boss(String key, String label, Map<String, Integer> roles, List<Integer> targetPriority,
                    List<Integer> adds, List<Integer> neverTarget, List<Integer> fieldBosses,
                    boolean neverTargetFakes, List<Integer> markerItems, int minLevel, int deathWaitMap,
                    boolean randomSpots, List<Integer> stageMaps, String stagePortal,
                    List<Integer> meleePriority, int reentryWarpFrom, List<Integer> entryItems,
                    List<KeepOut> keepOut) {
            this(key, label, roles, targetPriority, adds, neverTarget, fieldBosses, neverTargetFakes, markerItems,
                    minLevel, deathWaitMap, randomSpots, stageMaps, stagePortal, meleePriority, reentryWarpFrom,
                    entryItems, keepOut, List.of());
        }

        /** A boss row before P20: its entry items are its marker items, and no keep-out boxes. */
        public Boss(String key, String label, Map<String, Integer> roles, List<Integer> targetPriority,
                    List<Integer> adds, List<Integer> neverTarget, List<Integer> fieldBosses,
                    boolean neverTargetFakes, List<Integer> markerItems, int minLevel, int deathWaitMap,
                    boolean randomSpots, List<Integer> stageMaps, String stagePortal,
                    List<Integer> meleePriority, int reentryWarpFrom) {
            this(key, label, roles, targetPriority, adds, neverTarget, fieldBosses, neverTargetFakes, markerItems,
                    minLevel, deathWaitMap, randomSpots, stageMaps, stagePortal, meleePriority, reentryWarpFrom, null,
                    List.of());
        }

        /** A boss row before P18: one battle map, one target priority for every member, no re-entry warp. */
        public Boss(String key, String label, Map<String, Integer> roles, List<Integer> targetPriority,
                    List<Integer> adds, List<Integer> neverTarget, List<Integer> fieldBosses,
                    boolean neverTargetFakes, List<Integer> markerItems, int minLevel, int deathWaitMap,
                    boolean randomSpots) {
            this(key, label, roles, targetPriority, adds, neverTarget, fieldBosses, neverTargetFakes, markerItems,
                    minLevel, deathWaitMap, randomSpots, List.of(), null, List.of(), 0);
        }

        /** A boss row before P15: its members wait where they come to after a death, and no random spots. */
        public Boss(String key, String label, Map<String, Integer> roles, List<Integer> targetPriority,
                    List<Integer> adds, List<Integer> neverTarget, List<Integer> fieldBosses,
                    boolean neverTargetFakes, List<Integer> markerItems, int minLevel) {
            this(key, label, roles, targetPriority, adds, neverTarget, fieldBosses, neverTargetFakes, markerItems,
                    minLevel, 0, false);
        }

        /** A boss with no level of its own (minLevel 0: every boss row before P14). */
        public Boss(String key, String label, Map<String, Integer> roles, List<Integer> targetPriority,
                    List<Integer> adds, List<Integer> neverTarget, List<Integer> fieldBosses,
                    boolean neverTargetFakes, List<Integer> markerItems) {
            this(key, label, roles, targetPriority, adds, neverTarget, fieldBosses, neverTargetFakes, markerItems, 0);
        }

        /** The mob id of the named part; throws when the catalog names none, so a typo fails at load. */
        public int role(String name) {
            Integer id = roles.get(name);
            if (id == null || id <= 0) {
                throw new IllegalStateException("boss rule " + key + " names no " + name);
            }
            return id;
        }
    }

    /**
     * P20: a box beside part {@code mobId}, from {@code fromDx} to {@code toDx} along x from the part (negative: to its
     * left), that ranged and magic members keep out of while the part lives.
     */
    public record KeepOut(int mobId, int fromDx, int toDx) {
        public KeepOut {
            if (fromDx > toDx) {
                throw new IllegalArgumentException("a keep-out box needs fromDx <= toDx");
            }
        }
    }

    /**
     * 2026-10-16: the event property that counts a run's deaths, and the count at which the boss's script calls its
     * last stand (Pink Bean: "fallenPlayers", 4; the expedition ends at 5).
     */
    public record LastStand(String property, int at) {
        public LastStand {
            if (property == null || property.isBlank() || at < 1) {
                throw new IllegalArgumentException("a last stand needs an event property and a count of at least 1");
            }
        }
    }

    /**
     * One step of a boss's focus order (Horntail, 2026-10-14): the parts every member hits while any of them lives,
     * before any later step's. {@code side} ("left", "right" or null) is the side of a deadly touch area a member keeps
     * to while the step runs (the Legs and the Tail from the left). {@code anchors} are ledges a member grinds from
     * while the step runs, for parts out of reach from the floor; empty for none.
     */
    public record Focus(List<Integer> mobIds, String side, List<Anchor> anchors) {
        public Focus {
            mobIds = mobIds == null ? List.of() : List.copyOf(mobIds);
            if (mobIds.isEmpty()) {
                throw new IllegalArgumentException("a focus step needs mob ids");
            }
            side = side == null || side.isBlank() ? null : side.trim().toLowerCase(Locale.ROOT);
            if (side != null && !side.equals("left") && !side.equals("right")) {
                throw new IllegalArgumentException("a focus step's side is left or right: " + side);
            }
            anchors = anchors == null ? List.of() : List.copyOf(anchors);
        }
    }

    /** A ledge (x, y on its foothold) a member grinds from, for the parts {@code mobIds} it reaches from there. */
    public record Anchor(int x, int y, List<Integer> mobIds) {
        public Anchor {
            mobIds = mobIds == null ? List.of() : List.copyOf(mobIds);
            if (mobIds.isEmpty()) {
                throw new IllegalArgumentException("a focus anchor needs the mob ids it reaches");
            }
        }
    }

    record Catalog(int schemaVersion, List<Boss> bosses) {
    }

    private AgentBossRules() {
    }

    public static List<Boss> all() {
        return bosses;
    }

    /** The rules of boss {@code key} (trimmed, any case), or null when the catalog has none. */
    public static Boss of(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String wanted = key.trim().toLowerCase(Locale.ROOT);
        for (Boss boss : bosses) {
            if (boss.key().equals(wanted)) {
                return boss;
            }
        }
        return null;
    }

    /**
     * P14: true when boss {@code key}'s rules name {@code mobId} as one of its fight's parts: a role, a target
     * priority part or an add.
     */
    public static boolean names(String key, int mobId) {
        Boss boss = of(key);
        return boss != null && (boss.targetPriority().contains(mobId) || boss.meleePriority().contains(mobId)
                || boss.adds().contains(mobId) || boss.roles().containsValue(mobId));
    }

    /** True when boss {@code key}'s rules list {@code mobId} as a part its run members never hit. */
    public static boolean neverTarget(String key, int mobId) {
        Boss boss = of(key);
        return boss != null && boss.neverTarget().contains(mobId);
    }

    /**
     * P12 G1: the key of the field group whose {@code fieldBosses} list {@code mobId} (field_victoria for Mano, say),
     * or null when no group lists it. The answer holds only on a map with no event.
     */
    public static String fieldKeyOf(int mobId) {
        FieldIndex index = fieldIndex; // the combat candidate filter asks for every hostile monster: no scan, no boxing
        int at = Arrays.binarySearch(index.ids(), mobId);
        return at < 0 ? null : index.keys()[at];
    }

    /** True when boss {@code key}'s rules say its run members never hit a fake part. */
    public static boolean neverTargetFakes(String key) {
        Boss boss = of(key);
        return boss != null && boss.neverTargetFakes();
    }

    /**
     * The level a companion needs for party boss {@code key} (P14: the event script's minLevel, 70 for Neo City),
     * 1 when the catalog names none. An expedition boss reads its expedition type instead
     * (AgentBossRunDefinition.minLevel).
     */
    public static int minLevel(String key) {
        Boss boss = of(key);
        return boss == null || boss.minLevel() <= 0 ? 1 : boss.minLevel();
    }

    /**
     * P15, decision 9: the map a member of boss {@code key}'s run walks to after it died in the fight and came to
     * elsewhere (Papulatus: the gate 220080000), there to wait for its owner; 0 when it waits where it came to.
     */
    public static int deathWaitMap(String key) {
        Boss boss = of(key);
        return boss == null ? 0 : Math.max(0, boss.deathWaitMap());
    }

    /** P15: true when boss {@code key}'s mirror strikes a random-spot attack only near its spots. */
    public static boolean randomSpots(String key) {
        Boss boss = of(key);
        return boss != null && boss.randomSpots();
    }

    /** P18: the event maps before boss {@code key}'s battle map, in order; empty for a fight on one map. */
    public static List<Integer> stageMaps(String key) {
        Boss boss = of(key);
        return boss == null ? List.of() : boss.stageMaps();
    }

    /** P18: the portal a member of boss {@code key}'s run takes from one stage map to the next; null for none. */
    public static String stagePortal(String key) {
        Boss boss = of(key);
        return boss == null || boss.stagePortal() == null || boss.stagePortal().isBlank() ? null : boss.stagePortal();
    }

    /**
     * P18, plan decision 11a: the map from which a member of boss {@code key}'s re-enter run, walking back, is moved
     * to the recruit map no route reaches (Horntail: Leafre 240000000); 0 for none.
     */
    public static int reentryWarpFrom(String key) {
        Boss boss = of(key);
        return boss == null ? 0 : Math.max(0, boss.reentryWarpFrom());
    }

    /**
     * P20: the items an owner carries in to start boss {@code key}'s fight (its {@code entryItems}, else its marker
     * items: Zakum's Eye of Fire, Showa's comb); empty when it needs none (Easy Balrog, Pink Bean).
     */
    public static List<Integer> entryItems(String key) {
        Boss boss = of(key);
        return boss == null ? List.of() : boss.entryItems();
    }

    /** P20: the keep-out boxes of boss {@code key}; empty when it names none. */
    public static List<KeepOut> keepOut(String key) {
        Boss boss = of(key);
        return boss == null ? List.of() : boss.keepOut();
    }

    /** The drops boss {@code key}'s run members leave on the floor; empty when the catalog lists none. */
    public static List<Integer> markerItems(String key) {
        Boss boss = of(key);
        return boss == null ? List.of() : boss.markerItems();
    }

    /** What a member grinds first, and what it falls back to. */
    public record Preferences(Set<Integer> preferred, Set<Integer> fallback) {
        public Preferences {
            preferred = Set.copyOf(preferred);
            fallback = Set.copyOf(fallback);
        }

        public boolean isEmpty() {
            return preferred.isEmpty() && fallback.isEmpty();
        }
    }

    /**
     * P12 G3: the target priority of {@code boss} over the mob ids alive now. The first {@code targetPriority} id that
     * is alive is preferred, and every other live id is the fallback (a later priority id, an add, any monster the
     * list does not name). A {@code neverTarget} id is in neither. With no priority id alive, every live id is
     * preferred. Pure.
     */
    public static Preferences preferences(Boss boss, Collection<Integer> liveMobIds) {
        return preferences(boss, liveMobIds, false);
    }

    /**
     * P18: as {@link #preferences(Boss, Collection)}, for a melee member by the boss's {@code meleePriority} when it
     * names one (Horntail's legs and tail first), else by {@code targetPriority}. Pure.
     */
    public static Preferences preferences(Boss boss, Collection<Integer> liveMobIds, boolean melee) {
        Set<Integer> live = new LinkedHashSet<>(liveMobIds);
        if (boss != null) {
            boss.neverTarget().forEach(live::remove);
        }
        List<Integer> priority = boss == null ? List.of()
                : melee && !boss.meleePriority().isEmpty() ? boss.meleePriority() : boss.targetPriority();
        Integer first = priority.stream().filter(live::contains).findFirst().orElse(null);
        if (first == null) {
            return new Preferences(live, Set.of());
        }
        live.remove(first);
        return new Preferences(Set.of(first), live);
    }

    /** The first focus step of {@code boss} with a part alive now ({@code liveMobIds}); null when none has. Pure. */
    public static Focus focusStep(Boss boss, Collection<Integer> liveMobIds) {
        if (boss == null) {
            return null;
        }
        for (Focus step : boss.focus()) {
            for (int id : step.mobIds()) {
                if (liveMobIds.contains(id)) {
                    return step;
                }
            }
        }
        return null;
    }

    /**
     * Focus order (2026-10-14): a member hits the live parts of {@code step} and nothing else of the boss; the boss's
     * live adds are the fallback, so it still answers a summon that touches it. Pure.
     */
    public static Preferences focusPreferences(Boss boss, Focus step, Collection<Integer> liveMobIds) {
        Set<Integer> preferred = new LinkedHashSet<>();
        for (int id : step.mobIds()) {
            if (liveMobIds.contains(id)) {
                preferred.add(id);
            }
        }
        Set<Integer> fallback = new LinkedHashSet<>();
        for (int id : boss.adds()) {
            if (liveMobIds.contains(id) && !preferred.contains(id)) {
                fallback.add(id);
            }
        }
        return new Preferences(preferred, fallback);
    }

    /** Replaces the catalog with {@code replacement}; tests only (null loads the shipped file again). */
    static void replaceForTest(List<Boss> replacement) {
        List<Boss> next = replacement == null ? load() : List.copyOf(replacement);
        fieldIndex = FieldIndex.of(next);
        bosses = next;
    }

    /**
     * The field-boss ids of every row, sorted, with the key of the first row that lists each ({@link #fieldKeyOf}).
     * Built with the catalog: the lookup is a binary search over ints.
     */
    private record FieldIndex(int[] ids, String[] keys) {
        static FieldIndex of(List<Boss> rows) {
            TreeMap<Integer, String> byId = new TreeMap<>();
            for (Boss boss : rows) {
                for (Integer id : boss.fieldBosses()) {
                    byId.putIfAbsent(id, boss.key());
                }
            }
            int[] ids = new int[byId.size()];
            String[] keys = new String[byId.size()];
            int i = 0;
            for (Map.Entry<Integer, String> row : byId.entrySet()) {
                ids[i] = row.getKey();
                keys[i++] = row.getValue();
            }
            return new FieldIndex(ids, keys);
        }
    }

    private static List<Boss> load() {
        try (InputStream input = AgentBossRules.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("missing boss rules catalog: " + RESOURCE);
            }
            Catalog catalog = MAPPER.readValue(input, Catalog.class);
            return catalog.bosses() == null ? List.of() : List.copyOf(catalog.bosses());
        } catch (IOException failure) {
            throw new IllegalStateException("could not load boss rules catalog: " + RESOURCE, failure);
        }
    }
}
