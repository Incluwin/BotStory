package server.life.autonomy;

import provider.Data;
import provider.DataEntity;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.DataType;
import provider.wz.WZFiles;
import server.life.MobSkill;
import server.life.MobSkillFactory;
import server.life.MobSkillType;
import tools.StringUtil;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Loads immutable monster action descriptions through the normal WZ provider path. */
public final class ServerMobActionCatalog {
    private static final DataProvider MOB_DATA = DataProviderFactory.getDataProvider(WZFiles.MOB);
    private static final Map<Integer, MonsterActions> CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, List<Integer>> RESOLVED_ANIMATIONS =
            new ConcurrentHashMap<>();
    private static final Map<Integer, List<Boolean>> JUMP_ATTACKS = new ConcurrentHashMap<>();
    /** A UOL frame that still points at a UOL after this many steps counts as no frame. */
    private static final int MAX_UOL_HOPS = 8;

    private ServerMobActionCatalog() {
    }

    public static MonsterActions forMob(int mobId) {
        return CACHE.computeIfAbsent(mobId, ServerMobActionCatalog::load);
    }

    private static MonsterActions load(int mobId) {
        Data mob = MOB_DATA.getData(StringUtil.getLeftPaddedStr(mobId + ".img", '0', 11));
        if (mob == null) {
            return new MonsterActions(List.of(), List.of());
        }
        Data actionSource = linkedActionSource(mob);

        List<BossAction.OrdinaryAttack> attacks = new ArrayList<>();
        for (int index = 0; ; index++) {
            Data attack = actionSource.getChildByPath("attack" + (index + 1));
            if (attack == null) {
                break;
            }
            Data info = attack.getChildByPath("info");
            int actionNumber = index + 1;
            int mpCost = DataTool.getIntConvert("conMP", info, 0);
            int impactDelay = DataTool.getIntConvert("attackAfter", info, 0);
            boolean magic = DataTool.getIntConvert("magic", info, 0) != 0;
            Point lt = DataTool.getPoint("range/lt", info, null);
            Point rb = DataTool.getPoint("range/rb", info, null);
            if (lt == null || rb == null) {
                Point center = DataTool.getPoint("range/sp", info, null);
                // 8800010 attack2 (r=-1000) is the only negative radius in Mob.wz; its size counts.
                int radius = Math.abs(DataTool.getIntConvert("range/r", info, 0));
                if (center != null && radius > 0) {
                    lt = new Point(center.x - radius, center.y - radius);
                    rb = new Point(center.x + radius, center.y + radius);
                }
            }
            attacks.add(new BossAction.OrdinaryAttack(
                    index, actionNumber, mpCost, impactDelay,
                    animationTime(attack), magic, copy(lt), copy(rb),
                    DataTool.getIntConvert("range/start", info, 0),
                    DataTool.getIntConvert("range/areaCount", info, 0),
                    DataTool.getIntConvert("range/attackCount", info, 0),
                    info != null && info.getChildByPath("deadlyAttack") != null,
                    DataTool.getIntConvert("PADamage", info, 0),
                    DataTool.getIntConvert("MADamage", info, 0),
                    DataTool.getIntConvert("disease", info, 0),
                    DataTool.getIntConvert("level", info, 0),
                    DataTool.getIntConvert("tremble", info, 0) != 0,
                    randomSpotSpacing(info)));
        }

        List<BossAction.Skill> skills = new ArrayList<>();
        Data skillInfo = mob.getChildByPath("info/skill");
        if (skillInfo != null) {
            for (Data entry : skillInfo.getChildren()) {
                int skillId = DataTool.getInt("skill", entry, 0);
                int level = DataTool.getInt("level", entry, 0);
                int actionNumber = Math.max(1, DataTool.getInt("action", entry, 1));
                MobSkillType type = MobSkillType.from(skillId).orElse(null);
                if (type == null || level <= 0) {
                    continue;
                }
                MobSkill skill = MobSkillFactory.getMobSkill(type, level).orElse(null);
                if (skill == null) {
                    continue;
                }
                Data animation = mob.getChildByPath("skill" + actionNumber);
                int animationMs = animationTime(animation);
                int effectDelay = DataTool.getInt("effectAfter", entry, animationMs);
                if (effectDelay <= 0) {
                    effectDelay = animationMs;
                }
                skills.add(new BossAction.Skill(
                        skill, actionNumber, effectDelay, animationMs,
                        copy(skill.getLt()), copy(skill.getRb())));
            }
        }
        return new MonsterActions(List.copyOf(attacks), List.copyOf(skills));
    }

    private static Data linkedActionSource(Data mob) {
        String link = DataTool.getString("info/link", mob, null);
        if (link == null || link.isBlank()) {
            return mob;
        }
        Data linked = MOB_DATA.getData(
                StringUtil.getLeftPaddedStr(link + ".img", '0', 11));
        return linked == null ? mob : linked;
    }

    private static int animationTime(Data animation) {
        if (animation == null) {
            return 0;
        }
        int total = 0;
        for (Data frame : animation.getChildren()) {
            total += Math.max(0, DataTool.getIntConvert("delay", frame, 0));
        }
        return total;
    }

    /**
     * P15: the spacing of an attack whose effect falls at random spots (info/effect0 randomPos, its effectDistance:
     * Papulatus's Clock attack3, 300); 0 for every other attack.
     */
    private static int randomSpotSpacing(Data info) {
        if (info == null || DataTool.getIntConvert("effect0/randomPos", info, 0) == 0) {
            return 0;
        }
        return Math.max(0, DataTool.getIntConvert("effect0/effectDistance", info, 0));
    }

    private static Point copy(Point point) {
        return point == null ? null : new Point(point);
    }

    /**
     * The full length of attack{index+1}'s animation, with UOL frames resolved to the frames they
     * point at. {@link BossAction#animationTimeMs()} adds only frames that carry their own delay,
     * so it stays as it is for the server-owned action locks. 0 for an unknown mob or attack.
     */
    public static int resolvedAnimationMs(int mobId, int index) {
        List<Integer> times = RESOLVED_ANIMATIONS.computeIfAbsent(
                mobId, ServerMobActionCatalog::loadResolvedAnimations);
        return index >= 0 && index < times.size() ? times.get(index) : 0;
    }

    /** True when attack{index+1} has info/jumpAttack set (a target in the air is not hit). */
    public static boolean jumpAttack(int mobId, int index) {
        List<Boolean> flags = JUMP_ATTACKS.computeIfAbsent(
                mobId, ServerMobActionCatalog::loadJumpAttacks);
        return index >= 0 && index < flags.size() && flags.get(index);
    }

    private static List<Integer> loadResolvedAnimations(int mobId) {
        List<Integer> times = new ArrayList<>();
        for (Data attack : attackData(mobId)) {
            times.add(resolvedAnimationTime(attack));
        }
        return List.copyOf(times);
    }

    private static List<Boolean> loadJumpAttacks(int mobId) {
        List<Boolean> flags = new ArrayList<>();
        for (Data attack : attackData(mobId)) {
            flags.add(DataTool.getIntConvert("info/jumpAttack", attack, 0) != 0);
        }
        return List.copyOf(flags);
    }

    private static List<Data> attackData(int mobId) {
        Data mob = MOB_DATA.getData(StringUtil.getLeftPaddedStr(mobId + ".img", '0', 11));
        if (mob == null) {
            return List.of();
        }
        Data actionSource = linkedActionSource(mob);
        List<Data> attacks = new ArrayList<>();
        for (int index = 0; ; index++) {
            Data attack = actionSource.getChildByPath("attack" + (index + 1));
            if (attack == null) {
                return attacks;
            }
            attacks.add(attack);
        }
    }

    private static int resolvedAnimationTime(Data animation) {
        int total = 0;
        for (Data frame : animation.getChildren()) {
            Data resolved = resolveUol(frame);
            if (resolved != null) {
                total += Math.max(0, DataTool.getIntConvert("delay", resolved, 0));
            }
        }
        return total;
    }

    /** Follows a UOL (a path relative to its own directory, such as "../stand/0") to its frame. */
    private static Data resolveUol(Data frame) {
        Data current = frame;
        for (int hop = 0; current != null && current.getType() == DataType.UOL; hop++) {
            if (hop == MAX_UOL_HOPS || !(current.getData() instanceof String path)) {
                return null;
            }
            current = relative(current.getParent(), path);
        }
        return current;
    }

    /** Walks a relative path one segment at a time, so a ".." above the image root gives null. */
    private static Data relative(DataEntity directory, String path) {
        DataEntity current = directory;
        for (String segment : path.split("/")) {
            if (!(current instanceof Data data)) {
                return null;
            }
            current = segment.equals("..") ? data.getParent() : data.getChildByPath(segment);
        }
        return current instanceof Data resolved ? resolved : null;
    }

    public record MonsterActions(
            List<BossAction.OrdinaryAttack> attacks,
            List<BossAction.Skill> skills
    ) {
    }
}
