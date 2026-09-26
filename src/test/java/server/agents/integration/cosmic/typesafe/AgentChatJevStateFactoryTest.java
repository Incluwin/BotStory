package server.agents.integration.cosmic.typesafe;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentFarmAnchorState;
import server.agents.capabilities.shop.AgentShopState;
import server.agents.runtime.AgentModeState;
import server.agents.runtime.AgentRuntimeEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentChatJevStateFactoryTest {
    @Test
    void activityDistinguishesShoppingHoldingASpotHuntingFollowingAndIdle() {
        assertEquals("shopping at an NPC store", AgentChatJevStateFactory.activity(entry(true, true, true, false)));
        assertEquals("holding a spot and fighting monsters that come near",
                AgentChatJevStateFactory.activity(entry(false, true, true, false)));
        assertEquals("hunting monsters", AgentChatJevStateFactory.activity(entry(false, true, false, false)));
        assertEquals("following its leader", AgentChatJevStateFactory.activity(entry(false, false, false, true)));
        assertEquals("idle", AgentChatJevStateFactory.activity(entry(false, false, false, false)));
    }

    private static AgentRuntimeEntry entry(boolean shopping, boolean grinding, boolean anchored, boolean following) {
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        AgentShopState shop = mock(AgentShopState.class);
        AgentModeState mode = mock(AgentModeState.class);
        AgentFarmAnchorState anchor = mock(AgentFarmAnchorState.class);
        when(shop.visitPending()).thenReturn(shopping);
        when(mode.grinding()).thenReturn(grinding);
        when(mode.following()).thenReturn(following);
        when(anchor.hasAnchor()).thenReturn(anchored);
        when(entry.shopState()).thenReturn(shop);
        when(entry.modeState()).thenReturn(mode);
        when(entry.farmAnchorState()).thenReturn(anchor);
        return entry;
    }
}
