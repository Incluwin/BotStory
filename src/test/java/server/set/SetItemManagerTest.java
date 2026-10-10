package server.set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SetItemManagerTest {

    @BeforeEach
    void reset() {
        // Each test builds its own config to avoid order-dependency.
        SetItemManager.installConfig(new SetItemsConfig());
    }

    @Test
    void emptyEquipmentReturnsEmptyBonus() {
        SetItemManager.installConfig(buildWarriorTimelessOnly());
        SetBonusTotal b = SetItemManager.computeFromItemIds(Collections.emptyList());
        assertSame(SetBonusTotal.EMPTY, b);
    }

    @Test
    void twoPieceThresholdAppliesTimelessBonus() {
        SetItemManager.installConfig(buildWarriorTimelessOnly());
        // Hat (1002776) + Overall (1052155) = 2 pieces
        SetBonusTotal b = SetItemManager.computeFromItemIds(Arrays.asList(1002776, 1052155));
        // 2pc: +15 allStats, +5 WA, +5 MA
        assertEquals(15, b.str);
        assertEquals(15, b.dex);
        assertEquals(15, b.int_);
        assertEquals(15, b.luk);
        assertEquals(5, b.watk);
        assertEquals(5, b.matk);
        assertEquals(1, b.activeSets.size());
        assertEquals(2, b.activeSets.get(0).pieces);
    }

    @Test
    void threePiecesStackCumulatively() {
        SetItemManager.installConfig(buildWarriorTimelessOnly());
        // Hat + Overall + Gloves = 3 pieces
        SetBonusTotal b = SetItemManager.computeFromItemIds(Arrays.asList(1002776, 1052155, 1082234));
        // 2pc +15 allStats, +5 WA, +5 MA; 3pc +10 allStats. Cumulative: +25 allStats, +5 WA, +5 MA
        assertEquals(25, b.str);
        assertEquals(25, b.dex);
        assertEquals(25, b.int_);
        assertEquals(25, b.luk);
        assertEquals(5, b.watk);
        assertEquals(5, b.matk);
    }

    @Test
    void sevenPieceFullTimelessMatchesPlanTotals() {
        SetItemManager.installConfig(buildWarriorTimelessOnly());
        // 7 distinct slots: hat, overall, gloves, shoes, shield, cape, weapon
        SetBonusTotal b = SetItemManager.computeFromItemIds(
                Arrays.asList(1002776, 1052155, 1082234, 1072355, 1092058, 1102172, 1402046));
        // PLAN.md Tier 4 cumulative at 7pc: +60 all stats, +55 WA/MA
        assertEquals(60, b.str);
        assertEquals(60, b.dex);
        assertEquals(60, b.int_);
        assertEquals(60, b.luk);
        assertEquals(55, b.watk);
        assertEquals(55, b.matk);
        // 7pc: cumulative wdef (50+50+25+25)=150, acc (10+5+10+15)=40, avoid 40, speed 10, jump 10, bossDmg 10
        assertEquals(150, b.wdef);
        assertEquals(40, b.acc);
        assertEquals(40, b.avoid);
        assertEquals(10, b.speed);
        assertEquals(10, b.jump);
        assertEquals(10, b.bossDamagePct);
    }

    @Test
    void mixingTimelessAndReverseCountsAsOneSet() {
        SetItemManager.installConfig(buildWarriorWithReverse());
        // Timeless hat + Reverse overall = 2 pieces of same set
        SetBonusTotal b = SetItemManager.computeFromItemIds(Arrays.asList(1002776, 1052160));
        assertEquals(1, b.activeSets.size());
        assertEquals(2, b.activeSets.get(0).pieces);
        assertEquals(15, b.str);
    }

    @Test
    void itemInTwoSetsCountsForBoth() {
        // Build Warrior + Magician sets that share Timeless Moonlight cape (1102172).
        SetItemsConfig cfg = new SetItemsConfig();
        cfg.sets.add(makeWarriorSet());
        cfg.sets.add(makeMagicianSet());
        SetItemManager.installConfig(cfg);
        // Wearing only the cape: 1 piece in each set, under any threshold -> no bonuses active
        SetBonusTotal b1 = SetItemManager.computeFromItemIds(Collections.singletonList(1102172));
        assertTrue(b1.activeSets.isEmpty(), "Single piece under threshold should not activate");
        // Wearing cape + warrior hat: 2 in warrior (active), 1 in magician (inactive)
        SetBonusTotal b2 = SetItemManager.computeFromItemIds(Arrays.asList(1102172, 1002776));
        assertEquals(1, b2.activeSets.size());
        assertEquals("TIMELESS_WARRIOR", b2.activeSets.get(0).id);
    }

    @Test
    void realYamlFileLoadsAndParses() {
        // Maven runs tests from the project base dir, which is where scripts/config/set-items.yml lives.
        SetItemManager.loadAllSets(Paths.get("scripts/config/set-items.yml"));
        assertTrue(SetItemManager.getSetsById().containsKey("TIMELESS_WARRIOR"));
        assertTrue(SetItemManager.getSetsById().containsKey("TIMELESS_MAGICIAN"));
        assertTrue(SetItemManager.getSetsById().containsKey("TIMELESS_ARCHER"));
        assertTrue(SetItemManager.getSetsById().containsKey("TIMELESS_THIEF"));
        assertTrue(SetItemManager.getSetsById().containsKey("TIMELESS_PIRATE"));
        SetItemDef warrior = SetItemManager.getSetsById().get("TIMELESS_WARRIOR");
        assertNotNull(warrior);
        assertEquals("Timeless Warrior", warrior.name);
        // Verify a known 2pc bonus number round-trips from YAML
        SetBonusDef twoPc = null;
        for (SetBonusDef b : warrior.bonuses) {
            if (b.pieces == 2) { twoPc = b; break; }
        }
        assertNotNull(twoPc);
        assertEquals(15, twoPc.allStats);
        assertEquals(5, twoPc.watk);
    }

    @Test
    void fullYamlContainsAllTwentySets() {
        SetItemManager.loadAllSets(Paths.get("scripts/config/set-items.yml"));
        assertEquals(20, SetItemManager.getSetsById().size(),
                "expected 5 classes x 4 active tiers (Starter/Adventurer/Veteran/Timeless)");
        // Spot-check one from each tier
        assertTrue(SetItemManager.getSetsById().containsKey("STARTER_WARRIOR"));
        assertTrue(SetItemManager.getSetsById().containsKey("ADVENTURER_PIRATE"));
        assertTrue(SetItemManager.getSetsById().containsKey("VETERAN_THIEF"));
        assertTrue(SetItemManager.getSetsById().containsKey("TIMELESS_ARCHER"));
    }

    @Test
    void tier3VeteranWarriorAtSixPiecesMatchesPlanCeiling() {
        SetItemManager.loadAllSets(Paths.get("scripts/config/set-items.yml"));
        // 6 distinct slots: helmet, armor, gauntlet, boots, shield, cape
        SetBonusTotal b = SetItemManager.computeFromItemIds(
                Arrays.asList(1002551, 1052075, 1082168, 1072273, 1092060, 1102031));
        // Cumulative 2pc+3pc+4pc+5pc+6pc per PLAN.md Tier 3 table
        assertEquals(25, b.str);
        assertEquals(25, b.dex);
        assertEquals(25, b.int_);
        assertEquals(25, b.luk);
        assertEquals(8, b.watk);        // 2+2+1+2+1
        assertEquals(10, b.matk);       // 3+2+1+2+2
        assertEquals(100, b.wdef);      // 20 x 5
        assertEquals(10, b.hp);         // warrior at 3pc
        assertEquals(0, b.mp);
        assertEquals(5, b.acc);         // 4pc
        assertEquals(5, b.avoid);       // 6pc
    }

    @Test
    void tier3VeteranMagicianAtThreePiecesRoutesToMp() {
        SetItemManager.loadAllSets(Paths.get("scripts/config/set-items.yml"));
        // 3 members: crown + czar + wand
        SetBonusTotal b = SetItemManager.computeFromItemIds(
                Arrays.asList(1002773, 1052076, 1372032));
        // 2pc + 3pc cumulative; magician variant uses MP not HP
        assertEquals(10, b.str);
        assertEquals(4, b.watk);        // 2 + 2
        assertEquals(5, b.matk);        // 3 + 2
        assertEquals(40, b.wdef);       // 20 + 20
        assertEquals(10, b.mp);
        assertEquals(0, b.hp);
    }

    @Test
    void tier2AdventurerArcherAtFourPiecesMatchesCumulative() {
        SetItemManager.loadAllSets(Paths.get("scripts/config/set-items.yml"));
        // 4 members: Beige Patriot + Beige Linnex + Blue Eyes + Blue Elf Shoes
        SetBonusTotal b = SetItemManager.computeFromItemIds(
                Arrays.asList(1002287, 1050062, 1082106, 1072164));
        // 2pc + 3pc + 4pc cumulative
        assertEquals(8, b.str);         // 3 + 2 + 3
        assertEquals(1, b.watk);        // 0 + 1 + 0
        assertEquals(2, b.matk);        // 0 + 2 + 0
        assertEquals(10, b.wdef);       // 0 + 0 + 10
        assertEquals(0, b.hp);          // 5pc not reached
    }

    @Test
    void tier1StarterMagicianRoutesToMpAndMatk() {
        SetItemManager.loadAllSets(Paths.get("scripts/config/set-items.yml"));
        // 4 distinct slots (hat variants count as one wearable)
        SetBonusTotal b = SetItemManager.computeFromItemIds(
                Arrays.asList(1002034, 1050018, 1082052, 1072076));
        // 2pc + 3pc + 4pc cumulative
        assertEquals(4, b.str);         // 1 + 1 + 2
        assertEquals(15, b.mp);         // 5 + 10
        assertEquals(0, b.hp);
        assertEquals(0, b.watk);
        assertEquals(1, b.matk);
        assertEquals(2, b.wdef);
    }

    @Test
    void tier1StarterWarriorRoutesToHpAndWatk() {
        SetItemManager.loadAllSets(Paths.get("scripts/config/set-items.yml"));
        // Only 3 members available (top/pants IDs pending audit) -> stop at 3pc
        SetBonusTotal b = SetItemManager.computeFromItemIds(
                Arrays.asList(1002005, 1082005, 1072039));
        assertEquals(2, b.str);         // 1 + 1
        assertEquals(5, b.hp);          // warrior routing
        assertEquals(0, b.mp);
        assertEquals(0, b.watk);        // 4pc not reached
        assertEquals(0, b.matk);
    }

    private static SetItemsConfig buildWarriorTimelessOnly() {
        SetItemsConfig cfg = new SetItemsConfig();
        cfg.sets.add(makeWarriorSet());
        return cfg;
    }

    private static SetItemsConfig buildWarriorWithReverse() {
        SetItemsConfig cfg = new SetItemsConfig();
        SetItemDef w = makeWarriorSet();
        w.members.add(1052160);  // Reverse overall (warrior)
        cfg.sets.add(w);
        return cfg;
    }

    private static SetItemDef makeWarriorSet() {
        SetItemDef d = new SetItemDef();
        d.id = "TIMELESS_WARRIOR";
        d.name = "Timeless Warrior";
        d.members = new java.util.ArrayList<>(Arrays.asList(
                1002776, 1052155, 1082234, 1072355, 1092058, 1102172, 1402046));
        d.bonuses = bonusesFromPlan();
        return d;
    }

    private static SetItemDef makeMagicianSet() {
        SetItemDef d = new SetItemDef();
        d.id = "TIMELESS_MAGICIAN";
        d.name = "Timeless Magician";
        d.members = new java.util.ArrayList<>(Arrays.asList(
                1002777, 1052156, 1082235, 1072356, 1092057, 1102172, 1372044));
        d.bonuses = bonusesFromPlan();
        return d;
    }

    private static List<SetBonusDef> bonusesFromPlan() {
        // Tier 4 Timeless numbers from PLAN.md, kept in sync with scripts/config/set-items.yml.
        List<SetBonusDef> out = new java.util.ArrayList<>();
        out.add(bonus(2, 15, 5, 5, 0, 0, 50, 0, 10, 10, 0, 0, 0, 0));
        out.add(bonus(3, 10, 0, 0, 0, 0, 50, 0, 5, 5, 0, 0, 0, 0));
        out.add(bonus(4, 0, 12, 12, 0, 0, 25, 0, 0, 0, 0, 0, 0, 0));
        out.add(bonus(5, 25, 0, 0, 0, 0, 0, 0, 10, 10, 0, 0, 0, 0));
        out.add(bonus(6, 10, 16, 16, 0, 0, 0, 0, 15, 15, 0, 0, 10, 0));
        out.add(bonus(7, 0, 22, 22, 0, 0, 25, 0, 0, 0, 10, 10, 0, 0));
        return out;
    }

    private static SetBonusDef bonus(int pieces, int allStats, int watk, int matk, int hp, int mp,
                                     int wdef, int mdef, int acc, int avoid,
                                     int speed, int jump, int bossDmg, int finalDmg) {
        SetBonusDef b = new SetBonusDef();
        b.pieces = pieces;
        b.allStats = allStats;
        b.watk = watk;
        b.matk = matk;
        b.hp = hp;
        b.mp = mp;
        b.wdef = wdef;
        b.mdef = mdef;
        b.acc = acc;
        b.avoid = avoid;
        b.speed = speed;
        b.jump = jump;
        b.bossDamagePct = bossDmg;
        b.finalDamagePct = finalDmg;
        return b;
    }
}
