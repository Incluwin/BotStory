package server.agents.capabilities.partyquest.lpq;

import client.Character;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.plans.AgentScriptItemActionService;
import server.maps.MapItem;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * P6g LPQ C3.3 (amended B5): Alishar's key carrier about to go down (at or below PASS_DYING_HP_PERCENT with no HP
 * potion left) drops the key where it stands and says K5, so its death does not take the key out of the event
 * (LudiPQ's event exclusives go with an unregistered player); nobody else loots that stack unless it re-collects it.
 * With HP potions left the carrier walks on.
 */
class AgentLpqBossKeyDyingTest {
    private static final int S9 = AgentLpqDefinition.stage(9).mapId();
    private final LpqTickFixture fx = new LpqTickFixture();
    private List<Character> crew;
    private Character kes;

    @AfterEach
    void tearDown() {
        fx.close();
    }

    /** Kes is the key's carrier and holds it, far from Crewfriend; then he is at 20% HP. */
    private AgentLpqSession kesCarriesTheKeyAtLowHp(int hpPotions) {
        fx.npcAt(AgentLpqDefinition.stage(9).npcId(), new Point(222, 70));
        crew = fx.crew(S9, new Point(300, 184));
        kes = crew.get(2);
        fx.moveTo(crew.getFirst(), new Point(400, 184));
        fx.moveTo(kes, new Point(950, 184));
        fx.drop(S9, AgentLpqDefinition.BOSS_KEY, 1, new Point(980, 184), false, 0, 1_000L);
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_9, crew.toArray(Character[]::new));
        session.narrateOnce("s9-start");
        fx.tick(session, 3_000L);
        assertEquals(kes.getId(), session.keyCarrierId());
        assertEquals(1, kes.getItemQuantity(AgentLpqDefinition.BOSS_KEY, false));
        when(kes.getHp()).thenReturn(400);
        when(fx.actions.hpPotionCount(kes)).thenReturn(hpPotions);
        return session;
    }

    @Test
    void aCarrierAboutToGoDownDropsTheKeyWhereItStands() {
        AgentLpqSession session = kesCarriesTheKeyAtLowHp(0);
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class)) {
            fx.tick(session, 4_000L);

            assertEquals(1, log.startingWith("LPQ hand-over: session=" + session.sessionId() + " member=Kes(3)"
                    + " stage=9 item=4001023 quantity=1 place=DYING").size());
        }
        fx.items.verify(() -> AgentScriptItemActionService.dropItem(fx.entry(kes), InventoryType.ETC,
                AgentLpqDefinition.BOSS_KEY, (short) 1));
        fx.said(session, "s9-key-dying", kes, "almost out of pots, leaving the key here, grab it.");
        MapItem key = fx.map(S9).getDroppedItems().stream()
                .filter(drop -> drop.getItemId() == AgentLpqDefinition.BOSS_KEY).findFirst().orElseThrow();
        assertEquals(new Point(950, 184), key.getPosition(), "where it stands");

        fx.tickEvery(session, 5_000L, 30_000L, 1_000L);

        for (Character member : crew) {
            assertEquals(0, member.getItemQuantity(AgentLpqDefinition.BOSS_KEY, false), member.getName());
        }
        fx.said(session, "s9-key-dying", kes, "almost out of pots, leaving the key here, grab it.");
    }

    @Test
    void withHpPotionsLeftTheCarrierWalksOn() {
        AgentLpqSession session = kesCarriesTheKeyAtLowHp(5);

        fx.tick(session, 4_000L);

        fx.items.verify(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort()), never());
        fx.neverSaid("s9-key-dying");
        assertEquals(1, kes.getItemQuantity(AgentLpqDefinition.BOSS_KEY, false));
        org.mockito.Mockito.verify(fx.actions).navigate(eq(fx.entry(kes)), eq(new Point(400, 184)), eq(true));
    }
}
