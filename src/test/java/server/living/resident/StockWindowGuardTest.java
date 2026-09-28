package server.living.resident;

import net.packet.InPacket;
import net.packet.Packet;
import net.server.channel.handlers.NPCTalkHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import scripting.AbstractPlayerInteraction;
import scripting.npc.NPCScriptManager;
import scripting.quest.QuestScriptManager;
import server.life.NPC;
import server.maps.MapleMap;
import testutil.HandlerTest;
import testutil.NpcTalks;
import testutil.Packets;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * While a LivingTalk window (the Crew window, a card, a Resident menu) awaits its reply, no stock NPC or quest
 * window may start: a second NPC_TALK drops the client (docs/BOT_MANAGEMENT_P0_RESULTS.md, probe 6). Every stock
 * starter answers with enableActions only: a chat command's openNpc (@help, @gacha, @staff), an NPC click, an NPC or
 * item script start, a party script start and a quest script start or end.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockWindowGuardTest extends HandlerTest {
    private static final int CREW_WINDOW_NPC = 9040011;
    private static final int ATHENA = 1012100;
    private static final int ATHENA_OID = 77;

    private final MapleMap map = mock(MapleMap.class);

    @BeforeEach
    void openTheCrewWindow() {
        when(chr.getId()).thenReturn(10);
        when(chr.getName()).thenReturn("Ann");
        when(chr.getClient()).thenReturn(client);
        when(chr.getMap()).thenReturn(map);
        when(chr.isAlive()).thenReturn(true);
        when(client.canClickNPC()).thenReturn(true);
        LivingTalk.open(chr, CREW_WINDOW_NPC).speaker(2).ok("the Crew window, awaiting its reply").show();
        assertTrue(LivingTalk.active(client));
        clearInvocations(client);
    }

    @AfterEach
    void closeIt() {
        LivingTalk.forget(chr);
    }

    @Test
    void aChatCommandsOpenNpcSendsNothingAndKeepsTheClickThrottle() {
        new AbstractPlayerInteraction(client).openNpc(9010000); // @help, @gacha, @staff

        verify(client, never()).removeClickedNPC();
        assertNoSecondWindow();
    }

    @Test
    void anNpcOrItemScriptStartIsHandledWithEnableActionsOnly() {
        assertTrue(NPCScriptManager.getInstance().start(client, ATHENA, null)); // true: an NPC click never falls to its shop
        NPCScriptManager.getInstance().start("MatchCheckerCPQChallenge", client, ATHENA, List.of()); // a party start

        assertNull(NPCScriptManager.getInstance().getCM(client));
        assertNoSecondWindow();
    }

    @Test
    void aQuestScriptStartOrEndIsRefusedWithEnableActionsOnly() {
        QuestScriptManager.getInstance().start(client, (short) 2000, ATHENA);
        QuestScriptManager.getInstance().end(client, (short) 2000, ATHENA);

        assertNull(QuestScriptManager.getInstance().getQM(client));
        assertNoSecondWindow();
    }

    @Test
    void anNpcClickOpensNeitherAScriptNorAShop() {
        NPC athena = mock(NPC.class);
        when(athena.getId()).thenReturn(ATHENA);
        when(athena.getName()).thenReturn("Athena Pierce");
        when(athena.hasShop()).thenReturn(true);
        when(map.getMapObject(ATHENA_OID)).thenReturn(athena);
        when(chr.getNpcCooldown()).thenReturn(-60_000L); // past the stock 500 ms re-click guard

        new NPCTalkHandler().handlePacket(click(ATHENA_OID), client);

        verify(athena, never()).sendShop(any());
        assertNoSecondWindow();
    }

    @Test
    void onceTheWindowIsAnsweredTheStockStartersRunAgain() {
        assertTrue(LivingTalk.respond(client, (byte) 0, (byte) 1, Packets.buildInPacket(out -> { })));
        assertFalse(LivingTalk.active(client));
    }

    private void assertNoSecondWindow() {
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(client, atLeast(0)).sendPacket(sent.capture());
        assertTrue(sent.getAllValues().stream().noneMatch(NpcTalks::isTalk), "a second NPC_TALK went out");
        assertTrue(sent.getAllValues().stream().allMatch(NpcTalks::isEnableActions));
        assertTrue(LivingTalk.active(client)); // the Crew window's reply is still expected
    }

    private static InPacket click(int objectId) {
        return Packets.buildInPacket(out -> {
            out.writeInt(objectId);
            out.writeShort(0);
            out.writeShort(0);
        });
    }
}
