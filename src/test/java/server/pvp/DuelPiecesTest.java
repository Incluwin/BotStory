package server.pvp;

import client.Character;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackInfo;
import org.junit.jupiter.api.Test;
import server.StatEffect;
import server.maps.MapleMap;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The duel's pure pieces (docs/PVP_PLAN.md V1.3): shown lines, line counts, bot reach, the registry's refusals. */
class DuelPiecesTest {
    @Test
    void shownLinesShrinkTogetherWhenAGuardTookPartOfTheHit() {
        assertEquals(List.of(50, 100), DuelField.shown(List.of(100, 200), 300, 150));
    }

    @Test
    void shownLinesStayAsRolledWhenTheWholeHitLanded() {
        assertEquals(List.of(100, 200), DuelField.shown(List.of(100, 200), 300, 300));
    }

    @Test
    void aLandedLineNeverShowsAsZero() {
        assertEquals(List.of(1, 100), DuelField.shown(List.of(1, 399), 400, 100)); // 0.25 would round to 0
    }

    @Test
    void lineCountKeepsShadowPartnerButNotMoreThanTwiceTheSkill() {
        StatEffect triple = mock(StatEffect.class);
        when(triple.getAttackCount()).thenReturn(3);
        AttackInfo attack = new AttackInfo();

        attack.numDamage = 6;
        assertEquals(6, DuelField.lineCount(attack, triple));
        attack.numDamage = 15;
        assertEquals(6, DuelField.lineCount(attack, triple));
        attack.numDamage = 0;
        assertEquals(1, DuelField.lineCount(attack, null));
        attack.numDamage = 2;
        assertEquals(2, DuelField.lineCount(attack, null), "a basic attack with Shadow Partner");
    }

    @Test
    void reachIsMeasuredTowardTheTarget() {
        Rectangle box = new Rectangle(100, -50, 200, 60);

        assertEquals(210, DuelBrain.reachToward(box, new Point(90, 0), new Point(500, 0)));
        assertEquals(250, DuelBrain.reachToward(new Rectangle(150, -50, 250, 60), new Point(400, 0),
                new Point(0, 0)));
        assertEquals(0, DuelBrain.reachToward(new Rectangle(500, 0, 10, 10), new Point(400, 0), new Point(0, 0)));
    }

    /**
     * Live 2026-11-08: the brain judged a projectile skill by the planner's 1x1 aim-point box, which never meets a
     * body, and so never walked into reach. It now asks the duel's own box: a projectile reaches ahead, not behind.
     */
    @Test
    void aProjectileBoxReachesAFoeAheadAndNotBehind() {
        Character priest = mock(Character.class);
        when(priest.getPosition()).thenReturn(new Point(1000, 300));

        Rectangle right = PvpHitResolver.attackBox(priest, 0, null, false, true, false);
        Rectangle left = PvpHitResolver.attackBox(priest, 0, null, false, true, true);

        assertTrue(right.intersects(PvpHitResolver.hurtbox(new Point(1200, 300), 0)));
        assertFalse(right.intersects(PvpHitResolver.hurtbox(new Point(800, 300), 0)));
        assertTrue(left.intersects(PvpHitResolver.hurtbox(new Point(800, 300), 0)));
        assertTrue(DuelBrain.reachToward(right, new Point(1000, 300), new Point(1500, 300)) >= 300,
                "a projectile's reach is hundreds of px, not the gap to the foe");
    }

    @Test
    void aClassRateGoesAheadOfThePhysicalAndMagicRates() {
        PvpReference reference = PvpReference.parse(List.of(
                "dps_per_base:", "  physical: 4.6", "  magic: 80",
                "class_dps_per_base:", "  \"1\": 1.2", "  \"2\": 14.4"));

        assertEquals(1.2d, reference.ratePerBase(112, false), 1e-9);
        assertEquals(14.4d, reference.ratePerBase(232, true), 1e-9);
        assertEquals(4.6d, reference.ratePerBase(312, false), 1e-9, "no class rate: the physical one");
    }

    @Test
    void classScalesSuggestTheRateThatMatchesWhatBotsDeal() {
        PvpReference reference = PvpReference.parse(List.of("dps_per_base:", "  physical: 4.6", "  magic: 80"));
        // warriors with gear base 2000: model 9200/s; they deal 2300, 2760 and 1840 raw a second (0.25, 0.30, 0.20)
        String a = "1,1,0,30,x,0,A,1,112,95,B,0,2000,0,0,1,0,1,0,0,0,20,0,0,20,69000,0,0,30.0,-";
        String b = "1,1,0,30,x,0,B,2,111,92,B,1,2000,0,0,1,0,1,0,0,0,20,0,0,20,82800,0,0,30.0,-";
        String c = "1,2,0,30,x,0,C,3,112,98,B,0,2000,0,0,1,0,1,0,0,0,20,0,0,20,55200,0,0,30.0,-";

        List<DuelLog.ClassScale> scales = DuelLog.classScales(List.of(DuelLog.HEADER, a, b, c), reference);

        assertEquals(1, scales.size());
        assertEquals(1, scales.get(0).jobClass());
        assertEquals(0.25d, scales.get(0).medianRatio(), 1e-9);
        assertEquals(1.15d, scales.get(0).suggestedRatePerBase(), 1e-9);
    }

    /** A platform at y 100 from x 0 to 500 over open ground at y 300. */
    private static Point floorUnder(Point p) {
        return p.x >= 0 && p.x <= 500 ? new Point(p.x, 100) : new Point(p.x, 300);
    }

    /**
     * Live 2026-11-09: casters with a 400 px projectile aimed for a spot past the platform's edge, dropped below, and
     * never swung. The spot now stays on the target's level.
     */
    @Test
    void aStandSpotStaysOnTheTargetsPlatform() {
        Point target = new Point(400, 100);

        Point caster = DuelBrain.standPoint(new Point(900, 300), target, 400, DuelPiecesTest::floorUnder);
        Point melee = DuelBrain.standPoint(new Point(600, 100), target, 80, DuelPiecesTest::floorUnder);
        Point atTheEdge = DuelBrain.standPoint(new Point(900, 300), new Point(490, 100), 400,
                DuelPiecesTest::floorUnder);

        assertEquals(100, caster.y);
        assertTrue(caster.x > 400 && caster.x <= 500, "on the platform, on the caster's side: " + caster);
        assertEquals(new Point(456, 100), melee, "70% of an 80 px reach");
        assertEquals(new Point(210, 100), atTheEdge, "no room on its side, so the other side");
    }

    /**
     * Filmed 2026-11-10: in reach, neither fighter moved again for 29 s. A step goes to another spot inside the reach,
     * on the fighter's own side and level, far enough to read as a step.
     */
    @Test
    void aStepStaysInReachOnTheFightersSideAndLevel() {
        Point target = new Point(200, 100);
        Point at = new Point(450, 100);   // 250 px out, on the platform (x 0-500 at y 100)

        Point near = DuelBrain.footworkSpot(at, target, 400, 0d, DuelPiecesTest::floorUnder);
        Point middle = DuelBrain.footworkSpot(at, target, 400, 0.5d, DuelPiecesTest::floorUnder);

        assertEquals(new Point(340, 100), near, "35% of a 400 px reach, on its own side");
        assertEquals(new Point(340, 100), middle, "240 px is 10 px from where it stands, so the band's near end");
    }

    @Test
    void aStepTooSmallToSeeGoesToTheBandsOtherEnd() {
        Point target = new Point(100, 100);
        Point at = new Point(240, 100);   // already at the near end (140 px of a 400 px reach)

        assertEquals(new Point(440, 100), DuelBrain.footworkSpot(at, target, 400, 0d, DuelPiecesTest::floorUnder),
                "the near end is where it stands, so the far end: 85% of the reach");
    }

    @Test
    void aRangedFighterCrowdedByAMeleeOneStepsBack() {
        Point target = new Point(200, 100);
        Point crowded = new Point(230, 100);   // the melee foe 30 px away

        Point spot = DuelBrain.footworkSpot(crowded, target, 400, 0.2d, DuelPiecesTest::floorUnder);

        assertNotNull(spot);
        assertTrue(spot.x - target.x >= 140, "back out to at least 35% of its reach: " + spot);
    }

    @Test
    void aStepThatWouldLeaveTheFightersLevelIsNotTaken() {
        Point target = new Point(400, 100);
        Point at = new Point(480, 100);   // 80 px out; every step out is past the platform's edge (x 500)

        assertNull(DuelBrain.footworkSpot(at, target, 400, 0.9d, DuelPiecesTest::floorUnder));
    }

    /**
     * Check runs 14-15: a warrior's autopot drank only under its own alert, so it absorbed 1.11x its HP against a
     * magician's 1.29x. A duel bot reaches for a potion at 90% HP, about once a second.
     */
    @Test
    void aBotDrinksFromNinetyPercentAboutOnceASecond() {
        assertTrue(DuelBrain.wantsDrink(900, 1000, 5_000L), "at 90%");
        assertFalse(DuelBrain.wantsDrink(901, 1000, 5_000L), "above 90% a drink would mostly be wasted");
        assertFalse(DuelBrain.wantsDrink(500, 1000, 400L), "it tried under a second ago");
        assertTrue(DuelBrain.wantsDrink(500, 1000, DuelBrain.DRINK_RETRY_MS));
        assertFalse(DuelBrain.wantsDrink(0, 1000, 5_000L), "knocked out or dead: no drink");
        assertFalse(DuelBrain.wantsDrink(0, 0, 5_000L));
    }

    /** An 80 px spear reach steps 30-68 px from its target, so its step is half that band, not 40 px. */
    @Test
    void aShortReachStillSteps() {
        Point target = new Point(100, 100);

        assertEquals(new Point(130, 100), DuelBrain.footworkSpot(new Point(156, 100), target, 80, 0.5d,
                DuelPiecesTest::floorUnder), "56 px out (its stand spot): in to the band's near end, 26 px");
    }

    @Test
    void aHopGoesInFromFarAndOutFromNearAndStaysInReach() {
        Point target = new Point(100, 100);

        assertEquals(-1, DuelBrain.hopDirection(new Point(450, 100), target, 400, DuelPiecesTest::floorUnder),
                "350 px out of a 400 px reach: in, toward the target on its left");
        assertEquals(1, DuelBrain.hopDirection(new Point(180, 100), target, 400, DuelPiecesTest::floorUnder),
                "80 px out: away");
        assertEquals(-1, DuelBrain.hopDirection(new Point(150, 100), target, 80, DuelPiecesTest::floorUnder),
                "an 80 px reach 50 px out: over the target, landing 40 px past it, still in reach");
        assertEquals(0, DuelBrain.hopDirection(new Point(470, 100), new Point(800, 100), 400,
                DuelPiecesTest::floorUnder), "in drops off the platform's edge (x 500), out leaves its reach");
    }

    @Test
    void aStandSpotWithNoLevelGroundIsTheTargetsOwn() {
        Point target = new Point(1000, 100);

        assertEquals(target, DuelBrain.standPoint(new Point(1200, 300), target, 400, p -> new Point(p.x, 300)));
    }

    private static Character fighter(int id, MapleMap map, boolean alive) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn("F" + id);
        when(chr.getMap()).thenReturn(map);
        when(chr.isAlive()).thenReturn(alive);
        return chr;
    }

    /** Live 2026-11-09, duel 2: a fighter walked off the map and the duel held the other one for 523 s. */
    @Test
    void aFighterWhoLeavesEndsTheDuelOnTheNextTick() {
        MapleMap map = mock(MapleMap.class);
        Character stays = fighter(1, map, true);
        Character leaves = fighter(2, mock(MapleMap.class), true);
        when(map.getCharacterById(1)).thenReturn(stays);
        DuelField field = new DuelField(99, map, List.of(stays), List.of(leaves));
        List<List<DuelLog.Row>> written = new java.util.ArrayList<>();
        var sink = DuelField.rowSink;
        DuelField.rowSink = written::add;
        try {
            field.tick(System.currentTimeMillis());
        } finally {
            DuelField.rowSink = sink;
        }

        assertEquals(DuelField.State.ENDED, field.state());
        assertEquals(1, written.size());
        assertEquals(2, written.get(0).size(), "a row for each fighter");
        assertTrue(written.get(0).get(1).left());
    }

    @Test
    void ratesAreTheBotsMedianRawDamagePerEngagedSecondPerJobAndBand() {
        String header = DuelLog.HEADER;
        // endedAt,duel,map,seconds,reason,winner,name,charId,jobId,level,kind,side,gearBase,ko,left,thinks,buffs,sent,
        // deferred,refused,noSkill,packets,whiffs,dodged,hits,raw,dealt,taken,engaged,topRefusal
        String a = "1,1,0,30,x,0,A,1,112,95,B,0,2000,0,0,1,0,1,0,0,0,20,0,0,20,60000,0,0,30.0,-";   // 2000/s
        String b = "1,1,0,30,x,0,B,2,111,92,B,1,2000,0,0,1,0,1,0,0,0,20,0,0,20,30000,0,0,30.0,-";   // 1000/s
        String c = "1,2,0,30,x,0,C,3,112,98,B,0,2000,0,0,1,0,1,0,0,0,20,0,0,20,90000,0,0,30.0,-";   // 3000/s
        String human = "1,2,0,30,x,0,D,4,112,98,H,1,2000,0,0,1,0,1,0,0,0,20,0,0,20,900000,0,0,30.0,-";
        String brief = "1,3,0,4,x,0,E,5,112,98,B,0,2000,0,0,1,0,1,0,0,0,20,0,0,20,900000,0,0,4.0,-";

        List<DuelLog.Rate> rates = DuelLog.rates(List.of(header, a, b, c, human, brief));

        assertEquals(1, rates.size());
        assertEquals("11-9", rates.get(0).key());
        assertEquals(2000L, rates.get(0).medianRawDps());
        assertEquals(3, rates.get(0).fighters());
    }

    /** Practice 2026-11-09: a citizen entered nearly dead eight duels running and lost to its first hit. */
    @Test
    void everyFighterStartsWholeWhenTheBellRings() {
        MapleMap map = mock(MapleMap.class);
        Character a = fighter(1, map, true);
        Character b = fighter(2, map, true);
        when(map.getCharacterById(1)).thenReturn(a);
        when(map.getCharacterById(2)).thenReturn(b);
        when(a.getCurrentMaxHp()).thenReturn(5000);
        when(a.getCurrentMaxMp()).thenReturn(800);
        when(b.getCurrentMaxHp()).thenReturn(3000);
        when(b.getCurrentMaxMp()).thenReturn(4000);
        DuelField field = new DuelField(98, map, List.of(a), List.of(b));

        field.tick(System.currentTimeMillis());   // never begun, so its countdown is already over

        assertEquals(DuelField.State.FIGHTING, field.state());
        verify(a).updateHpMp(5000, 800);
        verify(b).updateHpMp(3000, 4000);
    }

    /**
     * Long run 2026-11-09: a hit reset the heal baseline, so a potion or Heal between a tick and a hit passed whole.
     * A hit now runs this check first (no time passes, so no new allowance): the heal beyond what is left is taken back.
     */
    @Test
    void aHealBeyondTheAllowanceIsTakenBackBeforeAHitMovesTheBaseline() {
        MapleMap map = mock(MapleMap.class);
        Character a = fighter(1, map, true);
        Character b = fighter(2, map, true);
        when(a.getCurrentMaxHp()).thenReturn(5000);
        when(a.getCurrentMaxMp()).thenReturn(800);
        DuelField field = new DuelField(97, map, List.of(a), List.of(b));
        DuelField.Fighter healed = field.fighters().stream().filter(f -> f.chr == a).findFirst().orElseThrow();
        healed.lastHp = 1000;
        healed.lastMp = 800;
        healed.hpAllowance = 500;
        healed.mpAllowance = 80;
        when(a.getHp()).thenReturn(3000);   // +2000 since the last look
        when(a.getMp()).thenReturn(800);

        field.accountHealing(healed, 0d);

        verify(a).updateHp(1500);
        assertEquals(1500, healed.lastHp);
        assertEquals(0d, healed.hpAllowance, 1e-9);
    }

    /**
     * Check run 16 (2026-11-11): HP and MP potions shared one window, so a warrior's MP autopot crowded out its HP
     * drinks and 59% of warriors never got one in.
     */
    @Test
    void hpAndMpPotionsEachHaveTheirOwnWindow() {
        MapleMap map = mock(MapleMap.class);
        Character a = fighter(1, map, true);
        Character b = fighter(2, map, true);
        DuelField field = new DuelField(95, map, List.of(a), List.of(b));

        assertFalse(field.refusesDrink(a, false, true), "an MP potion");
        assertFalse(field.refusesDrink(a, true, false), "an HP potion right after it");
        assertTrue(field.refusesDrink(a, true, false), "a second HP potion inside its window");
        assertTrue(field.refusesDrink(a, true, true), "an Elixir while both windows run");
        assertFalse(field.refusesDrink(b, true, true), "the other fighter's windows are its own");
    }

    /**
     * Heal-fixed run 2026-11-10: a bot's autopot drinks inside the hit's loss, and the HP after it became the baseline,
     * so knocked-out magicians had absorbed 1.59x their max HP. The baseline is now what the loss left.
     */
    @Test
    void anAutopotDrinkInsideAHitMeetsTheAllowance() {
        MapleMap map = mock(MapleMap.class);
        Character a = fighter(1, map, true);
        Character b = fighter(2, map, true);
        when(a.getCurrentMaxHp()).thenReturn(5000);
        when(a.getCurrentMaxMp()).thenReturn(800);
        DuelField field = new DuelField(96, map, List.of(a), List.of(b));
        DuelField.Fighter hit = field.fighters().stream().filter(f -> f.chr == a).findFirst().orElseThrow();
        hit.hpAllowance = 500;
        hit.mpAllowance = 80;
        when(a.getHp()).thenReturn(5000);   // 3000 - 1000 from the hit, then an Elixir drunk inside it
        when(a.getMp()).thenReturn(800);

        field.settleAfterLoss(hit, 3000, 800, new server.combat.CharacterHitApplier.Applied(1000, 0, false));

        verify(a).updateHp(2500);
        assertEquals(2500, hit.lastHp);
    }

    /** Filmed 2026-11-10: an assassin threw stars from a ladder for six seconds. No player can attack while climbing. */
    @Test
    void aBotNeverSwingsFromARopeOrLadder() {
        assertFalse(DuelBrain.mayAttackFrom(constants.game.CharacterStance.LADDER_LEFT_STANCE));
        assertFalse(DuelBrain.mayAttackFrom(constants.game.CharacterStance.LADDER_RIGHT_STANCE));
        assertFalse(DuelBrain.mayAttackFrom(constants.game.CharacterStance.ROPE_LEFT_STANCE));
        assertFalse(DuelBrain.mayAttackFrom(constants.game.CharacterStance.ROPE_RIGHT_STANCE));
        assertTrue(DuelBrain.mayAttackFrom(constants.game.CharacterStance.WALK_LEFT_STANCE));
        assertTrue(DuelBrain.mayAttackFrom(constants.game.CharacterStance.WALK_RIGHT_STANCE));
    }

    /** A held attack key: the next think comes as the attack lock ends, not a whole reaction later. */
    @Test
    void aBotSwingsAgainAsItsAttackLockEnds() {
        DuelBrain.Mind mind = new DuelBrain.Mind();
        for (int i = 0; i < 50; i++) {
            DuelBrain.swingAgainAt(mind, 10_000L, 700);
            long after = mind.nextThinkMs - 10_700L;
            assertTrue(after >= DuelBrain.KEY_REPEAT_MIN_MS
                            && after < DuelBrain.KEY_REPEAT_MIN_MS + DuelBrain.KEY_REPEAT_SPREAD_MS,
                    "thinks " + after + " ms after its lock ends");
        }
        DuelBrain.swingAgainAt(mind, 10_000L, -5);
        assertTrue(mind.nextThinkMs >= 10_000L + DuelBrain.KEY_REPEAT_MIN_MS, "a spent lock is no lock");
    }

    @Test
    void aWaitedSwingCountsInTheTotalAndByItsReason() {
        DuelStats stats = new DuelStats();
        stats.deferred("ATTACK_COOLDOWN");
        stats.deferred("ATTACK_COOLDOWN");
        stats.deferred("CANNOT_USE_SKILL");
        stats.refused("plan:combo-orbs");

        assertEquals(3, stats.swingsDeferred.get());
        assertEquals("ATTACK_COOLDOWNx2", stats.topDeferral());
        assertEquals("plan:combo-orbsx1", stats.topRefusal(), "a wait is not a refusal");
        assertTrue(stats.summary().contains("waited=ATTACK_COOLDOWNx2"));
        assertEquals("-", new DuelStats().topDeferral());
    }

    @Test
    void theRegistryRefusesAFighterOnBothSides() {
        MapleMap map = mock(MapleMap.class);
        Character a = fighter(1, map, true);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> DuelRegistry.start(map, List.of(a), List.of(a)));
        assertTrue(refused.getMessage().contains("both sides"));
        assertFalse(DuelRegistry.active());
    }

    @Test
    void theRegistryRefusesAFighterOffTheMapOrDown() {
        MapleMap map = mock(MapleMap.class);
        Character a = fighter(1, map, true);
        Character elsewhere = fighter(2, mock(MapleMap.class), true);
        Character down = fighter(3, map, false);

        assertThrows(IllegalStateException.class, () -> DuelRegistry.start(map, List.of(a), List.of(elsewhere)));
        assertThrows(IllegalStateException.class, () -> DuelRegistry.start(map, List.of(a), List.of(down)));
        assertThrows(IllegalStateException.class, () -> DuelRegistry.start(map, List.of(a), List.of()));
        assertFalse(DuelRegistry.active());
    }
}
