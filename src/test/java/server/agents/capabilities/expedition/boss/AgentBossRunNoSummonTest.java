package server.agents.capabilities.expedition.boss;

import client.BuffStat;
import client.Character;
import client.Skill;
import constants.skills.Priest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.StatEffect;
import server.agents.capabilities.combat.AgentCombatBuffRuntime;
import server.agents.capabilities.combat.AgentCombatSkillCacheStateRuntime;
import server.agents.capabilities.combat.AgentCombatSummonRuntime;
import server.agents.capabilities.expedition.balrog.AgentBalrogDefinition;
import server.agents.capabilities.expedition.zakum.AgentZakumDefinition;
import server.agents.integration.AgentClientGateway;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentCombatGatewayRuntime;
import server.agents.integration.AgentSkillGatewayRuntime;
import server.agents.integration.CombatGateway;
import server.agents.integration.SkillGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.expeditions.Expedition;
import server.maps.FieldLimit;
import server.maps.MapleMap;
import server.maps.SummonMovementType;
import tools.Pair;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Players cannot summon on Easy Balrog's battle map (its fieldLimit has the SUMMON bit), and the server never checks
 * that bit, so a companion fighting there must not summon either; Zakum's altar carries it too. PQ maps carry the bit
 * too (KPQ stages, LPQ's boss map); Agents outside boss runs keep summoning there as before. Field limits are read
 * from the tracked WZ.
 */
class AgentBossRunNoSummonTest {
    private static final int OWNER = 1;
    private static final int ISOLDE = 11;
    private static final int THANE = 10;
    private static final int KPQ_AGENT = 40;
    private static final long NOW = 1_000_000L;
    private static final int SUMMON_DRAGON = Priest.SUMMON_DRAGON;
    private static final int RECRUIT_MAP = AgentBalrogDefinition.RECRUIT_MAP;
    private static final int BATTLE_MAP = AgentBalrogDefinition.BATTLE_MAP;
    private static final int CLEAR_MAP = AgentBalrogDefinition.CLEAR_MAP;
    private static final int KPQ_STAGE_MAP = 103_000_800;
    private static final int LPQ_BOSS_MAP = 922_010_800;
    private static final int ZAKUM_ALTAR = AgentZakumDefinition.BATTLE_MAP_ID;
    private static final Pattern FIELD_LIMIT = Pattern.compile("<int name=\"fieldLimit\" value=\"(\\d+)\"/>");

    private final CombatGateway combat = mock(CombatGateway.class);
    private final AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
    private final Skill skill = mock(Skill.class);
    private MockedStatic<AgentClientGatewayRuntime> clients;
    private MockedStatic<AgentSkillGatewayRuntime> skills;
    private MockedStatic<AgentCombatGatewayRuntime> combats;
    private MockedStatic<AgentCombatBuffRuntime> buffs;
    private MockedStatic<AgentCombatSkillCacheStateRuntime> cache;

    @BeforeEach
    void setUp() {
        StatEffect effect = mock(StatEffect.class);
        when(effect.getStatups()).thenReturn(List.of(new Pair<>(BuffStat.SUMMON, 1)));
        when(effect.getSummonMovementType()).thenReturn(SummonMovementType.FOLLOW);
        when(effect.canPaySkillCost(any())).thenReturn(true);
        when(effect.hasItemCon(any())).thenReturn(true);
        when(skill.getEffect(1)).thenReturn(effect);
        SkillGateway skillGateway = mock(SkillGateway.class);
        when(skillGateway.getSkill(SUMMON_DRAGON)).thenReturn(skill);
        AgentClientGateway clientGateway = mock(AgentClientGateway.class);
        when(clientGateway.hasClient(any())).thenReturn(true);
        when(combat.dispatchSummonSpecialMove(any(), anyInt(), anyInt(), anyInt())).thenReturn(true);

        clients = mockStatic(AgentClientGatewayRuntime.class);
        clients.when(AgentClientGatewayRuntime::clients).thenReturn(clientGateway);
        skills = mockStatic(AgentSkillGatewayRuntime.class);
        skills.when(AgentSkillGatewayRuntime::skills).thenReturn(skillGateway);
        combats = mockStatic(AgentCombatGatewayRuntime.class);
        combats.when(AgentCombatGatewayRuntime::combat).thenReturn(combat);
        buffs = mockStatic(AgentCombatBuffRuntime.class);   // not airborne, not climbing
        cache = mockStatic(AgentCombatSkillCacheStateRuntime.class);
        cache.when(() -> AgentCombatSkillCacheStateRuntime.summonSkillIds(entry)).thenReturn(List.of(SUMMON_DRAGON));
    }

    @AfterEach
    void tearDown() {
        cache.close();
        buffs.close();
        combats.close();
        skills.close();
        clients.close();
        AgentBossRunRegistry.resetForTest();
    }

    @Test
    void trackedWzPutsTheSummonBitOnTheBattleRewardKpqAndLpqMapsButNotOnMuYoungsRoom() throws Exception {
        assertTrue(FieldLimit.SUMMON.check(fieldLimit(BATTLE_MAP)));
        assertTrue(FieldLimit.SUMMON.check(fieldLimit(CLEAR_MAP)));
        assertTrue(FieldLimit.SUMMON.check(fieldLimit(KPQ_STAGE_MAP)));
        assertTrue(FieldLimit.SUMMON.check(fieldLimit(LPQ_BOSS_MAP)));
        assertFalse(FieldLimit.SUMMON.check(fieldLimit(RECRUIT_MAP)));
    }

    @Test
    void insideMemberOnTheBattleMapNeverSummons() throws Exception {
        inside(openRun(), ISOLDE);
        Character isolde = agent(ISOLDE, BATTLE_MAP);

        assertEquals(0, AgentCombatSummonRuntime.tickSummons(entry, isolde, skillId -> true));
        assertEquals(AgentCombatSummonRuntime.SummonResult.BARRED,
                AgentCombatSummonRuntime.trySummon(entry, isolde, SUMMON_DRAGON));
        verify(combat, never()).dispatchSummonSpecialMove(any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void insideZakumMemberOnTheAltarNeverSummons() throws Exception {
        assertTrue(FieldLimit.SUMMON.check(fieldLimit(ZAKUM_ALTAR)), "the altar forbids summons to players too");
        inside(openRun(AgentBossRunCatalog.ZAKUM), ISOLDE);
        Character isolde = agent(ISOLDE, ZAKUM_ALTAR);

        assertEquals(0, AgentCombatSummonRuntime.tickSummons(entry, isolde, skillId -> true));
        assertEquals(AgentCombatSummonRuntime.SummonResult.BARRED,
                AgentCombatSummonRuntime.trySummon(entry, isolde, SUMMON_DRAGON));
        verify(combat, never()).dispatchSummonSpecialMove(any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void insideMemberInTheRewardRoomNeverSummonsEither() throws Exception {
        inside(openRun(), ISOLDE);

        assertEquals(0, AgentCombatSummonRuntime.tickSummons(entry, agent(ISOLDE, CLEAR_MAP), skillId -> true));
        verify(combat, never()).dispatchSummonSpecialMove(any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void theSameAgentInNoBossRunStillSummonsOnTheBattleMap() throws Exception {
        Character isolde = agent(ISOLDE, BATTLE_MAP);

        assertEquals(SUMMON_DRAGON, AgentCombatSummonRuntime.tickSummons(entry, isolde, skillId -> true));
        verify(combat).dispatchSummonSpecialMove(eq(isolde), eq(SUMMON_DRAGON), eq(1), anyInt());
    }

    @Test
    void insideMemberOnAMapWithoutTheBitStillSummons() throws Exception {
        inside(openRun(), ISOLDE);
        Character isolde = agent(ISOLDE, RECRUIT_MAP);

        assertEquals(SUMMON_DRAGON, AgentCombatSummonRuntime.tickSummons(entry, isolde, skillId -> true));
        verify(combat).dispatchSummonSpecialMove(eq(isolde), eq(SUMMON_DRAGON), eq(1), anyInt());
    }

    @Test
    void agentInNoBossRunOnKpqAndLpqMapsIsUnaffectedWhileARunIsOpen() throws Exception {
        inside(openRun(), ISOLDE);
        Character kpq = agent(KPQ_AGENT, KPQ_STAGE_MAP);
        Character lpq = agent(KPQ_AGENT + 1, LPQ_BOSS_MAP);

        assertEquals(SUMMON_DRAGON, AgentCombatSummonRuntime.tickSummons(entry, kpq, skillId -> true));
        assertEquals(AgentCombatSummonRuntime.SummonResult.CAST,
                AgentCombatSummonRuntime.trySummon(entry, lpq, SUMMON_DRAGON));
        verify(combat, times(2)).dispatchSummonSpecialMove(any(), eq(SUMMON_DRAGON), eq(1), anyInt());
    }

    @Test
    void insideAnswersOnlyForInsideMembersOfAnOpenRun() {
        AgentBossRunSession session = openRun();
        session.addMember(THANE, OWNER, NOW);
        session.addMember(ISOLDE, OWNER, NOW);

        assertFalse(AgentBossRunRegistry.inside(THANE), "WAITING");
        session.member(THANE).registered();
        assertFalse(AgentBossRunRegistry.inside(THANE), "REGISTERED");
        inside(session, ISOLDE);
        assertTrue(AgentBossRunRegistry.inside(ISOLDE));
        assertFalse(AgentBossRunRegistry.inside(KPQ_AGENT), "no run");

        session.member(ISOLDE).leftRun(AgentBossRunHost.Out.DIED);
        assertFalse(AgentBossRunRegistry.inside(ISOLDE), "OUT");

        session.member(THANE).adopted();
        assertTrue(AgentBossRunRegistry.inside(THANE));
        AgentBossRunRegistry.remove(session);
        assertFalse(AgentBossRunRegistry.inside(THANE), "run ended");
    }

    private static AgentBossRunSession openRun() {
        return openRun(AgentBossRunCatalog.EASY_BALROG);
    }

    private static AgentBossRunSession openRun(AgentBossRunDefinition definition) {
        AgentBossRunSession session = new AgentBossRunSession(7L, definition,
                mock(Expedition.class), new FakeBossRunPorts.Fight(), NOW);
        AgentBossRunRegistry.put(session);
        return session;
    }

    private static void inside(AgentBossRunSession session, int agentId) {
        AgentBossRunMember member = session.member(agentId);
        if (member == null) {
            member = session.addMember(agentId, OWNER, NOW);
        }
        member.registered();
        member.adopted();
    }

    private Character agent(int agentId, int mapId) throws Exception {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(mapId);
        when(map.getFieldLimit()).thenReturn(fieldLimit(mapId));
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(agentId);
        when(bot.isAlive()).thenReturn(true);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(mapId);
        when(bot.getSkillLevel(skill)).thenReturn((byte) 1);
        when(bot.getSummonsValues()).thenReturn(List.of());
        return bot;
    }

    private static int fieldLimit(int mapId) throws Exception {
        Path file = Path.of("wz/Map.wz/Map/Map" + mapId / 100_000_000 + "/" + mapId + ".img.xml");
        Matcher matcher = FIELD_LIMIT.matcher(Files.readString(file));
        assertTrue(matcher.find(), () -> file + " has no fieldLimit");
        return Integer.parseInt(matcher.group(1));
    }
}
