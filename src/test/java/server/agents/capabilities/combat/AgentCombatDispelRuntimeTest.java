package server.agents.capabilities.combat;

import client.Character;
import client.Disease;
import client.Skill;
import constants.skills.Priest;
import org.junit.jupiter.api.Test;
import server.StatEffect;
import server.agents.integration.SkillGateway;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 2026-10-14 (P18): Bishops dispel the party when Horntail's Right Hand seals it. */
class AgentCombatDispelRuntimeTest {
    private final SkillGateway skills = mock(SkillGateway.class);
    private final List<Integer> casts = new ArrayList<>();
    private final AgentCombatDispelRuntime.Caster caster = (entry, bot, skill, effect, now) -> {
        casts.add(bot.getId());
        return true;
    };

    @Test
    void aBishopDispelsWhenAPartyMemberInsideTheBoxIsSealedAtMostEveryInterval() {
        Character bishop = bishop(4_101, new Point(0, 260));
        Character fighter = member(4_102, new Point(200, 260), Disease.SEAL);
        when(bishop.getPartyMembersOnSameMap()).thenReturn(List.of(bishop, fighter));
        AgentRuntimeEntry entry = following(bishop);
        int every = AgentCombatConfig.cfg.DISPEL_INTERVAL_MS;

        assertTrue(tick(entry, bishop, 10_000L));
        assertFalse(tick(entry, bishop, 10_000L + every - 1), "too soon");
        assertTrue(tick(entry, bishop, 10_000L + every));
        assertEquals(List.of(4_101, 4_101), casts);
    }

    @Test
    void itDispelsItsOwnDarknessToo() {
        Character bishop = bishop(4_111, new Point(0, 260));
        when(bishop.hasDisease(Disease.DARKNESS)).thenReturn(true);
        when(bishop.getPartyMembersOnSameMap()).thenReturn(List.of(bishop));

        assertTrue(tick(following(bishop), bishop, 0L));
    }

    @Test
    void aSealedBishopCannotCastIt() {
        Character bishop = bishop(4_121, new Point(0, 260));
        when(bishop.hasDisease(Disease.SEAL)).thenReturn(true);
        when(bishop.getPartyMembersOnSameMap()).thenReturn(List.of(bishop));

        assertFalse(tick(following(bishop), bishop, 0L));
        assertTrue(casts.isEmpty());
    }

    @Test
    void aSealedMemberOutsideTheBoxOrSeducedOnlyIsNoReason() {
        Character bishop = bishop(4_131, new Point(0, 260));
        Character far = member(4_132, new Point(900, 260), Disease.SEAL);
        Character seduced = member(4_133, new Point(50, 260), Disease.SEDUCE);
        when(bishop.getPartyMembersOnSameMap()).thenReturn(List.of(bishop, far, seduced));

        assertFalse(tick(following(bishop), bishop, 0L));
        assertTrue(casts.isEmpty());
    }

    @Test
    void anAgentWithoutDispelNeverCastsIt() {
        Character hero = bishop(4_141, new Point(0, 260));
        when(hero.getSkillLevel(Priest.DISPEL)).thenReturn(0);
        when(hero.hasDisease(Disease.SEAL)).thenReturn(false);
        when(hero.hasDisease(Disease.DARKNESS)).thenReturn(true);

        assertFalse(tick(following(hero), hero, 0L));
    }

    private boolean tick(AgentRuntimeEntry entry, Character bot, long nowMs) {
        return AgentCombatDispelRuntime.tickDispel(entry, bot, AgentCombatConfig.cfg, skills, nowMs, caster);
    }

    private Character bishop(int id, Point at) {
        Character bishop = mock(Character.class);
        Skill dispel = mock(Skill.class);
        StatEffect effect = mock(StatEffect.class);
        when(bishop.getId()).thenReturn(id);
        when(bishop.isAlive()).thenReturn(true);
        when(bishop.getPosition()).thenReturn(at);
        when(bishop.getSkillLevel(Priest.DISPEL)).thenReturn(20);
        when(bishop.getSkillLevel(dispel)).thenReturn((byte) 20);
        when(skills.getSkill(Priest.DISPEL)).thenReturn(dispel);
        when(dispel.getEffect(20)).thenReturn(effect);
        when(effect.hasBoundingBox()).thenReturn(true);
        // Dispel level 20: lt (-300, -225), rb (300, 225)
        when(effect.calculateBoundingBox(any(Point.class), anyBoolean()))
                .thenAnswer(call -> {
                    Point from = call.getArgument(0);
                    return new Rectangle(from.x - 300, from.y - 225, 600, 450);
                });
        return bishop;
    }

    private static Character member(int id, Point at, Disease disease) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.isAlive()).thenReturn(true);
        when(member.getPosition()).thenReturn(at);
        when(member.hasDisease(disease)).thenReturn(true);
        return member;
    }

    private static AgentRuntimeEntry following(Character bot) {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
        AgentModeStateRuntime.setFollowing(entry, true);
        return entry;
    }
}
