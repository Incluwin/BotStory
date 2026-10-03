package client;

import config.YamlConfig;
import net.packet.InPacket;
import net.packet.Packet;
import net.server.Server;
import net.server.channel.handlers.PartyOperationHandler;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.PartyEvents;
import net.server.world.PartyOperation;
import net.server.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.TimerManager;
import server.maps.MapleMap;
import testutil.Packets;
import tools.PacketCreator;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Leadership never passes to a bot while a human is in the party (docs/BOT_MANAGEMENT_PLAN.md P1): the successor
 * when the leader logs out ({@code Client.removePartyPlayer}) or leaves ({@code Party.assignNewLeader}), and party
 * operation 6 (change leader).
 */
class LeaderSuccessionTest {
    private static final int LEAVER = 1;
    private static final boolean LOGS_OUT = true;
    private static final boolean LEAVES = false;

    private final MapleMap map = mock(MapleMap.class);

    // ---- the leader logs out (stays a member) --------------------------------------------

    @Test
    void aHumanOnTheMapLeadsEvenWhenABotOutlevelsThem() {
        PartyCharacter bot = member(2, 120, true, true);
        PartyCharacter ben = member(3, 30, false, true);

        assertSame(ben, party(leaver(false), bot, ben).successor(LEAVER, map, LOGS_OUT));
    }

    @Test
    void aHumanElsewhereLeadsBeforeABotOnTheMap() {
        PartyCharacter bot = member(2, 120, true, true);
        PartyCharacter ben = member(3, 30, false, false);

        assertSame(ben, party(leaver(false), bot, ben).successor(LEAVER, map, LOGS_OUT));
    }

    @Test
    void theHighestLevelHumanOnTheMapLeads() {
        PartyCharacter ben = member(3, 30, false, true);
        PartyCharacter cleo = member(4, 45, false, true);
        PartyCharacter dan = member(5, 90, false, false);

        assertSame(cleo, party(leaver(false), ben, cleo, dan).successor(LEAVER, map, LOGS_OUT));
    }

    @Test
    void aHumanLeaderWithOnlyBotsOnlineStaysLeader() {
        PartyCharacter bot = member(2, 120, true, true);
        PartyCharacter offlineHuman = offline(3, 50, false);

        assertNull(party(leaver(false), bot, offlineHuman).successor(LEAVER, map, LOGS_OUT));
    }

    @Test
    void aBotLeaderHandsOverToAHumanFirst() {
        PartyCharacter bot = member(2, 120, true, true);
        PartyCharacter ben = member(3, 30, false, true);

        assertSame(ben, party(leaver(true), bot, ben).successor(LEAVER, map, LOGS_OUT));
    }

    @Test
    void aBotLeaderWithOnlyAnOfflineHumanLeftHandsTheLeadToThatHuman() {
        PartyCharacter bot = member(2, 120, true, true);
        PartyCharacter offlineHuman = offline(3, 50, false);

        assertSame(offlineHuman, party(leaver(true), bot, offlineHuman).successor(LEAVER, map, LOGS_OUT));
    }

    @Test
    void aBotOnlyPartyKeepsTheStockRule() {
        PartyCharacter low = member(2, 20, true, true);
        PartyCharacter high = member(3, 60, true, true);
        PartyCharacter away = member(4, 90, true, false);

        assertSame(high, party(leaver(true), low, high, away).successor(LEAVER, map, LOGS_OUT));
    }

    // ---- the leader leaves the party (deleted, or level 10 out of a starter party) -------

    @Test
    void aLeaderLeavingHandsTheLeadToAnOfflineHumanBeforeAnyBot() {
        PartyCharacter bot = member(2, 120, true, true);
        PartyCharacter offlineHuman = offline(3, 20, false);

        assertSame(offlineHuman, party(leaver(false), bot, offlineHuman).successor(LEAVER, null, LEAVES));
    }

    @Test
    void aLeaderLeavingOnlyBotsHandsTheLeadToTheHighestOne() {
        PartyCharacter low = member(2, 20, true, true);
        PartyCharacter offlineBot = offline(3, 90, true);

        assertSame(offlineBot, party(leaver(false), low, offlineBot).successor(LEAVER, null, LEAVES));
    }

    @Test
    void assignNewLeaderUsesTheHumanFirstRule() {
        PartyCharacter bot = member(2, 120, true, true);
        PartyCharacter offlineHuman = offline(3, 20, false);
        Party party = party(leaver(false), bot, offlineHuman);
        Client leaving = mock(Client.class);
        World world = mock(World.class);
        when(leaving.getWorldServer()).thenReturn(world);

        party.assignNewLeader(leaving);

        verify(world).updateParty(party.getId(), PartyOperation.CHANGE_LEADER, offlineHuman);
    }

    // ---- the whole logout, against a real party and world --------------------------------

    @Test
    void theLeadersLogoutReallyHandsThePartyToTheHuman() {
        boolean families = YamlConfig.config.server.USE_FAMILY_SYSTEM;
        YamlConfig.config.server.USE_FAMILY_SYSTEM = false;
        try (MockedStatic<TimerManager> timers = mockStatic(TimerManager.class);
             MockedStatic<Server> servers = mockStatic(Server.class)) {
            timers.when(TimerManager::getInstance).thenReturn(mock(TimerManager.class));
            servers.when(Server::getInstance).thenReturn(mock(Server.class));
            World world = new World(0, 0, "", 1, 1, 1, 1, 1, 1, 1, 1f, 1);
            Client annClient = new Client(Client.Type.CHANNEL, -1, "test", null, 0, 1);
            Character ann = player(LEAVER, "Ann", 30, annClient);
            Character bot = player(2, "Olwen", 120, new BotClient(0, 1));
            Character ben = player(3, "Ben", 20, new Client(Client.Type.CHANNEL, -1, "test", null, 0, 1));
            Party party = world.createParty(new PartyCharacter(ann));
            party.addMember(new PartyCharacter(bot));
            party.addMember(new PartyCharacter(ben));
            when(ann.getParty()).thenReturn(party);
            when(map.getCharacterById(2)).thenReturn(bot);
            when(map.getCharacterById(3)).thenReturn(ben);
            annClient.setPlayer(ann);

            annClient.removePartyPlayer(world);

            assertEquals(3, party.getLeaderId());
            assertEquals(3, party.getLeader().getId());
        } finally {
            YamlConfig.config.server.USE_FAMILY_SYSTEM = families;
        }
    }

    @Test
    void theLogoutIsPublishedWithTheLeaverAsActorAndThePreviousLeader() {
        boolean families = YamlConfig.config.server.USE_FAMILY_SYSTEM;
        YamlConfig.config.server.USE_FAMILY_SYSTEM = false;
        List<PartyEvents.PartyChangeEvent> events = new ArrayList<>();
        try (MockedStatic<TimerManager> timers = mockStatic(TimerManager.class);
             MockedStatic<Server> servers = mockStatic(Server.class)) {
            timers.when(TimerManager::getInstance).thenReturn(mock(TimerManager.class));
            servers.when(Server::getInstance).thenReturn(mock(Server.class));
            World world = new World(0, 0, "", 1, 1, 1, 1, 1, 1, 1, 1f, 1);
            Client annClient = new Client(Client.Type.CHANNEL, -1, "test", null, 0, 1);
            Character ann = player(LEAVER, "Ann", 30, annClient);
            Character ben = player(3, "Ben", 20, new Client(Client.Type.CHANNEL, -1, "test", null, 0, 1));
            Party party = world.createParty(new PartyCharacter(ann));
            party.addMember(new PartyCharacter(ben));
            when(ann.getParty()).thenReturn(party);
            when(map.getCharacterById(3)).thenReturn(ben);
            annClient.setPlayer(ann);
            PartyEvents.install(events::add);

            annClient.removePartyPlayer(world);
        } finally {
            PartyEvents.install(null);
            YamlConfig.config.server.USE_FAMILY_SYSTEM = families;
        }

        assertEquals(List.of(PartyOperation.LOG_ONOFF, PartyOperation.CHANGE_LEADER),
                events.stream().map(PartyEvents.PartyChangeEvent::op).toList());
        assertEquals(LEAVER, events.get(0).actorId());
        assertEquals(LEAVER, events.get(1).actorId());
        assertEquals(LEAVER, events.get(1).before().leaderId());
        assertEquals(3, events.get(1).after().leaderId());
        assertEquals(0, PartyEvents.currentActor());
    }

    /**
     * Incident 2026-10-03: the owner's disconnect ran succession while companion-ops parked his companion off the
     * same map. MapleMap.removePlayer holds the map's character write lock while it asks the party about the
     * companion, so succession must not hold the party lock while it looks at that map.
     */
    @Test
    @Timeout(30)
    void aLeadersRealLogoutDoesNotDeadlockWithACompanionLeavingTheSameMap() throws Exception {
        boolean families = YamlConfig.config.server.USE_FAMILY_SYSTEM;
        YamlConfig.config.server.USE_FAMILY_SYSTEM = false;
        try (MockedStatic<TimerManager> timers = mockStatic(TimerManager.class);
             MockedStatic<Server> servers = mockStatic(Server.class)) {
            timers.when(TimerManager::getInstance).thenReturn(mock(TimerManager.class));
            servers.when(Server::getInstance).thenReturn(mock(Server.class));
            World world = new World(0, 0, "", 1, 1, 1, 1, 1, 1, 1, 1f, 1);
            MapleMap henesys = new MapleMap(100000000, 0, 1, 999999999, 1f);
            CountDownLatch companionHoldsMapWriteLock = new CountDownLatch(1);
            CountDownLatch ownerLooksAtMap = new CountDownLatch(1);
            Client annClient = new Client(Client.Type.CHANNEL, -1, "test", null, 0, 1);
            Character ann = player(LEAVER, "Ann", 30, annClient);
            when(ann.getMap()).thenReturn(henesys);
            Party party = world.createParty(new PartyCharacter(ann));
            party.addMember(onlineCompanion(2, ownerLooksAtMap));
            when(ann.getParty()).thenReturn(party);
            annClient.setPlayer(ann);
            Character companion = companionLeaving(2, henesys, party, companionHoldsMapWriteLock, ownerLooksAtMap);

            // neither worker needs the thread-local Server or TimerManager mocks: LOG_ONOFF only updates the party
            // and the world's (empty) player storage, and the companion's removal touches only the map
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch finished = new CountDownLatch(2);
            Thread companionOps = daemon("companion-ops", () -> henesys.removePlayer(companion), failure, finished);
            Thread ownerDisconnect = daemon("cosmic-database", () -> annClient.removePartyPlayer(world), failure,
                    finished);

            companionOps.start();
            assertTrue(companionHoldsMapWriteLock.await(5, TimeUnit.SECONDS), "the companion never left the map");
            ownerDisconnect.start();

            if (!finished.await(5, TimeUnit.SECONDS)) {
                fail("the leader's logout deadlocked with MapleMap.removePlayer: " + deadlockedThreadNames());
            }
            assertNull(failure.get(), () -> "worker failed: " + failure.get());
            assertEquals(LEAVER, party.getLeaderId(), "a human leader with only companions online keeps the lead");
        } finally {
            YamlConfig.config.server.USE_FAMILY_SYSTEM = families;
        }
    }

    // ---- op 6, change leader -------------------------------------------------------------

    @Test
    void changeLeaderWithoutAPartyIsIgnored() {
        Client client = leaderClient(null);

        changeLeader(client, 2);

        verify(client.getWorldServer(), never()).updateParty(anyInt(), any(), any());
    }

    @Test
    void onlyTheLeaderMayHandOverLeadership() {
        Party party = mockParty(3, member(3, 30, false, true));
        Client client = leaderClient(party);

        changeLeader(client, 3);

        verify(client.getWorldServer(), never()).updateParty(anyInt(), any(), any());
    }

    @Test
    void aBotIsNeverMadeLeaderAndSaysSo() {
        PartyCharacter bot = member(2, 120, true, true);
        when(bot.getPlayer().getName()).thenReturn("Olwen");
        when(bot.getPlayer().getClient().getChannel()).thenReturn(1);
        Party party = mockParty(LEAVER, bot);
        Client client = leaderClient(party);

        changeLeader(client, 2);

        verify(client.getWorldServer(), never()).updateParty(anyInt(), any(), any());
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(client).sendPacket(sent.capture());
        assertArrayEquals(PacketCreator.getWhisperReceive("Olwen", 0, false, "nah, you lead").getBytes(),
                sent.getValue().getBytes());
    }

    @Test
    void anOfflineMemberIsNeverMadeLeader() {
        // an offline member's character is gone, so it could be a bot the handler can no longer recognise
        PartyCharacter offlineBot = offline(2, 120, true);
        Party party = mockParty(LEAVER, offlineBot);
        Client client = leaderClient(party);

        changeLeader(client, 2);

        verify(client.getWorldServer(), never()).updateParty(anyInt(), any(), any());
        verify(client, never()).sendPacket(any());
    }

    @Test
    void aHumanMayBeMadeLeader() {
        PartyCharacter ben = member(3, 30, false, true);
        Party party = mockParty(LEAVER, ben);
        when(party.getId()).thenReturn(77);
        Client client = leaderClient(party);

        changeLeader(client, 3);

        verify(client.getWorldServer()).updateParty(77, PartyOperation.CHANGE_LEADER, ben);
    }

    private static Client leaderClient(Party party) {
        Character leader = mock(Character.class);
        when(leader.getId()).thenReturn(LEAVER);
        when(leader.getParty()).thenReturn(party);
        Client client = mock(Client.class);
        when(client.getPlayer()).thenReturn(leader);
        when(client.getWorldServer()).thenReturn(mock(World.class));
        return client;
    }

    private static void changeLeader(Client client, int newLeaderId) {
        InPacket packet = Packets.buildInPacket(out -> {
            out.writeByte(6);
            out.writeInt(newLeaderId);
        });
        new PartyOperationHandler().handlePacket(packet, client);
    }

    // ---- helpers -------------------------------------------------------------------------

    private PartyCharacter member(int id, int level, boolean bot, boolean onMap) {
        Character player = mock(Character.class);
        when(player.getClient()).thenReturn(bot ? mock(BotClient.class) : mock(Client.class));
        PartyCharacter member = mock(PartyCharacter.class);
        when(member.getId()).thenReturn(id);
        when(member.getLevel()).thenReturn(level);
        when(member.isBot()).thenReturn(bot);
        when(member.isOnline()).thenReturn(true);
        when(member.getPlayer()).thenReturn(player);
        when(map.getCharacterById(id)).thenReturn(onMap ? player : null);
        return member;
    }

    /** An offline member: its character is gone (PartyCharacter.setOnline(false)), its bot flag stays. */
    private static PartyCharacter offline(int id, int level, boolean bot) {
        PartyCharacter member = mock(PartyCharacter.class);
        when(member.getId()).thenReturn(id);
        when(member.getLevel()).thenReturn(level);
        when(member.isBot()).thenReturn(bot);
        return member;
    }

    /** The leaver's own entry is already offline when it logs out (LOG_ONOFF runs first). */
    private static PartyCharacter leaver(boolean bot) {
        return offline(LEAVER, 50, bot);
    }

    private static Party party(PartyCharacter leader, PartyCharacter... others) {
        Party party = new Party(5, leader);
        party.addMember(leader);
        for (PartyCharacter other : others) {
            party.addMember(other);
        }
        return party;
    }

    private static Party mockParty(int leaderId, PartyCharacter member) {
        Party party = mock(Party.class);
        when(party.getMembers()).thenReturn(List.of(member));
        when(party.getLeaderId()).thenReturn(leaderId);
        when(party.getMemberById(member.getId())).thenReturn(member);
        return party;
    }

    /** An online companion's party entry; looking at whether it is online is the owner's last step before the map. */
    private static PartyCharacter onlineCompanion(int id, CountDownLatch ownerLooksAtMap) {
        PartyCharacter member = mock(PartyCharacter.class);
        when(member.getId()).thenReturn(id);
        when(member.getLevel()).thenReturn(120);
        when(member.isBot()).thenReturn(true);
        when(member.isOnline()).thenAnswer(inv -> {
            ownerLooksAtMap.countDown();
            return true;
        });
        return member;
    }

    /**
     * A companion leaving {@code map}: inside removePlayer's write-locked section it waits for the owner to look at
     * the map, then asks the party about itself (party.getMemberById(chr.getId())).
     */
    private static Character companionLeaving(int id, MapleMap map, Party party, CountDownLatch holdsMapWriteLock,
                                              CountDownLatch ownerLooksAtMap) throws ReflectiveOperationException {
        Field field = MapleMap.class.getDeclaredField("chrWLock");
        field.setAccessible(true);
        ReentrantReadWriteLock.WriteLock mapWriteLock = (ReentrantReadWriteLock.WriteLock) field.get(map);
        Character companion = mock(Character.class);
        when(companion.getClient()).thenReturn(mock(Client.class));
        when(companion.getParty()).thenReturn(party);
        AtomicBoolean armed = new AtomicBoolean();
        when(companion.getId()).thenAnswer(inv -> {
            if (mapWriteLock.isHeldByCurrentThread() && armed.compareAndSet(true, false)) {
                holdsMapWriteLock.countDown();
                ownerLooksAtMap.await(10, TimeUnit.SECONDS);
            }
            return id;
        });
        armed.set(true);
        return companion;
    }

    private static Thread daemon(String name, Runnable body, AtomicReference<Throwable> failure,
                                 CountDownLatch finished) {
        Thread thread = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            } finally {
                finished.countDown();
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    private static String deadlockedThreadNames() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long[] ids = threads.findDeadlockedThreads();
        return ids == null ? "no JVM-detected cycle (timed out)"
                : Arrays.toString(Arrays.stream(threads.getThreadInfo(ids)).map(ThreadInfo::getThreadName).toArray());
    }

    private Character player(int id, String name, int level, Client client) {
        Character player = mock(Character.class);
        when(player.getId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.getLevel()).thenReturn(level);
        when(player.getClient()).thenReturn(client);
        when(player.getJob()).thenReturn(Job.BEGINNER);
        when(player.getMap()).thenReturn(map);
        return player;
    }
}
