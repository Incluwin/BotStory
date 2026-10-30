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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * P20 (Pink Bean): warriors died with full potion bags to a deadly hit (HP to 1) and a second hit. An Agent's autopot
 * drinks one potion id up to its alert; when that item ran short the Agent stayed low until the next hit's autopot,
 * which comes after that hit's damage. Now it drinks the next potion it would choose while it is still under the alert.
 */
class CharacterAgentAutopotDrinkOnTest {
    private static final int ELIXIR = 2_000_004;
    private static final int REINDEER_MILK = 2_022_000;
    private static final int MAX_HP = 20_000;
    private static final float HP_ALERT = 0.7f;
    private static final int AUTOPOT_HP_KEY = 91;
    private static final int ITEM_KEY_TYPE = 7;

    private MockedStatic<TimerManager> timers;
    private MockedStatic<Server> servers;
    private MapleMap map;

    @BeforeEach
    void setUp() {
        timers = mockStatic(TimerManager.class);
        timers.when(TimerManager::getInstance).thenReturn(mock(TimerManager.class));
        Server server = mock(Server.class);
        Channel channel = mock(Channel.class);
        MapManager maps = mock(MapManager.class);
        map = mock(MapleMap.class);
        when(server.getChannel(anyInt(), anyInt())).thenReturn(channel);
        when(channel.getMapFactory()).thenReturn(maps);
        when(maps.getMap(anyInt())).thenReturn(map);
        servers = mockStatic(Server.class);
        servers.when(Server::getInstance).thenReturn(server);
    }

    @AfterEach
    void tearDown() {
        servers.close();
        timers.close();
    }

    @Test
    void anAgentLeftUnderItsAlertByAShortStackDrinksTheNextPotionAtOnce() {
        try (MockedStatic<CosmicAgentPotionCheckRequestBridge> choices =
                     mockStatic(CosmicAgentPotionCheckRequestBridge.class);
             MockedStatic<PetAutopotProcessor> drinks = mockStatic(PetAutopotProcessor.class)) {
            BotClient client = new BotClient(0, 1);
            Character thane = agent(client);
            short elixirs = slot(thane, ELIXIR);
            short milks = slot(thane, REINDEER_MILK);
            choices.when(() -> CosmicAgentPotionCheckRequestBridge.selectAutopotForCurrentDeficit(thane, true))
                    .thenReturn(new AutopotSelection(elixirs, ELIXIR), new AutopotSelection(milks, REINDEER_MILK));
            drinks.when(() -> PetAutopotProcessor.runAutopotAction(client, elixirs, ELIXIR))
                    .thenAnswer(invocation -> { // the last Elixir: half the bar, under the 70% alert
                        thane.updateHp(thane.getHp() + MAX_HP / 2);
                        return null;
                    });
            drinks.when(() -> PetAutopotProcessor.runAutopotAction(client, milks, REINDEER_MILK))
                    .thenAnswer(invocation -> {
                        thane.updateHp(thane.getHp() + 5_000);
                        return null;
                    });

            thane.addMPHPAndTriggerAutopot(-(thane.getHp() - 1), 0); // a deadly hit: HP to 1

            assertEquals(15_001, thane.getHp());
            drinks.verify(() -> PetAutopotProcessor.runAutopotAction(client, elixirs, ELIXIR));
            drinks.verify(() -> PetAutopotProcessor.runAutopotAction(client, milks, REINDEER_MILK));
        }
    }

    @Test
    void anAgentThatReachesItsAlertDrinksOnce() {
        try (MockedStatic<CosmicAgentPotionCheckRequestBridge> choices =
                     mockStatic(CosmicAgentPotionCheckRequestBridge.class);
             MockedStatic<PetAutopotProcessor> drinks = mockStatic(PetAutopotProcessor.class)) {
            BotClient client = new BotClient(0, 1);
            Character thane = agent(client);
            short milks = slot(thane, REINDEER_MILK);
            choices.when(() -> CosmicAgentPotionCheckRequestBridge.selectAutopotForCurrentDeficit(thane, true))
                    .thenReturn(new AutopotSelection(milks, REINDEER_MILK));
            drinks.when(() -> PetAutopotProcessor.runAutopotAction(client, milks, REINDEER_MILK))
                    .thenAnswer(invocation -> {
                        thane.updateHp(thane.getHp() + 3 * 5_000);
                        return null;
                    });

            thane.addMPHPAndTriggerAutopot(-(thane.getHp() - 1), 0);

            assertEquals(15_001, thane.getHp());
            drinks.verify(() -> PetAutopotProcessor.runAutopotAction(any(), anyShort(), anyInt()), times(1));
        }
    }

    /** An Agent at full HP with Elixirs and Reindeer Milk, its HP autopot key bound and a 70% alert. */
    private Character agent(Client client) {
        Character chr = Character.getDefault(client);
        chr.setMap(map);
        chr.setMaxHp(MAX_HP);
        chr.localmaxhp = MAX_HP;
        chr.updateHp(MAX_HP);
        chr.getInventory(InventoryType.USE).addItem(new Item(ELIXIR, (short) 0, (short) 1));
        chr.getInventory(InventoryType.USE).addItem(new Item(REINDEER_MILK, (short) 0, (short) 1_300));
        chr.changeKeybinding(AUTOPOT_HP_KEY, new KeyBinding(ITEM_KEY_TYPE, REINDEER_MILK));
        chr.setAutopotHpAlert(HP_ALERT);
        return chr;
    }

    private static short slot(Character chr, int itemId) {
        return chr.getInventory(InventoryType.USE).findById(itemId).getPosition();
    }
}
