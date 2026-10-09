package client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import provider.DataProviderFactory;
import provider.wz.WZFiles;
import server.StatEffect;
import server.TimerManager;
import server.maps.MapleMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;

/**
 * Review wf_aa231c68-380 of fix/bot-drain-autopot: PetAutopotProcessor checks that the character is alive, takes the
 * potion, then heals. A lethal hit on another thread (mob damage, Agent combat damage, a map drain tick) can land in
 * between. A heal from HP 0 revived the character with its death still pending, so the deferred stat update ran
 * playerDead() on a living character. The autopot heal now refuses a dead character inside the stat lock that every
 * HP write takes. A living character's drink and every revive keep the stock behaviour.
 */
class CharacterAutopotHealRaceTest {
    private static final int WHITE_POTION = 2_000_002;
    private static final int WHITE_POTION_HP = 300;
    private static final int MAX_HP = 1_000;
    private static final int HP_BEFORE_THE_DRINK = 400;

    private MockedStatic<TimerManager> timers;
    private StatEffect whitePotion;

    @BeforeEach
    void setUp() {
        timers = mockStatic(TimerManager.class);
        timers.when(TimerManager::getInstance).thenReturn(mock(TimerManager.class));
        whitePotion = StatEffect.loadItemEffectFromData(DataProviderFactory.getDataProvider(WZFiles.ITEM)
                .getData("Consume/0200.img").getChildByPath("0" + WHITE_POTION + "/spec"), WHITE_POTION);
    }

    @AfterEach
    void tearDown() {
        timers.close();
    }

    @Test
    void aLethalHitAfterTheAliveCheckLeavesTheAgentDead() throws Exception {
        Character agent = onMap(new BotClient(0, 1), HP_BEFORE_THE_DRINK);
        assertTrue(agent.isAlive(), "the autopot's alive check passes");

        lethalHitOnAnotherThread(agent);
        boolean healed = whitePotion.applyToIfAlive(agent);

        assertFalse(healed);
        assertEquals(0, agent.getHp(), "the heal must not revive a character whose death is pending");
    }

    @Test
    void aLethalHitInsideTheHealBeforeItsStatWriteLeavesTheAgentDead() {
        Character agent = spy(onMap(new BotClient(0, 1), HP_BEFORE_THE_DRINK));
        AtomicBoolean hit = new AtomicBoolean();
        // The heal reads the zombify disease before it takes the stat lock: the hit lands there, so an alive check
        // made anywhere outside that lock has already passed.
        doAnswer(read -> {
            if (hit.compareAndSet(false, true)) {
                lethalHitOnAnotherThread(agent);
            }
            return read.callRealMethod();
        }).when(agent).hasDisease(Disease.ZOMBIFY);

        boolean healed = whitePotion.applyToIfAlive(agent);

        assertTrue(hit.get(), "the hit must land inside the heal");
        assertFalse(healed);
        assertEquals(0, agent.getHp(), "the heal must not revive a character whose death is pending");
    }

    @Test
    void aLivingCharacterDrinksLikeStock() {
        Character human = onMap(mock(Client.class), HP_BEFORE_THE_DRINK);

        boolean healed = whitePotion.applyToIfAlive(human);

        assertTrue(healed);
        assertEquals(HP_BEFORE_THE_DRINK + WHITE_POTION_HP, human.getHp());
    }

    @Test
    void theHealPathThatResurrectionUsesStillRevivesADeadCharacter() {
        Character dead = onMap(mock(Client.class), 0);

        boolean applied = dead.applyHpMpChange(0, MAX_HP, 0);

        assertTrue(applied);
        assertEquals(MAX_HP, dead.getHp());
    }

    @Test
    void anItemEffectOutsideTheAutopotKeepsTheStockHealOnADeadCharacter() {
        Character dead = onMap(mock(Client.class), 0);

        whitePotion.applyTo(dead);

        assertEquals(WHITE_POTION_HP, dead.getHp(), "event buffs and scripts keep the stock item heal");
    }

    /**
     * The processor itself cannot run in a unit test: its effect lookup initializes ItemInformationProvider, which
     * needs the database. So this pins that its heal goes through the guarded call.
     */
    @Test
    void theAutopotHealsOnlyThroughTheAliveGuard() throws Exception {
        String processor = Files.readString(Path.of("src/main/java/client/processor/action/PetAutopotProcessor.java"));

        assertTrue(processor.contains("stat.applyToIfAlive(chr)"));
        assertFalse(processor.contains(".applyTo("));
    }

    /**
     * The hit takes the stat write lock on its own thread, the way ServerMobDamageService, the Agent combat damage
     * runtime and the World drain timer do. The timeout turns a deadlock into a failure instead of a hung suite.
     */
    private static void lethalHitOnAnotherThread(Character chr) throws Exception {
        FutureTask<Void> hit = new FutureTask<>(() -> chr.addMPHP(-chr.getHp(), 0), null);
        new Thread(hit, "lethal-hit").start();
        hit.get(5, TimeUnit.SECONDS);
    }

    /**
     * A character on a map. The max HP is set directly: a max HP update recalculates equip stats through
     * ItemInformationProvider, which needs the database.
     */
    private static Character onMap(Client client, int hp) {
        Character chr = Character.getDefault(client);
        chr.setMap(mock(MapleMap.class));
        chr.setMaxHp(MAX_HP);
        chr.localmaxhp = MAX_HP;
        chr.updateHp(hp);
        return chr;
    }
}
