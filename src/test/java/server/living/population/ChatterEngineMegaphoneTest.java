package server.living.population;

import client.Character;
import config.LivingWorldConfig;
import config.PopulationConfig;
import net.packet.Packet;
import net.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.TimerManager;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.living.LivingWorld;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * ChatterEngine.megaphone, the one megaphone entry point party megaphones and rival crews share (F5c, F7): one slot of
 * CHATTER_SMEGA_PER_HOUR and the random megaphones' 10-minute gap, a Super Megaphone used up when asked, the ear button
 * on, and nothing sent (false) when the cap, the gap or an empty cash tab blocks it.
 */
class ChatterEngineMegaphoneTest {
    private static final long MINUTE = 60_000L;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class, withSettings().stubOnly());
    private final List<String> notices = new ArrayList<>();
    private final List<Integer> itemsUsed = new ArrayList<>();
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private ChatterEngine chatter;
    private CitizenSession lia;
    private int megaphonesHeld;
    private long now;

    @BeforeEach
    void setUp() {
        config.CHATTER_ENABLED = true;
        config.CHATTER_SMEGA_PER_HOUR = 2;
        when(population.config()).thenReturn(config);
        VoicePacks voices = mock(VoicePacks.class, withSettings().stubOnly());
        when(voices.renderAnswer(any(), anyString(), anyLong(), anyBoolean())).thenAnswer(call -> call.getArgument(1));
        when(population.voices()).thenReturn(voices);
        LivingWorld world = mock(LivingWorld.class, withSettings().stubOnly());
        when(world.config()).thenReturn(new LivingWorldConfig());
        when(population.world()).thenReturn(world);
        FieldCatalog fields = mock(FieldCatalog.class, withSettings().stubOnly());
        when(fields.name(anyInt())).thenReturn("Kerning City");
        when(population.fields()).thenReturn(fields);
        PresenceScheduler presence = mock(PresenceScheduler.class, withSettings().stubOnly());
        when(population.presence()).thenReturn(presence);

        Character agent = mock(Character.class, withSettings().stubOnly());
        when(agent.getName()).thenReturn("Lia");
        when(agent.getId()).thenReturn(1);
        Citizen citizen = new Citizen(1, "Lia", "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d, 0, 2,
                new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        lia = new CitizenSession(citizen, 0L, 24 * 3_600_000L, false);
        lia.entry = mock(AgentRuntimeEntry.class, withSettings().stubOnly());
        open(AgentRuntimeIdentityRuntime.class).when(() -> AgentRuntimeIdentityRuntime.bot(lia.entry)).thenReturn(agent);
        lia.mapId = 103_000_000;
        when(presence.session(1)).thenReturn(lia);

        TimerManager timers = mock(TimerManager.class, withSettings().stubOnly());
        doAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return null;
        }).when(timers).schedule(any(TimerManager.SchedulerLane.class), any(Runnable.class), anyLong());
        open(TimerManager.class).when(TimerManager::getInstance).thenReturn(timers);
        Server server = mock(Server.class, withSettings().stubOnly());
        open(Server.class).when(Server::getInstance).thenReturn(server);
        open(PacketCreator.class).when(() -> PacketCreator.serverNotice(anyInt(), anyInt(), anyString(), anyBoolean()))
                .thenAnswer(call -> {
                    notices.add(call.getArgument(0) + "|ch" + call.getArgument(1) + "|" + call.getArgument(2) + "|ear="
                            + call.getArgument(3));
                    return mock(Packet.class);
                });

        chatter = new ChatterEngine(population);
        now = System.currentTimeMillis();
        chatter.megaphoneClock = () -> now;
        megaphonesHeld = 2;
        chatter.megaphoneItems = who -> {
            if (megaphonesHeld <= 0) {
                return false;
            }
            megaphonesHeld--;
            itemsUsed.add(who.getId());
            return true;
        };
    }

    @AfterEach
    void tearDown() {
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }

    private <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }

    @Test
    void aMegaphoneGoesOutAsASuperMegaphoneWithTheEarOnAndUsesOneUp() {
        assertTrue(chatter.megaphone(lia, "LF1M KPQ 21-30 ch2, whisper Lia", true));

        assertEquals(List.of("3|ch2|Lia : LF1M KPQ 21-30 ch2, whisper Lia|ear=true"), notices);
        assertEquals(List.of(1), itemsUsed);
        assertEquals(1, megaphonesHeld);
    }

    @Test
    void theTenMinuteGapHoldsForEveryMegaphoneAndATurnedDownOneUsesNothing() {
        assertTrue(chatter.megaphone(lia, "LF1M KPQ", true));

        now += 9 * MINUTE;
        assertFalse(chatter.megaphone(lia, "LF1M KPQ", true));
        assertEquals(1, notices.size());
        assertEquals(1, itemsUsed.size(), "a megaphone the gap turned down is not used up");

        now += 2 * MINUTE;
        assertTrue(chatter.megaphone(lia, "LF1M KPQ", true));
        assertEquals(2, notices.size());
    }

    @Test
    void theHourCapIsOneCounterForEveryCaller() {
        megaphonesHeld = 5;
        assertTrue(chatter.megaphone(lia, "LF1M KPQ", true));
        now += 11 * MINUTE;
        assertTrue(chatter.megaphone(lia, "rival line", false)); // a rival crew's (F7), no item
        now += 11 * MINUTE;

        assertFalse(chatter.megaphone(lia, "LF1M KPQ", true), "CHATTER_SMEGA_PER_HOUR (2) is spent");
        assertEquals(2, notices.size());
        assertEquals(1, itemsUsed.size());

        now += 40 * MINUTE; // a new hour
        assertTrue(chatter.megaphone(lia, "LF1M KPQ", true));
    }

    @Test
    void noMegaphoneInTheBagMeansNoLineAndNoSlotTaken() {
        megaphonesHeld = 0;

        assertFalse(chatter.megaphone(lia, "LF1M KPQ", true));
        assertTrue(notices.isEmpty());

        assertTrue(chatter.megaphone(lia, "a rival line", false), "the slot and the gap were not taken");
        assertEquals(1, notices.size());
    }

    @Test
    void nothingWhileChatterIsOffOrForAnAwaySession() {
        config.CHATTER_ENABLED = false;
        assertFalse(chatter.megaphone(lia, "LF1M KPQ", true));

        config.CHATTER_ENABLED = true;
        CitizenSession away = new CitizenSession(lia.citizen, 0L, 1L, true);
        assertFalse(chatter.megaphone(away, "LF1M KPQ", true));
        assertFalse(chatter.megaphone(lia, " ", true));

        assertTrue(notices.isEmpty());
        assertTrue(itemsUsed.isEmpty());
    }
}
