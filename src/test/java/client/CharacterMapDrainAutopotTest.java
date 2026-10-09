package client;

import client.inventory.InventoryType;
import client.inventory.Item;
import client.keybind.KeyBinding;
import client.processor.action.PetAutopotProcessor;
import net.server.Server;
import net.server.channel.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.TimerManager;
import server.agents.integration.cosmic.CosmicAgentPotionCheckRequestBridge;
import server.agents.integration.cosmic.CosmicAgentPotionCheckRequestBridge.AutopotSelection;
import server.maps.MapManager;
import server.maps.MapleMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * Live Easy Balrog run 1 (2026-10-09): companion Isolde stood at 47% HP for about a minute with 725 HP potions. The
 * Balrog map 105100400 drains 50 HP (Map.wz decHP) on every World drain tick through {@link Character#doHurtHp}, and
 * that loss never ran the server autopot. A human's client pet autopot answers the HP packet; a headless Agent has no
 * client, so a drain tick on an Agent now runs the server autopot. A human keeps the stock loss, so it never drinks twice.
 */
class CharacterMapDrainAutopotTest {
    private static final int BALROG_MAP_DRAIN = 50;
    private static final int WHITE_POTION = 2_000_002;
    private static final short POTIONS = 725;
    private static final int MAX_HP = 1_000;
    private static final float HP_ALERT = 0.5f;
    private static final int AUTOPOT_HP_KEY = 91;
    private static final int ITEM_KEY_TYPE = 7;

    private MockedStatic<TimerManager> timers;
    private MockedStatic<Server> servers;
    private Channel channel;
    private MapleMap balrogMap;

    @BeforeEach
    void setUp() {
        timers = mockStatic(TimerManager.class);
        timers.when(TimerManager::getInstance).thenReturn(mock(TimerManager.class));
        Server server = mock(Server.class);
        channel = mock(Channel.class);
        MapManager maps = mock(MapManager.class);
        balrogMap = mock(MapleMap.class);
        when(balrogMap.getHPDec()).thenReturn(BALROG_MAP_DRAIN);
        when(server.getChannel(anyInt(), anyInt())).thenReturn(channel);
        when(channel.getMapFactory()).thenReturn(maps);
        when(maps.getMap(anyInt())).thenReturn(balrogMap);
        servers = mockStatic(Server.class);
        servers.when(Server::getInstance).thenReturn(server);
    }

    @AfterEach
    void tearDown() {
        servers.close();
        timers.close();
    }

    @Test
    void anAgentDrinksOnceTheDrainTakesItUnderItsAlert() {
        try (MockedStatic<CosmicAgentPotionCheckRequestBridge> choices =
                     mockStatic(CosmicAgentPotionCheckRequestBridge.class);
             MockedStatic<PetAutopotProcessor> drinks = mockStatic(PetAutopotProcessor.class)) {
            BotClient client = new BotClient(0, 1);
            Character isolde = onBalrogMap(client, 560);
            short slot = potionSlot(isolde);
            choices.when(() -> CosmicAgentPotionCheckRequestBridge.selectAutopotForCurrentDeficit(isolde, true))
                    .thenReturn(new AutopotSelection(slot, WHITE_POTION));

            isolde.doHurtHp();

            assertEquals(510, isolde.getHp(), "51% is still above the 50% alert");
            drinks.verify(() -> PetAutopotProcessor.runAutopotAction(any(), anyShort(), anyInt()), never());

            isolde.doHurtHp();

            assertEquals(460, isolde.getHp());
            drinks.verify(() -> PetAutopotProcessor.runAutopotAction(client, slot, WHITE_POTION));
        }
    }

    @Test
    void aHumanKeepsTheStockDrainAndGetsNoServerAutopot() {
        try (MockedStatic<PetAutopotProcessor> drinks = mockStatic(PetAutopotProcessor.class)) {
            Client client = mock(Client.class);
            when(client.getChannelServer()).thenReturn(channel);
            Character human = onBalrogMap(client, 460);
            when(client.getPlayer()).thenReturn(human);

            human.doHurtHp();

            assertEquals(410, human.getHp());
            drinks.verify(() -> PetAutopotProcessor.runAutopotAction(any(), anyShort(), anyInt()), never());
        }
    }

    @Test
    void aLethalDrainTickOnAnAgentThrowsNothingAndDrinksNothing() {
        try (MockedStatic<CosmicAgentPotionCheckRequestBridge> choices =
                     mockStatic(CosmicAgentPotionCheckRequestBridge.class)) {
            BotClient client = new BotClient(0, 1);
            Character isolde = onBalrogMap(client, 30);
            client.setPlayer(isolde);
            short slot = potionSlot(isolde);
            choices.when(() -> CosmicAgentPotionCheckRequestBridge.selectAutopotForCurrentDeficit(isolde, true))
                    .thenReturn(new AutopotSelection(slot, WHITE_POTION));

            assertDoesNotThrow(isolde::doHurtHp);

            assertEquals(0, isolde.getHp());
            choices.verify(() -> CosmicAgentPotionCheckRequestBridge.selectAutopotForCurrentDeficit(isolde, true));
            assertEquals(POTIONS, isolde.getInventory(InventoryType.USE).getItem(slot).getQuantity(),
                    "the autopot refuses a dead character");
        }
    }

    /**
     * A character on the Balrog map with HP potions, its HP autopot key bound and a 50% alert. The max HP is set
     * directly: a max HP update recalculates equip stats through ItemInformationProvider, which needs the database.
     */
    private Character onBalrogMap(Client client, int hp) {
        Character chr = Character.getDefault(client);
        chr.setMap(balrogMap);
        chr.setMaxHp(MAX_HP);
        chr.localmaxhp = MAX_HP;
        chr.updateHp(hp);
        chr.getInventory(InventoryType.USE).addItem(new Item(WHITE_POTION, (short) 0, POTIONS));
        chr.changeKeybinding(AUTOPOT_HP_KEY, new KeyBinding(ITEM_KEY_TYPE, WHITE_POTION));
        chr.setAutopotHpAlert(HP_ALERT);
        return chr;
    }

    private static short potionSlot(Character chr) {
        return chr.getInventory(InventoryType.USE).findById(WHITE_POTION).getPosition();
    }
}
