package net.server.channel.handlers;

import client.BuddyList;
import client.Character;
import client.Client;
import net.server.PlayerStorage;
import net.server.channel.Channel;
import net.server.world.World;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.living.LivingWorldHooks;
import testutil.Packets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Accepting a buddy request (BUDDYLIST_MODIFY mode 2) puts the other character in the group the living world names
 * for it (P9: "Crew" for your own companion), else the stock "Default Group"; it shows online on the channel it is on.
 */
class BuddylistAcceptGroupTest {
    private static final int ANN = 10;
    private static final int CASSE = 333;

    @Test
    void anAcceptedCompanionLandsInTheGroupTheLivingWorldNames() {
        BuddyList annList = new BuddyList(20);
        annList.put(new client.BuddylistEntry("Casse", "Default Group", CASSE, 2, false)); // her request, unanswered
        Character ann = mock(Character.class);
        when(ann.getId()).thenReturn(ANN);
        when(ann.getName()).thenReturn("Ann");
        when(ann.getBuddylist()).thenReturn(annList);
        Client client = mock(Client.class);
        World world = mock(World.class);
        Channel channel = mock(Channel.class);
        PlayerStorage storage = mock(PlayerStorage.class);
        Character casse = mock(Character.class);
        when(casse.getName()).thenReturn("Casse");
        when(client.getPlayer()).thenReturn(ann);
        when(client.getWorldServer()).thenReturn(world);
        when(client.getChannelServer()).thenReturn(channel);
        when(client.getChannel()).thenReturn(1);
        when(channel.getPlayerStorage()).thenReturn(storage);
        when(storage.getCharacterById(CASSE)).thenReturn(casse);
        when(world.find(CASSE)).thenReturn(2);

        try (MockedStatic<LivingWorldHooks> hooks = mockStatic(LivingWorldHooks.class)) {
            hooks.when(() -> LivingWorldHooks.buddyGroup(ann, CASSE)).thenReturn("Crew");

            new BuddylistModifyHandler().handlePacket(Packets.buildInPacket(out -> {
                out.writeByte(2);
                out.writeInt(CASSE);
            }), client);
        }

        assertTrue(annList.containsVisible(CASSE));
        assertEquals("Crew", annList.get(CASSE).getGroup());
        assertEquals(2, annList.get(CASSE).getChannel());
    }
}
