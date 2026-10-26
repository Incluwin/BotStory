package server.living;

import client.Character;
import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.living.bond.Friendship;
import server.living.quest.QuestLedger;
import server.living.roster.CompanionExecutor;
import server.living.roster.CompanionPartyQuestService;
import server.living.roster.DirectExecutor;
import testutil.LogLines;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Boss clears are not party-quest clears (user decision 2026-10-08): EventInstanceManager.clearPQ runs for every event
 * clear, boss expeditions too. A clear of an event script that living_world.BOSS_EVENT_SCRIPTS lists marks no
 * companion PQ run as cleared, advances no "clear a party quest" step and gives no friendship PQ points; it logs one
 * INFO line instead. Friendship boss points (a boss's death, mobKilled) and companion boss-run records do not come
 * through this hook and stay as they are. Every other clear, and a clear whose script is unknown, is today's.
 */
class BossClearsNotPartyQuestsTest {
    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingWorldConfig config = new LivingWorldConfig();
    private final CompanionPartyQuestService partyQuests = mock(CompanionPartyQuestService.class);
    private final QuestLedger quests = mock(QuestLedger.class);
    private final Friendship friendship = mock(Friendship.class);
    private final CompanionExecutor ops = DirectExecutor.inline();
    private final Character ann = character(10, "Ann");
    private final Character thane = character(901, "Thane");
    private MockedStatic<LivingWorld> living;

    @BeforeEach
    void wire() {
        when(world.config()).thenReturn(config);
        when(world.partyQuests()).thenReturn(partyQuests);
        when(world.quests()).thenReturn(quests);
        when(world.friendship()).thenReturn(friendship);
        when(world.executor()).thenReturn(ops);
        living = mockStatic(LivingWorld.class);
        living.when(LivingWorld::get).thenReturn(world);
    }

    @AfterEach
    void close() {
        living.close();
    }

    @Test
    void aBossScriptClearSkipsEveryPartyQuestEffectAndLogsOneLine() {
        try (LogLines lines = new LogLines(LivingWorldHooks.class)) {
            LivingWorldHooks.partyQuestCleared(List.of(ann, thane), "ZakumBattle", "Zakum1");

            assertEquals(List.of("Boss event cleared: script=ZakumBattle instance=Zakum1 players=Ann,Thane"),
                    lines.startingWith("Boss event cleared"));
        }
        verify(partyQuests, never()).cleared(anyList());
        verify(quests, never()).onPartyQuestCleared(any(), anyString(), any());
        verify(friendship, never()).onPartyQuestCleared(anyList());
    }

    @Test
    void everyShippedBossScriptIsABossClear() {
        for (String script : new String[] {"BalrogBattle", "BalrogBattle_Easy", "ZakumBattle", "HorntailBattle",
                "PinkBeanBattle", "ScargaBattle", "ShowaBattle", "PapulatusBattle", "LatanicaBattle", "TD_Battle1",
                "TD_Battle2", "TD_Battle3", "TD_Battle4", "TD_Battle5"}) {
            LivingWorldHooks.partyQuestCleared(List.of(ann), script, script + "0");
        }
        verify(partyQuests, never()).cleared(anyList());
        verify(quests, never()).onPartyQuestCleared(any(), anyString(), any());
        verify(friendship, never()).onPartyQuestCleared(anyList());
    }

    @Test
    void aPartyQuestScriptClearKeepsAllThreeEffectsWithTheInstanceName() {
        try (LogLines lines = new LogLines(LivingWorldHooks.class)) {
            LivingWorldHooks.partyQuestCleared(List.of(ann, thane), "KerningPQ", "Kerning0");

            assertEquals(List.of(), lines.startingWith("Boss event cleared"));
        }
        verify(partyQuests).cleared(List.of(ann, thane));
        verify(quests).onPartyQuestCleared(ann, "Kerning0", "KerningPQ");
        verify(quests).onPartyQuestCleared(thane, "Kerning0", "KerningPQ");
        verify(friendship).onPartyQuestCleared(List.of(ann, thane));
    }

    @Test
    void theOtherPartyQuestsStayPartyQuests() {
        String[] scripts = {"HenesysPQ", "LudiPQ", "EllinPQ", "LudiMazePQ", "OrbisPQ", "PiratePQ", "MagatiaPQ_A",
                "CWKPQ", "ZakumPQ", "HorntailPQ"};
        for (String script : scripts) {
            LivingWorldHooks.partyQuestCleared(List.of(ann), script, script + "1");
        }
        for (String script : scripts) {
            verify(quests).onPartyQuestCleared(ann, script + "1", script);
        }
        verify(partyQuests, org.mockito.Mockito.times(scripts.length)).cleared(List.of(ann));
        verify(friendship, org.mockito.Mockito.times(scripts.length)).onPartyQuestCleared(List.of(ann));
    }

    @Test
    void anUnknownOrMissingScriptNameKeepsTodaysBehaviour() {
        LivingWorldHooks.partyQuestCleared(List.of(ann), "SomeNewEvent", "SomeNewEvent3");
        LivingWorldHooks.partyQuestCleared(List.of(ann), null, "Zakum1");
        LivingWorldHooks.partyQuestCleared(List.of(ann), "KerningPQ");

        verify(quests).onPartyQuestCleared(ann, "SomeNewEvent3", "SomeNewEvent");
        verify(quests).onPartyQuestCleared(ann, "Zakum1", null);
        verify(quests).onPartyQuestCleared(ann, "KerningPQ", null);
        verify(partyQuests, org.mockito.Mockito.times(3)).cleared(List.of(ann));
        verify(friendship, org.mockito.Mockito.times(3)).onPartyQuestCleared(List.of(ann));
    }

    @Test
    void anEmptyListMakesBossClearsCountAsBefore() {
        config.BOSS_EVENT_SCRIPTS = "";

        LivingWorldHooks.partyQuestCleared(List.of(ann), "ZakumBattle", "Zakum1");

        verify(partyQuests).cleared(List.of(ann));
        verify(quests).onPartyQuestCleared(ann, "Zakum1", "ZakumBattle");
        verify(friendship).onPartyQuestCleared(List.of(ann));
    }

    @Test
    void aWorldWithoutConfigKeepsTodaysBehaviour() {
        when(world.config()).thenReturn(null);

        LivingWorldHooks.partyQuestCleared(List.of(ann), "ZakumBattle", "Zakum1");

        verify(quests).onPartyQuestCleared(ann, "Zakum1", "ZakumBattle");
    }

    private static Character character(int id, String name) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        return character;
    }
}
