package server.living.population;

import client.Character;
import client.Client;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import config.LivingWorldConfig;
import config.PopulationConfig;
import net.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.living.LivingWorld;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatterEngine.megaphone, the one way to send a megaphone outside the cadence (the rival crew's boast, F7; LF party
 * megaphones, F5c): it takes a slot of CHATTER_SMEGA_PER_HOUR and keeps the 10-minute gap, so the shared cap holds; with
 * consumeItem the citizen must carry a Super Megaphone and uses one up; blocked, it sends and counts nothing.
 */
class ChatterMegaphoneTest {
    private static final String BOAST = "Ironclads did Easy Balrog in 14:20";

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final Character agent = mock(Character.class);
    private final Server server = mock(Server.class);
    private MockedStatic<AgentRuntimeIdentityRuntime> identity;
    private MockedStatic<Server> servers;
    private ChatterEngine chatter;
    private CitizenSession speaker;

    @BeforeEach
    void setUp() {
        config.CHATTER_ENABLED = true;
        config.CHATTER_SMEGA_PER_HOUR = 4;
        when(population.config()).thenReturn(config);
        LivingWorld world = mock(LivingWorld.class);
        when(world.config()).thenReturn(new LivingWorldConfig());
        when(population.world()).thenReturn(world);
        Citizen kim = new Citizen(5001, "Kim", "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        speaker = CitizenSessions.loaded(kim, 0L, Long.MAX_VALUE);
        speaker.entry = mock(AgentRuntimeEntry.class);
        speaker.channel = 1;
        when(agent.getId()).thenReturn(5001);
        when(agent.getName()).thenReturn("Kim");
        when(agent.getClient()).thenReturn(mock(Client.class));
        identity = mockStatic(AgentRuntimeIdentityRuntime.class);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(any())).thenReturn(agent);
        servers = mockStatic(Server.class);
        servers.when(Server::getInstance).thenReturn(server);
        chatter = new ChatterEngine(population);
    }

    @AfterEach
    void close() {
        identity.close();
        servers.close();
    }

    private int countThisHour() throws Exception {
        Field field = ChatterEngine.class.getDeclaredField("smegasThisHour");
        field.setAccessible(true);
        return field.getInt(chatter);
    }

    private void moveGapBack(long ms) throws Exception {
        Field field = ChatterEngine.class.getDeclaredField("lastSmegaMs");
        field.setAccessible(true);
        field.setLong(chatter, System.currentTimeMillis() - ms);
    }

    @Test
    void oneSlotOfTheHourGoesOutToTheWorldAndTheGapHolds() throws Exception {
        assertTrue(chatter.megaphone(speaker, BOAST, false));
        assertEquals(1, countThisHour());
        verify(server, times(1)).broadcastMessage(anyInt(), any());

        assertFalse(chatter.megaphone(speaker, BOAST, false), "ten minutes apart, as the cadence's own");
        assertEquals(1, countThisHour());
        verify(server, times(1)).broadcastMessage(anyInt(), any());
    }

    @Test
    void theHourlyCapIsSharedWithEveryOtherMegaphone() throws Exception {
        config.CHATTER_SMEGA_PER_HOUR = 1;
        assertTrue(chatter.megaphone(speaker, BOAST, false));
        moveGapBack(11 * 60_000L);

        assertFalse(chatter.megaphone(speaker, BOAST, false), "the hour's one megaphone is spent");
        assertEquals(1, countThisHour());
    }

    @Test
    void aRealMegaphoneIsUsedUpOnlyWhenAsked() {
        try (MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class)) {
            when(agent.getItemQuantity(ChatterEngine.SUPER_MEGAPHONE, false)).thenReturn(0);
            assertFalse(chatter.megaphone(speaker, BOAST, true), "none to use: nothing is sent");
            verify(server, never()).broadcastMessage(anyInt(), any());

            when(agent.getItemQuantity(ChatterEngine.SUPER_MEGAPHONE, false)).thenReturn(3);
            assertTrue(chatter.megaphone(speaker, BOAST, true));

            inventory.verify(() -> InventoryManipulator.removeById(any(), eq(InventoryType.CASH),
                    eq(ChatterEngine.SUPER_MEGAPHONE), eq(1), eq(false), eq(false)));
        }
    }

    @Test
    void theRivalBoastNeedsNoItem() {
        try (MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class)) {
            assertTrue(chatter.megaphone(speaker, BOAST, false));
            inventory.verify(() -> InventoryManipulator.removeById(any(), any(), anyInt(), anyInt(), anyBoolean(),
                    anyBoolean()), never());
        }
    }

    @Test
    void chatterOffAnAwayCitizenOrNoWordsSendNothing() throws Exception {
        speaker.virtual = true;
        assertFalse(chatter.megaphone(speaker, BOAST, false));
        speaker.virtual = false;
        assertFalse(chatter.megaphone(speaker, " ", false));
        config.CHATTER_ENABLED = false;
        assertFalse(chatter.megaphone(speaker, BOAST, false));

        assertEquals(0, countThisHour());
        verify(server, never()).broadcastMessage(anyInt(), any());
    }
}
