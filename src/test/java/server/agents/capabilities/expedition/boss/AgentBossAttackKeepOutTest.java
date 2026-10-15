package server.agents.capabilities.expedition.boss;

import client.Character;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;
import server.life.autonomy.BossAction;
import server.maps.MapleMap;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-10-15 (P19 run 1): a boss-run member keeps out of the reach of a listed part's attack that would take most of
 * its HP in one hit. Showa's Bodyguard A (9400112) stands at (420,161) on the battle map; its attack2 (PADamage 1800,
 * box lt -147,-80 to rb 8,0 for a part facing left) hits about 26,000 against a Lv200 Hero's 19,776 HP, so while it
 * faces left the attack spans x 273..428 and a member in front keeps to x <= 273 - 1 - KEEP_OUT_MARGIN_PX (30) = 242;
 * one behind it keeps past its touch area. Facing right, the span mirrors to x 412..567. Its attack1 (a stun, no
 * damage), a deadly attack, a hit under ATTACK_KEEP_OUT_HP_PERCENT (80) of max HP, or a boss whose rules list no
 * attackKeepOut part leave the member free.
 */
class AgentBossAttackKeepOutTest {
    private static final int BATTLE = 801_040_100;
    private static final int BODYGUARD_A = 9_400_112;
    private static final Point AT = new Point(420, 161);
    private static final Rectangle TOUCH = new Rectangle(390, 131, 61, 30);
    private static final BossAction.OrdinaryAttack STUN = attack(0, false, new Point(-384, -94), new Point(34, 1));
    private static final BossAction.OrdinaryAttack SMASH = attack(1, false, new Point(-147, -80), new Point(8, 0));
    private static final BossAction.OrdinaryAttack DEADLY = attack(2, true, new Point(-609, -180), new Point(90, 1));

    private final PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
    private final MapleMap battle = mock(MapleMap.class);
    private final List<Monster> monsters = new ArrayList<>();
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private final Map<BossAction.OrdinaryAttack, Double> hits = new HashMap<>();
    private final EventInstanceManager event = mock(EventInstanceManager.class);
    private List<BossAction.OrdinaryAttack> attacks = List.of(STUN, SMASH);
    private boolean facesLeft = true;
    private boolean mobile;
    private float ownAlert = 0.7f;
    private final List<Float> alerts = new ArrayList<>();
    private int maxHp = 19_776;
    private AgentBossPriorityFight fight;

    @BeforeEach
    void setUp() {
        when(battle.getId()).thenReturn(BATTLE);
        when(gateway.groundPoint(any(), any())).thenAnswer(call -> new Point((Point) call.getArgument(1)));
        hits.put(STUN, 1.0d);
        hits.put(SMASH, 25_900.0d);
        hits.put(DEADLY, 99_999.0d);
        fight = new AgentBossPriorityFight("showa", new AgentBossPriorityFight.Ports() {
            @Override
            public PrimitiveCapabilityGateway gateway() {
                return gateway;
            }

            @Override
            public AgentRuntimeEntry entry(int characterId) {
                return entries.get(characterId);
            }

            @Override
            public List<Monster> monsters(MapleMap map) {
                return map == battle ? monsters : List.of();
            }

            @Override
            public boolean hittable(Monster monster) {
                return true;
            }

            @Override
            public Rectangle touchArea(Monster monster) {
                return monster.getId() == BODYGUARD_A ? TOUCH : null;
            }

            @Override
            public double touchDamage(Character member, Monster monster) {
                return 16_800.0d;
            }

            @Override
            public int maxHp(Character member) {
                return maxHp;
            }

            @Override
            public List<BossAction.OrdinaryAttack> attacks(Monster monster) {
                return monster.getId() == BODYGUARD_A ? attacks : List.of();
            }

            @Override
            public boolean facesLeft(Monster monster) {
                return facesLeft;
            }

            @Override
            public double attackDamage(Character member, Monster monster, BossAction.OrdinaryAttack attack) {
                return hits.getOrDefault(attack, 0.0d);
            }

            @Override
            public boolean mobile(Monster monster) {
                return mobile;
            }

            @Override
            public float baseHpAlert(Character member) {
                return ownAlert;
            }

            @Override
            public void setHpAlert(Character member, float alert) {
                alerts.add(alert);
            }
        });
        monsters.add(bodyguard());
    }

    @Test
    void inFrontOfBodyguardAAMemberKeepsPastTheReachOfItsSmash() {
        assertArrayEquals(new int[] {Integer.MIN_VALUE, 273 - 1 - 30},
                fight.touchBand(battle, member(10, new Point(200, 161)), null, List.of(BODYGUARD_A)));
    }

    @Test
    void behindBodyguardAAMemberKeepsPastItsTouchOnly() {
        assertArrayEquals(new int[] {450 + 1 + 30, Integer.MAX_VALUE},
                fight.touchBand(battle, member(10, new Point(600, 161)), null, List.of(BODYGUARD_A)));
    }

    @Test
    void facingRightTheReachMirrorsAndAMemberBehindItBacksOff() {
        facesLeft = false;

        assertArrayEquals(new int[] {567 + 1 + 30, Integer.MAX_VALUE},
                fight.touchBand(battle, member(10, new Point(600, 161)), null, List.of(BODYGUARD_A)));
        assertArrayEquals(new int[] {Integer.MIN_VALUE, 390 - 1 - 30},
                fight.touchBand(battle, member(11, new Point(200, 161)), null, List.of(BODYGUARD_A)));
    }

    @Test
    void aHitUnderEightyPercentOfMaxHpLeavesOnlyTheTouch() {
        maxHp = 35_000;

        assertArrayEquals(new int[] {Integer.MIN_VALUE, 390 - 1 - 30},
                fight.touchBand(battle, member(10, new Point(200, 161)), null, List.of(BODYGUARD_A)));
    }

    @Test
    void aDeadlyAttackIsLeftToThePotionsNotKeptOutOf() {
        attacks = List.of(DEADLY);

        assertArrayEquals(new int[] {Integer.MIN_VALUE, 390 - 1 - 30},
                fight.touchBand(battle, member(10, new Point(200, 161)), null, List.of(BODYGUARD_A)));
    }

    @Test
    void aPartTheRulesDoNotListLeavesItsAttacksOut() {
        assertArrayEquals(new int[] {Integer.MIN_VALUE, 390 - 1 - 30},
                fight.touchBand(battle, member(10, new Point(200, 161)), null));
    }

    @Test
    void onALedgeAboveTheSmashAMemberIsFree() {
        assertNull(fight.touchBand(battle, member(10, new Point(300, 40)), null, List.of(BODYGUARD_A)));
    }

    @Test
    void showasRulesListTheBodyguardsSoATickHoldsAMemberInFront() {
        AgentRuntimeEntry hero = mock(AgentRuntimeEntry.class);
        entries.put(10, hero);

        fight.tick(List.of(member(10, new Point(200, 161))), event, 0L);

        verify(gateway).setHorizontalBoundary(hero, BATTLE, Integer.MIN_VALUE, 273 - 1 - 30);
        verify(gateway).grind(any(), anySet(), anySet());
        verify(gateway, never()).navigate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        assertEquals(List.of(9_400_112, 9_400_113), AgentBossRules.of("showa").attackKeepOut());
    }

    @Test
    void aBodyguardThatWalksIsGivenAWiderBerth() {
        mobile = true;

        assertArrayEquals(new int[] {Integer.MIN_VALUE, 273 - 1 - 120},
                fight.touchBand(battle, member(10, new Point(100, 161)), null, List.of(BODYGUARD_A)));
    }

    @Test
    void whileBodyguardALivesAMemberDrinksAtNinetyFivePercentAndBackAtSeventyAfter() {
        entries.put(10, mock(AgentRuntimeEntry.class));
        Character hero = member(10, new Point(100, 161));

        fight.tick(List.of(hero), event, 0L);
        monsters.clear();
        fight.tick(List.of(hero), event, 500L);
        fight.tick(List.of(hero), event, 1_000L);

        assertEquals(List.of(0.95f, 0.7f), alerts);
    }

    @Test
    void aTouchTheMemberOutlastsAtItsOwnAlertLeavesThatAlert() {
        attacks = List.of(STUN);
        maxHp = 60_000;
        entries.put(10, mock(AgentRuntimeEntry.class));

        fight.tick(List.of(member(10, new Point(100, 161))), event, 0L);

        assertEquals(List.of(), alerts, "16,800 x 1.15 is a third of 60,000, under the own 70%");
    }

    @Test
    void aMemberWithNoHpPotionKeyIsLeftAlone() {
        ownAlert = 0f;
        entries.put(10, mock(AgentRuntimeEntry.class));

        fight.tick(List.of(member(10, new Point(100, 161))), event, 0L);

        assertEquals(List.of(), alerts);
    }

    @Test
    void theAttackAreaIsTheWzBoxMirroredForAPartFacingRight() {
        assertEquals(new Rectangle(273, 81, 156, 81), AgentBossPriorityFight.attackArea(AT, true, SMASH));
        assertEquals(new Rectangle(412, 81, 156, 81), AgentBossPriorityFight.attackArea(AT, false, SMASH));
    }

    private static BossAction.OrdinaryAttack attack(int index, boolean deadly, Point lt, Point rb) {
        return new BossAction.OrdinaryAttack(index, 13 + index, 0, 500, 1_000, false, lt, rb, 0, 0, 0, deadly,
                index == 1 ? 1_800 : 0, 0, 0, 0, false);
    }

    private Monster bodyguard() {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(BODYGUARD_A);
        when(monster.isAlive()).thenReturn(true);
        when(monster.getPosition()).thenReturn(new Point(AT));
        when(monster.getMap()).thenReturn(battle);
        return monster;
    }

    private Character member(int id, Point at) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.getMap()).thenReturn(battle);
        when(member.getMapId()).thenReturn(BATTLE);
        when(member.getPosition()).thenReturn(at);
        return member;
    }
}
