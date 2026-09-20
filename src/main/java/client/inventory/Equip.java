/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as
 published by the Free Software Foundation version 3 as published by
 the Free Software Foundation. You may not use, modify or distribute
 this program under any other version of the GNU Affero General Public
 License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package client.inventory;

import client.Client;
import config.YamlConfig;
import constants.game.ExpTable;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import tools.PacketCreator;
import tools.Pair;
import tools.Randomizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class Equip extends Item {
    private static final Logger log = LoggerFactory.getLogger(Equip.class);

    public enum ScrollResult {

        FAIL(0), SUCCESS(1), CURSE(2);
        private int value = -1;

        ScrollResult(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    public enum StatUpgrade {

        incDEX(0), incSTR(1), incINT(2), incLUK(3),
        incMHP(4), incMMP(5), incPAD(6), incMAD(7),
        incPDD(8), incMDD(9), incEVA(10), incACC(11),
        incSpeed(12), incJump(13), incVicious(14), incSlot(15);
        private int value = -1;

        StatUpgrade(int value) {
            this.value = value;
        }
    }

    private byte upgradeSlots;
    private byte level, itemLevel;
    private short flag;
    private short str, dex, _int, luk, hp, mp, watk, matk, wdef, mdef, acc, avoid, hands, speed, jump, vicious;
    private float itemExp;
    private int ringid = -1;
    private int anvilItemId = 0;
    private boolean wear = false;
    private boolean isUpgradeable, isElemental = false;    // timeless or reverse, or any equip that could levelup on GMS for all effects

    public Equip(int id, short position) {
        this(id, position, 0);
    }

    public Equip(int id, short position, int slots) {
        super(id, position, (short) 1);
        this.upgradeSlots = (byte) slots;
        this.itemExp = 0;
        this.itemLevel = 1;

        this.isElemental = (ItemInformationProvider.getInstance().getEquipLevel(id, false) > 1);
    }

    @Override
    public Item copy() {
        Equip ret = new Equip(getItemId(), getPosition(), getUpgradeSlots());
        ret.str = str;
        ret.dex = dex;
        ret._int = _int;
        ret.luk = luk;
        ret.hp = hp;
        ret.mp = mp;
        ret.matk = matk;
        ret.mdef = mdef;
        ret.watk = watk;
        ret.wdef = wdef;
        ret.acc = acc;
        ret.avoid = avoid;
        ret.hands = hands;
        ret.speed = speed;
        ret.jump = jump;
        ret.flag = flag;
        ret.vicious = vicious;
        ret.upgradeSlots = upgradeSlots;
        ret.itemLevel = itemLevel;
        ret.itemExp = itemExp;
        ret.level = level;
        ret.anvilItemId = anvilItemId;
        ret.itemLog = new LinkedList<>(itemLog);
        ret.setOwner(getOwner());
        ret.setQuantity(getQuantity());
        ret.setExpiration(getExpiration());
        ret.setGiftFrom(getGiftFrom());
        return ret;
    }

    @Override
    public short getFlag() {
        return flag;
    }

    @Override
    public byte getItemType() {
        return 1;
    }

    public byte getUpgradeSlots() {
        return upgradeSlots;
    }

    public short getStr() {
        return str;
    }

    public short getDex() {
        return dex;
    }

    public short getInt() {
        return _int;
    }

    public short getLuk() {
        return luk;
    }

    public short getHp() {
        return hp;
    }

    public short getMp() {
        return mp;
    }

    public short getWatk() {
        return watk;
    }

    public short getMatk() {
        return matk;
    }

    public short getWdef() {
        return wdef;
    }

    public short getMdef() {
        return mdef;
    }

    public short getAcc() {
        return acc;
    }

    public short getAvoid() {
        return avoid;
    }

    public short getHands() {
        return hands;
    }

    public short getSpeed() {
        return speed;
    }

    public short getJump() {
        return jump;
    }

    public short getVicious() {
        return vicious;
    }

    @Override
    public void setFlag(short flag) {
        this.flag = flag;
    }

    public void setStr(short str) {
        this.str = str;
    }

    public void setDex(short dex) {
        this.dex = dex;
    }

    public void setInt(short _int) {
        this._int = _int;
    }

    public void setLuk(short luk) {
        this.luk = luk;
    }

    public void setHp(short hp) {
        this.hp = hp;
    }

    public void setMp(short mp) {
        this.mp = mp;
    }

    public void setWatk(short watk) {
        this.watk = watk;
    }

    public void setMatk(short matk) {
        this.matk = matk;
    }

    public void setWdef(short wdef) {
        this.wdef = wdef;
    }

    public void setMdef(short mdef) {
        this.mdef = mdef;
    }

    public void setAcc(short acc) {
        this.acc = acc;
    }

    public void setAvoid(short avoid) {
        this.avoid = avoid;
    }

    public void setHands(short hands) {
        this.hands = hands;
    }

    public void setSpeed(short speed) {
        this.speed = speed;
    }

    public void setJump(short jump) {
        this.jump = jump;
    }

    public void setVicious(short vicious) {
        this.vicious = vicious;
    }

    public void setUpgradeSlots(byte upgradeSlots) {
        this.upgradeSlots = upgradeSlots;
    }

    public byte getLevel() {
        return level;
    }

    public void setLevel(byte level) {
        this.level = level;
    }

    private static int getStatModifier(boolean isAttribute) {
        // each set of stat points grants a chance for a bonus stat point upgrade at equip level up.

        if (YamlConfig.config.server.USE_EQUIPMNT_LVLUP_POWER) {
            if (isAttribute) {
                return 2;
            } else {
                return 4;
            }
        } else {
            if (isAttribute) {
                return 4;
            } else {
                return 16;
            }
        }
    }

    private static int randomizeStatUpgrade(int top) {
        int limit = Math.min(top, YamlConfig.config.server.MAX_EQUIPMNT_LVLUP_STAT_UP);

        int poolCount = (limit * (limit + 1) / 2) + limit;
        int rnd = Randomizer.rand(0, poolCount);

        int stat = 0;
        if (rnd >= limit) {
            rnd -= limit;
            stat = 1 + (int) Math.floor((-1 + Math.sqrt((8 * rnd) + 1)) / 2);    // optimized randomizeStatUpgrade author: David A.
        }

        return stat;
    }

    private static boolean isPhysicalWeapon(int itemid) {
        Equip eqp = (Equip) ItemInformationProvider.getInstance().getEquipById(itemid);
        return eqp.getWatk() >= eqp.getMatk();
    }

    private boolean isNotWeaponAffinity(StatUpgrade name) {
        // Vcoc's idea - WATK/MATK expected gains lessens outside of weapon affinity (physical/magic)

        if (ItemConstants.isWeapon(this.getItemId())) {
            if (name.equals(StatUpgrade.incPAD)) {
                return !isPhysicalWeapon(this.getItemId());
            } else if (name.equals(StatUpgrade.incMAD)) {
                return isPhysicalWeapon(this.getItemId());
            }
        }

        return false;
    }

    private void getUnitStatUpgrade(List<Pair<StatUpgrade, Integer>> stats, StatUpgrade name, int curStat, boolean isAttribute) {
        isUpgradeable = true;

        int maxUpgrade = randomizeStatUpgrade((int) (1 + (curStat / (getStatModifier(isAttribute) * (isNotWeaponAffinity(name) ? 2.7 : 1)))));
        if (maxUpgrade == 0) {
            return;
        }

        stats.add(new Pair<>(name, maxUpgrade));
    }

    private static void getUnitSlotUpgrade(List<Pair<StatUpgrade, Integer>> stats, StatUpgrade name) {
        if (Math.random() < 0.1) {
            stats.add(new Pair<>(name, 1));  // 10% success on getting a slot upgrade.
        }
    }

    private void improveDefaultStats(List<Pair<StatUpgrade, Integer>> stats) {
        if (dex > 0) {
            getUnitStatUpgrade(stats, StatUpgrade.incDEX, dex, true);
        }
        if (str > 0) {
            getUnitStatUpgrade(stats, StatUpgrade.incSTR, str, true);
        }
        if (_int > 0) {
            getUnitStatUpgrade(stats, StatUpgrade.incINT, _int, true);
        }
        if (luk > 0) {
            getUnitStatUpgrade(stats, StatUpgrade.incLUK, luk, true);
        }
        if (hp > 0) {
            getUnitStatUpgrade(stats, StatUpgrade.incMHP, hp, false);
        }
        if (mp > 0) {
            getUnitStatUpgrade(stats, StatUpgrade.incMMP, mp, false);
        }
        if (watk > 0) {
            getUnitStatUpgrade(stats, StatUpgrade.incPAD, watk, false);
        }
        if (matk > 0) {
            getUnitStatUpgrade(stats, StatUpgrade.incMAD, matk, false);
        }
        if (wdef > 0) {
            getUnitStatUpgrade(stats, StatUpgrade.incPDD, wdef, false);
        }
        if (mdef > 0) {
            getUnitStatUpgrade(stats, StatUpgrade.incMDD, mdef, false);
        }
        if (avoid > 0) {
            getUnitStatUpgrade(stats, StatUpgrade.incEVA, avoid, false);
        }
        if (acc > 0) {
            getUnitStatUpgrade(stats, StatUpgrade.incACC, acc, false);
        }
        if (speed > 0) {
            getUnitStatUpgrade(stats, StatUpgrade.incSpeed, speed, false);
        }
        if (jump > 0) {
            getUnitStatUpgrade(stats, StatUpgrade.incJump, jump, false);
        }
    }

    public Map<StatUpgrade, Short> getStats() {
        Map<StatUpgrade, Short> stats = new HashMap<>(5);

        if (dex > 0) {
            stats.put(StatUpgrade.incDEX, dex);
        }
        if (str > 0) {
            stats.put(StatUpgrade.incSTR, str);
        }
        if (_int > 0) {
            stats.put(StatUpgrade.incINT, _int);
        }
        if (luk > 0) {
            stats.put(StatUpgrade.incLUK, luk);
        }
        if (hp > 0) {
            stats.put(StatUpgrade.incMHP, hp);
        }
        if (mp > 0) {
            stats.put(StatUpgrade.incMMP, mp);
        }
        if (watk > 0) {
            stats.put(StatUpgrade.incPAD, watk);
        }
        if (matk > 0) {
            stats.put(StatUpgrade.incMAD, matk);
        }
        if (wdef > 0) {
            stats.put(StatUpgrade.incPDD, wdef);
        }
        if (mdef > 0) {
            stats.put(StatUpgrade.incMDD, mdef);
        }
        if (avoid > 0) {
            stats.put(StatUpgrade.incEVA, avoid);
        }
        if (acc > 0) {
            stats.put(StatUpgrade.incACC, acc);
        }
        if (speed > 0) {
            stats.put(StatUpgrade.incSpeed, speed);
        }
        if (jump > 0) {
            stats.put(StatUpgrade.incJump, jump);
        }

        return stats;
    }

    public Pair<String, Pair<Boolean, Boolean>> gainStats(List<Pair<StatUpgrade, Integer>> stats) {
        boolean gotSlot = false, gotVicious = false;
        String lvupStr = "";
        int statUp, maxStat = YamlConfig.config.server.MAX_EQUIPMNT_STAT;
        for (Pair<StatUpgrade, Integer> stat : stats) {
            switch (stat.getLeft()) {
                case incDEX:
                    statUp = Math.min(stat.getRight(), maxStat - dex);
                    dex += statUp;
                    lvupStr += "+" + statUp + "DEX ";
                    break;
                case incSTR:
                    statUp = Math.min(stat.getRight(), maxStat - str);
                    str += statUp;
                    lvupStr += "+" + statUp + "STR ";
                    break;
                case incINT:
                    statUp = Math.min(stat.getRight(), maxStat - _int);
                    _int += statUp;
                    lvupStr += "+" + statUp + "INT ";
                    break;
                case incLUK:
                    statUp = Math.min(stat.getRight(), maxStat - luk);
                    luk += statUp;
                    lvupStr += "+" + statUp + "LUK ";
                    break;
                case incMHP:
                    statUp = Math.min(stat.getRight(), maxStat - hp);
                    hp += statUp;
                    lvupStr += "+" + statUp + "HP ";
                    break;
                case incMMP:
                    statUp = Math.min(stat.getRight(), maxStat - mp);
                    mp += statUp;
                    lvupStr += "+" + statUp + "MP ";
                    break;
                case incPAD:
                    statUp = Math.min(stat.getRight(), maxStat - watk);
                    watk += statUp;
                    lvupStr += "+" + statUp + "WATK ";
                    break;
                case incMAD:
                    statUp = Math.min(stat.getRight(), maxStat - matk);
                    matk += statUp;
                    lvupStr += "+" + statUp + "MATK ";
                    break;
                case incPDD:
                    statUp = Math.min(stat.getRight(), maxStat - wdef);
                    wdef += statUp;
                    lvupStr += "+" + statUp + "WDEF ";
                    break;
                case incMDD:
                    statUp = Math.min(stat.getRight(), maxStat - mdef);
                    mdef += statUp;
                    lvupStr += "+" + statUp + "MDEF ";
                    break;
                case incEVA:
                    statUp = Math.min(stat.getRight(), maxStat - avoid);
                    avoid += statUp;
                    lvupStr += "+" + statUp + "AVOID ";
                    break;
                case incACC:
                    statUp = Math.min(stat.getRight(), maxStat - acc);
                    acc += statUp;
                    lvupStr += "+" + statUp + "ACC ";
                    break;
                case incSpeed:
                    statUp = Math.min(stat.getRight(), maxStat - speed);
                    speed += statUp;
                    lvupStr += "+" + statUp + "SPEED ";
                    break;
                case incJump:
                    statUp = Math.min(stat.getRight(), maxStat - jump);
                    jump += statUp;
                    lvupStr += "+" + statUp + "JUMP ";
                    break;

                case incVicious:
                    vicious -= stat.getRight();
                    gotVicious = true;
                    break;
                case incSlot:
                    upgradeSlots += stat.getRight();
                    gotSlot = true;
                    break;
            }
        }

        return new Pair<>(lvupStr, new Pair<>(gotSlot, gotVicious));
    }

    // Collector for deferred per-equip level-up events. Character.increaseEquipExp populates
    // this across an equipped-list pass, then emits one animation + one summary instead of
    // firing N animations when a fresh NPC-bought set levels in lockstep.
    public static final class LevelUpBatch {
        public final List<Equip> items = new ArrayList<>();
        public final List<Byte> toLevels = new ArrayList<>();
        public final List<String> summaries = new ArrayList<>();

        public void add(Equip item, byte toLevel, String summary) {
            items.add(item);
            toLevels.add(toLevel);
            summaries.add(summary);
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }

        public int size() {
            return items.size();
        }
    }

    // --- PLAN.md equipment-leveling system (deterministic, tiered, cap at L30) ---

    public static int getTieredMaxLevel(int reqLevel) {
        return reqLevel < 10 ? 0 : 30;
    }

    /**
     * Item-aware max-level cap. Evolving Ring chain entries override the reqLevel-based tier
     * (their WZ reqLevel is 0 so the wear-warning popup is suppressed, but they still need
     * a 30-level cap to drive the auto-evolution). The Fully Evolved Ring returns 0 here
     * regardless and therefore cannot accumulate item-exp.
     */
    public static int getEffectiveMaxLevel(int itemId, int reqLevel) {
        if (EvolvingRing.isChainRing(itemId)) {
            return EvolvingRing.getMaxLevelFor(itemId);
        }
        return getTieredMaxLevel(reqLevel);
    }

    // Percent multiplier applied to every level's EXP requirement (stored x100 to avoid floats).
    // 0.5x / 1.33x / 2.67x / 5.33x; targets ~1.5h / 4h / 8h / 16h to reach L30 on a 10x server.
    private static int equipTierMultiplierX100(int reqLevel) {
        if (reqLevel < 10)  return 0;
        if (reqLevel < 31)  return 50;
        if (reqLevel < 71)  return 133;
        if (reqLevel < 101) return 267;
        return 533;
    }

    /**
     * Effective tier multiplier for an item. Standard upgradeable equips delegate
     * to the WZ-reqLevel-based tier table.
     *
     * Evolving Ring chain entries used to override this with bespoke 50/133/533
     * multipliers, but as of the rev-2 pacing the chain bypasses the entire
     * mastery/tier formula via getEffectiveExpForLevel + gainItemExp branches —
     * so this method now never sees evolving-ring IDs in practice.
     */
    private static int effectiveTierMultiplierX100(int itemId, int reqLevel) {
        return equipTierMultiplierX100(reqLevel);
    }

    private static int tierIndex(int reqLevel) {
        if (reqLevel < 10)  return -1;
        if (reqLevel < 31)  return 0;
        if (reqLevel < 71)  return 1;
        if (reqLevel < 101) return 2;
        return 3;
    }

    private static int getTieredExpForLevel(int itemLevel, int reqLevel) {
        int base = ExpTable.getEquipExpNeededForLevel(itemLevel);
        int mult = equipTierMultiplierX100(reqLevel);
        return mult == 100 ? base : (int) Math.max(1L, (long) base * mult / 100);
    }

    private static int getEffectiveExpForLevel(int itemId, int itemLevel, int reqLevel) {
        if (EvolvingRing.isLevelable(itemId)) {
            // Flat per-level threshold within the tier — total tier exp / 28 transitions.
            // See EvolvingRing.getExpPerLevel + the class docstring.
            return EvolvingRing.getExpPerLevel(itemId);
        }
        int base = ExpTable.getEquipExpNeededForLevel(itemLevel);
        int mult = effectiveTierMultiplierX100(itemId, reqLevel);
        return mult == 100 ? base : (int) Math.max(1L, (long) base * mult / 100);
    }

    // Levels at which the equipment grants +1 STR/DEX/INT/LUK (spread to avoid instant capping).
    private static boolean isBaseStatMilestone(int itemLevel, int reqLevel) {
        int tier = tierIndex(reqLevel);
        switch (tier) {
            case 0: return itemLevel == 15 || itemLevel == 30;
            case 1: return itemLevel == 10 || itemLevel == 20 || itemLevel == 30;
            case 2: return itemLevel == 7  || itemLevel == 15 || itemLevel == 22 || itemLevel == 30;
            case 3: return itemLevel == 5  || itemLevel == 10 || itemLevel == 15 ||
                           itemLevel == 20 || itemLevel == 25 || itemLevel == 30;
            default: return false;
        }
    }

    // Weapon-only: +1 WA / +2 MA at these milestones (L71+ items).
    private static boolean isWeaponAttackMilestone(int itemLevel, int reqLevel) {
        int tier = tierIndex(reqLevel);
        if (tier < 2) return false;
        if (tier == 2) return itemLevel == 15 || itemLevel == 30;
        return itemLevel == 10 || itemLevel == 20 || itemLevel == 30;
    }

    private enum EquipCategory {
        HAT, FACE, EYE, EARRING, TOP, OVERALL, BOTTOM, SHOES, GLOVES,
        SHIELD, CAPE, RING, PENDANT, BELT, MEDAL, SHOULDER, WEAPON, OTHER
    }

    private static EquipCategory categoryOf(int itemId) {
        int p = itemId / 10000;
        switch (p) {
            case 100: return EquipCategory.HAT;
            case 101: return EquipCategory.FACE;
            case 102: return EquipCategory.EYE;
            case 103: return EquipCategory.EARRING;
            case 104: return EquipCategory.TOP;
            case 105: return EquipCategory.OVERALL;
            case 106: return EquipCategory.BOTTOM;
            case 107: return EquipCategory.SHOES;
            case 108: return EquipCategory.GLOVES;
            case 109: return EquipCategory.SHIELD;
            case 110: return EquipCategory.CAPE;
            case 111: return EquipCategory.RING;
            case 112: return EquipCategory.PENDANT;
            case 113: return EquipCategory.BELT;
            case 114: return EquipCategory.MEDAL;
            case 115: return EquipCategory.SHOULDER;
            default:  break;
        }
        if (p >= 130 && p <= 169) return EquipCategory.WEAPON;
        return EquipCategory.OTHER;
    }

    // reqJob flag: 0 = any, 1 = warrior, 2 = magician, 4 = archer, 8 = thief, 16 = pirate.
    private static boolean isMagicianEquip(int itemId) {
        try {
            Integer reqJob = ItemInformationProvider.getInstance().getEquipStats(itemId).get("reqJob");
            return reqJob != null && (reqJob & 2) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    // Per-slot bonus granted every level (amounts scale with tier).
    private List<Pair<StatUpgrade, Integer>> categoryBonusForLevel(int reqLevel, int newLevel) {
        List<Pair<StatUpgrade, Integer>> out = new LinkedList<>();
        int tier = tierIndex(reqLevel);
        if (tier < 0) return out;

        final int[] hpMp      = { 5, 9, 17, 24 };
        final int[] defense   = { 2, 4,  9, 13 };
        final int[] accAvoid  = { 1, 1,  2,  2 };
        final int[] shieldHalf = { 1, 2, 5, 7 };

        switch (categoryOf(this.getItemId())) {
            case HAT:
            case BELT:
                out.add(new Pair<>(StatUpgrade.incMHP, hpMp[tier]));
                break;
            case TOP:
            case OVERALL:
            case BOTTOM:
                StatUpgrade defStat = isMagicianEquip(this.getItemId()) ? StatUpgrade.incMDD : StatUpgrade.incPDD;
                out.add(new Pair<>(defStat, defense[tier]));
                break;
            case SHOES:
                if (newLevel % 2 == 0) out.add(new Pair<>(StatUpgrade.incSpeed, 1));
                break;
            case SHOULDER:
                if (newLevel % 2 == 0) out.add(new Pair<>(StatUpgrade.incJump, 1));
                break;
            case GLOVES:
            case EYE:
                out.add(new Pair<>(StatUpgrade.incACC, accAvoid[tier]));
                break;
            case CAPE:
            case FACE:
                out.add(new Pair<>(StatUpgrade.incEVA, accAvoid[tier]));
                break;
            case EARRING:
            case PENDANT:
                out.add(new Pair<>(StatUpgrade.incMMP, hpMp[tier]));
                break;
            case SHIELD:
                out.add(new Pair<>(StatUpgrade.incPDD, shieldHalf[tier]));
                out.add(new Pair<>(StatUpgrade.incMDD, shieldHalf[tier]));
                break;
            case RING:
                if (newLevel % 5 == 0) {
                    out.add(new Pair<>(StatUpgrade.incSTR, 1));
                    out.add(new Pair<>(StatUpgrade.incDEX, 1));
                    out.add(new Pair<>(StatUpgrade.incINT, 1));
                    out.add(new Pair<>(StatUpgrade.incLUK, 1));
                }
                break;
            case MEDAL:
                if (newLevel % 6 == 0) {
                    out.add(new Pair<>(StatUpgrade.incSTR, 1));
                    out.add(new Pair<>(StatUpgrade.incDEX, 1));
                    out.add(new Pair<>(StatUpgrade.incINT, 1));
                    out.add(new Pair<>(StatUpgrade.incLUK, 1));
                }
                break;
            case WEAPON:
            case OTHER:
                // Weapon WA/MA is handled via isWeaponAttackMilestone, not category bonus.
                break;
        }
        return out;
    }

    private void gainLevel(Client c) {
        gainLevel(c, null);
    }

    private void gainLevel(Client c, LevelUpBatch batch) {
        itemLevel++;

        int reqLevel = ItemInformationProvider.getInstance().getEquipLevelReq(this.getItemId());
        List<Pair<StatUpgrade, Integer>> stats = new LinkedList<>();

        if (EvolvingRing.isLevelable(this.getItemId())) {
            // Evolving Ring chain: deterministic +1 STR/DEX/INT/LUK every level, plus a
            // +1 milestone bonus at item-level 15 so each tier still grants the original
            // +29 per stat across 28 level-ups (CAP_LEVEL went 30 → 29 in the rev-2
            // pacing change; without this milestone the ceiling would drift down by 1
            // per tier × 4 stats × 3 tiers = 12 stat points lost). Reaching CAP_LEVEL
            // triggers transmutation in gainItemExp.
            int perLevel = (itemLevel == 15) ? 2 : 1;
            stats.add(new Pair<>(StatUpgrade.incSTR, perLevel));
            stats.add(new Pair<>(StatUpgrade.incDEX, perLevel));
            stats.add(new Pair<>(StatUpgrade.incINT, perLevel));
            stats.add(new Pair<>(StatUpgrade.incLUK, perLevel));
        } else {
            if (isBaseStatMilestone(itemLevel, reqLevel)) {
                stats.add(new Pair<>(StatUpgrade.incSTR, 1));
                stats.add(new Pair<>(StatUpgrade.incDEX, 1));
                stats.add(new Pair<>(StatUpgrade.incINT, 1));
                stats.add(new Pair<>(StatUpgrade.incLUK, 1));
            }
            if (ItemConstants.isWeapon(this.getItemId()) && isWeaponAttackMilestone(itemLevel, reqLevel)) {
                stats.add(new Pair<>(StatUpgrade.incPAD, 1));
                stats.add(new Pair<>(StatUpgrade.incMAD, 2));
            }
            stats.addAll(categoryBonusForLevel(reqLevel, itemLevel));
        }

        Pair<String, Pair<Boolean, Boolean>> res = this.gainStats(stats);
        String statSummary = res.getLeft();
        String itemName = ItemInformationProvider.getInstance().getName(this.getItemId());
        String lvupStr = "'" + itemName + "' is now level " + itemLevel + "! " + statSummary;

        if (batch != null) {
            batch.add(this, itemLevel, lvupStr);
        } else {
            String showStr = "#e'" + itemName + "'#b is now #elevel #r" + itemLevel + "#k#b!";
            c.getPlayer().equipChanged();
            showLevelupMessage(showStr, c);
            c.getPlayer().dropMessage(6, lvupStr);
            c.sendPacket(PacketCreator.showEquipmentLevelUp());
            c.getPlayer().getMap().broadcastPacket(c.getPlayer(), PacketCreator.showForeignEffect(c.getPlayer().getId(), 15));
            c.getPlayer().forceUpdateItem(this);
        }
    }

    public int getItemExp() {
        return (int) itemExp;
    }

    private static double normalizedMasteryExp(int reqLevel) {
        // Conversion factor between mob exp and equip exp gain. Through many calculations, the expected for equipment levelup
        // from level 1 to 2 is killing about 100~200 mobs of the same level range, on a 1x EXP rate scenario.

        if (reqLevel < 5) {
            return 42;
        } else if (reqLevel >= 78) {
            return Math.max((10413.648 * Math.exp(reqLevel * 0.03275)), 15);
        } else if (reqLevel >= 38) {
            return Math.max((4985.818 * Math.exp(reqLevel * 0.02007)), 15);
        } else if (reqLevel >= 18) {
            return Math.max((248.219 * Math.exp(reqLevel * 0.11093)), 15);
        } else {
            return Math.max(((1334.564 * Math.log(reqLevel)) - 1731.976), 15);
        }
    }

    public synchronized void gainItemExp(Client c, int gain) {
        gainItemExp(c, gain, null);
    }

    public synchronized void gainItemExp(Client c, int gain, LevelUpBatch batch) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        if (ii.isCash(this.getItemId())) {
            return;
        }
        // Evolving Ring chain entries don't carry the canonical isUpgradeable bit (their WZ has
        // info/level so the client tooltip shows the EXP bar, but ItemInformationProvider doesn't
        // tag them as Timeless/Reverse). We bypass the upgradeable check for chain rings; the
        // getEffectiveMaxLevel cap below still gates them, and Fully Evolved returns 0 cap.
        if (!EvolvingRing.isLevelable(this.getItemId()) && !ii.isUpgradeable(this.getItemId())) {
            return;
        }

        int reqLevel = ii.getEquipLevelReq(this.getItemId());
        int equipMaxLevel = getEffectiveMaxLevel(this.getItemId(), reqLevel);
        if (equipMaxLevel == 0 || itemLevel >= equipMaxLevel) {
            return;
        }
        // Kill-switch: set USE_EQUIPMNT_LVLUP <= 0 to disable item leveling entirely.
        // (Repurposed from the old random-roll cap; see PLAN.md equipment-leveling section.)
        if (YamlConfig.config.server.USE_EQUIPMNT_LVLUP <= 0) {
            return;
        }

        float baseExpGain;
        float masteryModifier;
        float jitter;
        if (EvolvingRing.isLevelable(this.getItemId())) {
            // Evolving Ring chain bypasses the standard element/mastery/jitter formula
            // entirely. Ring exp == player exp 1:1 so total-to-fully-evolve lines up with
            // the player-exp-1-to-160 target (see EvolvingRing class docstring).
            // Jitter is intentionally absent — pacing is deterministic and predictable
            // because the ring is meant to track player level, not feel like a slot machine.
            masteryModifier = 1.0f;
            jitter = 1.0f;
            baseExpGain = gain;
        } else {
            // Standard upgradeable-equip path (Timeless / Reverse, etc.).
            //
            // Key the mastery divisor to the wearer's level (clamped to reqLevel minimum), not the
            // equip's reqLevel, so over-leveled gear (e.g. Tiger Talon reqLvl 20 at ToT Memory Monks)
            // can't exploit a tiny divisor against huge mob-exp. Intended-pace use is unchanged since
            // playerLevel == reqLevel in that case. Ceiling at 120 so level-200 players (no further
            // progression to exploit) still get a usable equip-exp pace — the low-req clamp above
            // still blocks twink exploits against the 120 ceiling.
            int masteryLevel = Math.min(Math.max(c.getPlayer().getLevel(), reqLevel), 120);
            masteryModifier = (float) (YamlConfig.config.server.EQUIP_EXP_RATE * ExpTable.getExpNeededForLevel(1))
                                    / (float) normalizedMasteryExp(masteryLevel);
            float elementModifier = (isElemental) ? 0.85f : 0.6f;
            jitter = 0.90f + (Randomizer.nextFloat() * 0.20f);  // ±10% per-kill variance, expectation 1.0

            baseExpGain = gain * elementModifier * masteryModifier * jitter;
        }
        itemExp += baseExpGain;

        int expNeeded = getEffectiveExpForLevel(this.getItemId(), itemLevel, reqLevel);

        if (YamlConfig.config.server.USE_DEBUG_SHOW_INFO_EQPEXP) {
            log.debug("{} -> EXP Gain: {}, Mastery: {}, Jitter: {}, Base gain: {}, exp: {} / {}",
                    ii.getName(getItemId()), gain, masteryModifier, jitter, baseExpGain, itemExp, expNeeded);
        }

        while (itemExp >= expNeeded) {
            itemExp -= expNeeded;
            gainLevel(c, batch);

            if (itemLevel >= equipMaxLevel) {
                itemExp = 0.0f;
                break;
            }

            expNeeded = getEffectiveExpForLevel(this.getItemId(), itemLevel, reqLevel);
        }

        // Evolving Ring transmutation: when a chain ring hits its cap, replace it in the equipped
        // slot with the next tier (level 1, base stats only — accumulated +X all-stats from this
        // ring don't transfer; the next tier's tooltip base stats already reflect the cumulative
        // progress per the design spec). Done after the level loop so the cap-level animation/
        // chat line still fires for the just-completed ring.
        if (EvolvingRing.isLevelable(this.getItemId()) && itemLevel >= equipMaxLevel) {
            transmuteToNextTier(c);
        }

        if (batch == null) {
            c.getPlayer().forceUpdateItem(this);
        }
    }

    /**
     * Replace this Evolving Ring chain entry with its successor in the equipped slot.
     * Quietly no-ops if anything looks wrong (item not equipped, next tier missing in WZ, etc.).
     * The successor starts at item-level 1 with WZ baseline stats; no exp carries over.
     */
    private void transmuteToNextTier(Client c) {
        int nextId = EvolvingRing.nextRingId(this.getItemId());
        if (nextId < 0) {
            return;
        }

        client.Character chr = c.getPlayer();
        if (chr == null) {
            return;
        }

        Inventory equipped = chr.getInventory(InventoryType.EQUIPPED);
        // Must currently be in EQUIPPED at this position to swap in-place.
        short slot = this.getPosition();
        if (slot >= 0 || equipped.getItem(slot) != this) {
            return;
        }

        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Item nextItem;
        try {
            nextItem = ii.getEquipById(nextId);
        } catch (Exception e) {
            log.warn("Evolving Ring transmute: failed to instantiate {} -> {}", this.getItemId(), nextId, e);
            return;
        }
        if (!(nextItem instanceof Equip)) {
            log.warn("Evolving Ring transmute: ii.getEquipById({}) returned non-Equip; aborting", nextId);
            return;
        }
        Equip nextRing = (Equip) nextItem;
        nextRing.setPosition(slot);
        nextRing.wear(true);

        equipped.lockInventory();
        try {
            chr.unequippedItem(this);
            equipped.removeSlot(slot);
            chr.equippedItem(nextRing);
            equipped.addItemFromDB(nextRing);
        } finally {
            equipped.unlockInventory();
        }

        chr.equipChanged();
        chr.forceUpdateItem(nextRing);

        String oldName = ii.getName(this.getItemId());
        String newName = ii.getName(nextId);
        if (oldName == null) oldName = "Ring";
        if (newName == null) newName = "Ring";
        chr.dropMessage(6, "Your " + oldName + " has evolved into " + newName + "!");
        c.sendPacket(PacketCreator.showEquipmentLevelUp());
        chr.getMap().broadcastPacket(chr, PacketCreator.showForeignEffect(chr.getId(), 15));
    }

    private boolean reachedMaxLevel() {
        int reqLevel = ItemInformationProvider.getInstance().getEquipLevelReq(getItemId());
        int cap = getEffectiveMaxLevel(getItemId(), reqLevel);
        return cap == 0 || itemLevel >= cap;
    }

    /**
     * Maxed-elite all-stat bonus eligibility. Each qualifying piece equipped grants
     * +1% STR/DEX/INT/LUK in {@link client.Character#recalcEquipStats()}.
     *
     * Qualifies if:
     *   - This is the Fully Evolved Ring (terminal state; treated as permanently maxed), OR
     *   - This item has reqLevel >= 71 (tier 3/4 in 1-indexed user naming, internal tierIndex
     *     2 or 3) AND has actually reached its level cap.
     */
    public boolean qualifiesForMaxedAllStatBonus() {
        int itemId = getItemId();
        if (itemId == EvolvingRing.FULLY_EVOLVED_RING) {
            return true;
        }
        int reqLevel = ItemInformationProvider.getInstance().getEquipLevelReq(itemId);
        if (reqLevel < 71) {
            return false;
        }
        int cap = getEffectiveMaxLevel(itemId, reqLevel);
        return cap > 0 && itemLevel >= cap;
    }

    public String showEquipFeatures(Client c) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        if (ii.isCash(this.getItemId())) {
            return "";
        }
        if (!ii.isUpgradeable(this.getItemId())) {
            return "";
        }

        String eqpName = ii.getName(getItemId());
        int reqLevel = ii.getEquipLevelReq(getItemId());
        String eqpInfo = reachedMaxLevel() ? " #e#rMAX LEVEL#k#n" : (" EXP: #e#b" + (int) itemExp + "#k#n / " + getTieredExpForLevel(itemLevel, reqLevel));
        if (qualifiesForMaxedAllStatBonus()) {
            eqpInfo += "  #e#g+1% ALL STAT#k#n";
        }

        return "'" + eqpName + "' -> LV: #e#b" + itemLevel + "#k#n    " + eqpInfo + "\r\n";
    }

    private static void showLevelupMessage(String msg, Client c) {
        c.getPlayer().showHint(msg, 300);
    }

    public void setItemExp(int exp) {
        this.itemExp = exp;
    }

    public void setItemLevel(byte level) {
        this.itemLevel = level;
    }

    @Override
    public void setQuantity(short quantity) {
        if (quantity < 0 || quantity > 1) {
            throw new RuntimeException("Setting the quantity to " + quantity + " on an equip (itemid: " + getItemId() + ")");
        }
        super.setQuantity(quantity);
    }

    public void setUpgradeSlots(int i) {
        this.upgradeSlots = (byte) i;
    }

    public void setVicious(int i) {
        this.vicious = (short) i;
    }

    public int getRingId() {
        return ringid;
    }

    public void setRingId(int id) {
        this.ringid = id;
    }

    public boolean isWearing() {
        return wear;
    }

    public void wear(boolean yes) {
        wear = yes;
    }

    public byte getItemLevel() {
        return itemLevel;
    }

    public int getAnvilItemId() {
        return anvilItemId;
    }

    public void setAnvilItemId(int id) {
        this.anvilItemId = id;
    }
}