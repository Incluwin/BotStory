package server.living.population;

import client.BuddyList;
import client.Character;
import client.Client;
import config.PopulationConfig;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.living.population.CitizenStore.Acquaintance;

import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A citizen you met asks you to be buddies after half an hour in your party (or an hour and a half on your map), as
 * before; since P9 it lists you on its own list first, so after your accept you see it come online and go offline.
 */
class SocialFabricBuddyRequestTest {
    private static final int CITIZEN = 963;
    private static final int ANN = 10;

    @Test
    void aCitizenAfterHalfAnHourInYourPartyListsYouAndAsks() {
        Population population = mock(Population.class);
        CitizenStore store = mock(CitizenStore.class);
        when(population.config()).thenReturn(new PopulationConfig());
        when(population.store()).thenReturn(store);
        Citizen citizen = new Citizen(CITIZEN, "Pip", "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d,
                0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        CitizenSession session = new CitizenSession(citizen, 0L, 60_000L, false);
        session.entry = mock(AgentRuntimeEntry.class);
        Character agent = character(CITIZEN, "Pip", new BuddyList(20), 1);
        BuddyList annList = new BuddyList(20);
        Character ann = character(ANN, "Ann", annList, 1);

        try (MockedStatic<AgentRuntimeIdentityRuntime> identity = mockStatic(AgentRuntimeIdentityRuntime.class)) {
            identity.when(() -> AgentRuntimeIdentityRuntime.bot(any())).thenReturn(agent);
            new SocialFabric(population).maybeBuddyRequest(session, ann, new Acquaintance(CITIZEN, ANN, 30, 31, false, false));
        }

        assertTrue(agent.getBuddylist().containsVisible(ANN), "Pip lists Ann first");
        assertTrue(annList.contains(CITIZEN) && !annList.containsVisible(CITIZEN), "Ann has the request to answer");
        verify(store).saveAcquaintance(new Acquaintance(CITIZEN, ANN, 30, 31, true, false));
    }

    private static Character character(int id, String name, BuddyList list, int channel) {
        Character chr = mock(Character.class);
        Client client = mock(Client.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getBuddylist()).thenReturn(list);
        when(chr.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(chr);
        when(client.getChannel()).thenReturn(channel);
        return chr;
    }
}
