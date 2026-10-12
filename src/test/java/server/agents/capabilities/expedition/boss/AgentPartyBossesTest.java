package server.agents.capabilities.expedition.boss;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import scripting.event.EventInstanceManager;
import server.agents.auth.AgentClaimGuard;
import server.expeditions.ExpeditionType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P14, the first party bosses: Capt. Latanica and the five Neo City bosses. Each event script's afterSetup opens its
 * catalog row's run, and the row's maps and level match the script's. Inside an event with an open party-instance run
 * the parts its boss rules name are that boss's parts (the control-steal guard covers them there, AgentBossScope), and
 * nowhere else.
 */
class AgentPartyBossesTest {
    private static final int OWNER = 1;
    private static final int THANE = 10;
    private static final long NOW = 1_000_000L;

    private final FakeBossRunPorts ports = new FakeBossRunPorts();
    private EventInstanceManager event;
    private Character owner;

    @BeforeEach
    void setUp() {
        event = mock(EventInstanceManager.class);
        when(event.getPlayerById(anyInt())).thenReturn(null);
        owner = ports.human(OWNER);
        when(event.getPlayers()).thenReturn(List.of(owner));
        when(event.getPlayerById(OWNER)).thenReturn(owner);
        AgentClaimGuard.install(new AgentClaimGuard.Rule() {
            @Override
            public boolean mayControl(Character actor, int agentCharacterId) {
                return true;
            }

            @Override
            public boolean isClaimed(int agentCharacterId) {
                return true;
            }
        });
    }

    @AfterEach
    void tearDown() {
        AgentClaimGuard.install(null);
        AgentBossRunRegistry.resetForTest();
    }

    @ParameterizedTest
    @CsvSource({"LatanicaBattle, latanica", "TD_Battle1, neo_bergamot", "TD_Battle2, neo_dunas",
            "TD_Battle3, neo_aufheben", "TD_Battle4, neo_oberon", "TD_Battle5, neo_nibelung",
            "PapulatusBattle, papulatus"})
    void eachEventScriptOpensItsOwnCatalogRowsRun(String script, String key) throws Exception {
        String source = Files.readString(Path.of("scripts/event/" + script + ".js"));
        AgentBossRunDefinition definition = AgentBossRunCatalog.of(key);

        assertTrue(source.contains("bossPartyEntered(eim, '" + key + "')"), script + " afterSetup");
        assertTrue(definition.partyInstance(), key);
        assertEquals(number(source, "entryMap"), definition.battleMapId(), key + " battle map");
        assertEquals(number(source, "recruitMap"), definition.recruitMapId(), key + " gate");
        assertEquals(number(source, "minLevel"), definition.minLevel(), key + " level");
        assertEquals(AgentBossRunDefinition.DeathMode.WAIT_OUTSIDE, definition.deathMode());
        assertTrue(definition.ownerLeavingTakesParty(), key);
    }

    @Test
    void latanicaLeavesByBobAndNeoCityByItsBattleMapsPortal() {
        assertEquals(9_270_033, AgentBossRunCatalog.LATANICA.exitNpcId());
        assertTrue(AgentBossRunCatalog.LATANICA.exitNpcAfterClear());
        for (AgentBossRunDefinition neo : List.of(AgentBossRunCatalog.NEO_BERGAMOT, AgentBossRunCatalog.NEO_DUNAS,
                AgentBossRunCatalog.NEO_AUFHEBEN, AgentBossRunCatalog.NEO_OBERON, AgentBossRunCatalog.NEO_NIBELUNG)) {
            assertEquals(neo.battleMapId(), neo.clearMapId(), neo.key() + ": members leave from the battle map");
            assertEquals(1, neo.rewardExitPortalId(), neo.key() + ": by portal 1");
            assertEquals(0, neo.exitNpcId(), neo.key() + ": no exit NPC to prepare");
        }
        assertEquals(ExpeditionType.ZAKUM.getMinLevel(), AgentBossRunCatalog.ZAKUM.minLevel(), "an expedition's own");
    }

    /**
     * P17: Scarlion and Targa is an expedition row (Aldol's list, no choices) whose maps match ScargaBattle.js, whose
     * dead re-enter by Aldol, and whose exit NPC 9201134 on the battle map lets members out before and after the clear.
     */
    @Test
    void scargaIsAnExpeditionRowWhoseDeadReenterByAldol() throws Exception {
        String source = Files.readString(Path.of("scripts/event/ScargaBattle.js"));
        AgentBossRunDefinition scarga = AgentBossRunCatalog.of("scarga");

        assertEquals(ExpeditionType.SCARGA, scarga.type());
        assertFalse(scarga.partyInstance());
        assertEquals(number(source, "entryMap"), scarga.battleMapId());
        assertEquals(number(source, "recruitMap"), scarga.recruitMapId());
        assertEquals(100, scarga.minLevel(), "the expedition type's Lv100");
        assertEquals(9_270_047, scarga.entryNpcId());
        assertEquals(List.of(), scarga.joinSelections());
        assertEquals(9_201_134, scarga.exitNpcId());
        assertTrue(scarga.exitNpcAfterClear());
        assertTrue(scarga.ownerLeavingTakesParty());
        assertEquals(AgentBossRunDefinition.DeathMode.REENTER, scarga.deathMode());
        assertEquals(List.of(9_420_545, 9_420_550, 9_420_531, 9_420_532), AgentBossRules.of("scarga").adds());
    }

    @Test
    void anOpenPartyRunsEventNamesItsBossesPartsAndNothingElseDoes() {
        long sessionId = openLatanica(List.of(THANE));
        assertTrue(sessionId > 0);

        assertTrue(AgentBossRunRegistry.partyBossPart(event, 9_420_513), "Capt. Latanica");
        assertTrue(AgentBossRunRegistry.partyBossPart(event, 9_420_511), "an add");
        assertFalse(AgentBossRunRegistry.partyBossPart(event, 100_100), "a snail");
        assertFalse(AgentBossRunRegistry.partyBossPart(mock(EventInstanceManager.class), 9_420_513),
                "another party's event, with no companion run");
        assertFalse(AgentBossRunRegistry.partyBossPart(null, 9_420_513), "a field map");

        AgentBossRunRegistry.remove(AgentBossRunRegistry.byId(sessionId));
        assertFalse(AgentBossRunRegistry.partyBossPart(event, 9_420_513), "the run ended");
    }

    private long openLatanica(List<Integer> agentIds) {
        return AgentBossRunService.openForEvent(ports, owner, AgentBossRunCatalog.LATANICA, event, agentIds, NOW)
                .sessionId();
    }

    private static int number(String source, String name) {
        Matcher matcher = Pattern.compile(name + " = (\\d+)").matcher(source);
        assertTrue(matcher.find(), name);
        return Integer.parseInt(matcher.group(1));
    }
}
