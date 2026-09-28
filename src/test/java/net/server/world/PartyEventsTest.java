package net.server.world;

import client.BotClient;
import client.Character;
import client.Client;
import client.Job;
import config.YamlConfig;
import net.packet.InPacket;
import net.server.Server;
import net.server.channel.handlers.PartyOperationHandler;
import net.server.world.PartyEvents.PartyChangeEvent;
import net.server.world.PartyEvents.Snapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.TimerManager;
import server.living.LivingWorld;
import server.living.LivingWorldHooks;
import server.living.roster.CompanionExecutor;
import server.living.roster.CompanionPartySync;
import server.living.roster.CompanionSlots;
import testutil.Packets;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PartyEvents on a real World and Party (docs/BOT_MANAGEMENT_PLAN.md P2a, "Threading and party-event model"): the
 * pre-mutation snapshot (CHANGE_LEADER keeps the previous leader), actor and cause propagation, events published
 * after the change commits, and the living listener handing each event to companion-ops instead of handling it inline.
 */
class PartyEventsTest {
    private static final int ANN = 10;
    private static final int BEN = 20;
    private static final int OLWEN = 900;

    private final List<PartyChangeEvent> events = new ArrayList<>();
    /** The party's member count each time the listener ran. */
    private final List<Integer> sizesSeen = new ArrayList<>();
    private MockedStatic<TimerManager> timers;
    private MockedStatic<Server> servers;
    private World world;
    private Party party;
    private Character ann;
    private Character ben;
    private Character olwen;
    private boolean families;

    @BeforeEach
    void wire() {
        families = YamlConfig.config.server.USE_FAMILY_SYSTEM;
        YamlConfig.config.server.USE_FAMILY_SYSTEM = false; // the World constructor would reach the database
        timers = mockStatic(TimerManager.class);
        servers = mockStatic(Server.class);
        timers.when(TimerManager::getInstance).thenReturn(mock(TimerManager.class));
        servers.when(Server::getInstance).thenReturn(mock(Server.class));
        world = new World(0, 0, "", 1, 1, 1, 1, 1, 1, 1, 1f, 1);
        ann = character(ANN, "Ann", new Client(Client.Type.CHANNEL, -1, "test", null, 0, 1));
        ben = character(BEN, "Ben", new Client(Client.Type.CHANNEL, -1, "test", null, 0, 1));
        olwen = character(OLWEN, "Olwen", new BotClient(0, 1));
        party = world.createParty(new PartyCharacter(ann));
        when(ann.getParty()).thenReturn(party);
        PartyEvents.install(event -> {
            events.add(event);
            sizesSeen.add(party.getMembers().size());
        });
    }

    @AfterEach
    void close() {
        PartyEvents.install(null);
        servers.close();
        timers.close();
        YamlConfig.config.server.USE_FAMILY_SYSTEM = families;
    }

    @Test
    void aJoinIsPublishedAfterItCommitsWithBothSnapshots() {
        world.updateParty(party.getId(), PartyOperation.JOIN, new PartyCharacter(ben));

        PartyChangeEvent event = only();
        assertEquals(party.getId(), event.partyId());
        assertEquals(PartyOperation.JOIN, event.op());
        assertEquals(BEN, event.targetId());
        assertEquals(new Snapshot(List.of(ANN), ANN), event.before());
        assertEquals(new Snapshot(List.of(ANN, BEN), ANN), event.after());
        assertEquals(List.of(2), sizesSeen); // the listener ran with Ben already in
        assertEquals(0, event.actorId());
        assertNull(event.cause());
    }

    @Test
    void changeLeaderKeepsThePreviousLeaderInItsSnapshot() {
        PartyCharacter benMember = new PartyCharacter(ben);
        party.addMember(benMember);

        world.updateParty(party.getId(), PartyOperation.CHANGE_LEADER, benMember);

        PartyChangeEvent event = only();
        assertEquals(ANN, event.before().leaderId());
        assertEquals(BEN, event.after().leaderId());
        assertEquals(BEN, event.targetId());
    }

    @Test
    void aDisbandKeepsItsMembersInTheBeforeSnapshot() {
        party.addMember(new PartyCharacter(olwen));

        world.updateParty(party.getId(), PartyOperation.DISBAND, party.getMemberById(ANN));

        PartyChangeEvent event = only();
        assertEquals(List.of(ANN, OLWEN), event.before().memberIds());
        assertEquals(Snapshot.EMPTY, event.after());
    }

    @Test
    void anExpelAndALeaveShowWhoWent() {
        PartyCharacter olwenMember = new PartyCharacter(olwen);
        PartyCharacter benMember = new PartyCharacter(ben);
        party.addMember(olwenMember);
        party.addMember(benMember);

        world.updateParty(party.getId(), PartyOperation.EXPEL, olwenMember);
        world.updateParty(party.getId(), PartyOperation.LEAVE, benMember);

        assertEquals(2, events.size());
        assertEquals(List.of(ANN, OLWEN, BEN), events.get(0).before().memberIds());
        assertEquals(List.of(ANN, BEN), events.get(0).after().memberIds());
        assertEquals(PartyOperation.LEAVE, events.get(1).op());
        assertEquals(List.of(ANN), events.get(1).after().memberIds());
    }

    @Test
    void aLogOnOrOffSaysWhichWayItWentFromThePartyRecord() {
        party.addMember(new PartyCharacter(ben));
        PartyCharacter leaving = new PartyCharacter(ben);
        leaving.setOnline(false); // as the logout writes it, while Ben may still be in player storage
        PartyCharacter back = new PartyCharacter(ben);
        back.setOnline(true);

        world.updateParty(party.getId(), PartyOperation.LOG_ONOFF, leaving);
        world.updateParty(party.getId(), PartyOperation.LOG_ONOFF, back);

        assertEquals(2, events.size());
        assertFalse(events.get(0).targetOnline());
        assertTrue(events.get(1).targetOnline());
    }

    @Test
    void aSilentUpdateIsNotPublished() {
        world.updateParty(party.getId(), PartyOperation.SILENT_UPDATE, party.getMemberById(ANN));

        assertTrue(events.isEmpty());
    }

    @Test
    void theHandlersActorIsCarriedByTheEvent() {
        party.addMember(new PartyCharacter(ben));
        Client client = mock(Client.class);
        when(client.getPlayer()).thenReturn(ann);
        when(client.getWorldServer()).thenReturn(world);

        InPacket changeLeader = Packets.buildInPacket(out -> {
            out.writeByte(6);
            out.writeInt(BEN);
        });
        new PartyOperationHandler().handlePacket(changeLeader, client);

        PartyChangeEvent event = only();
        assertEquals(PartyOperation.CHANGE_LEADER, event.op());
        assertEquals(ANN, event.actorId());
        assertEquals(0, PartyEvents.currentActor()); // the scope closed with the operation
    }

    @Test
    void theCauseTokenIsCarriedAndRestored() {
        try (PartyEvents.Scope outer = CauseScope.open("companion.swap")) {
            world.updateParty(party.getId(), PartyOperation.JOIN, new PartyCharacter(ben));
            try (PartyEvents.Scope inner = CauseScope.open("companion.merge")) {
                world.updateParty(party.getId(), PartyOperation.JOIN, new PartyCharacter(olwen));
            }
            assertEquals("companion.swap", CauseScope.current());
        }

        assertEquals("companion.swap", events.get(0).cause());
        assertEquals("companion.merge", events.get(1).cause());
        assertNull(CauseScope.current());
    }

    @Test
    void aFailingListenerNeverBreaksThePartyChange() {
        PartyEvents.install(event -> {
            throw new IllegalStateException("listener bug");
        });

        world.updateParty(party.getId(), PartyOperation.JOIN, new PartyCharacter(ben));

        assertEquals(2, party.getMembers().size());
    }

    @Test
    void aListenerThatChangesAPartyInlineIsNotPublishedAgain() {
        PartyEvents.install(event -> {
            events.add(event);
            if (event.op() == PartyOperation.JOIN) {
                world.updateParty(party.getId(), PartyOperation.LOG_ONOFF, party.getMemberById(BEN));
            }
        });

        world.updateParty(party.getId(), PartyOperation.JOIN, new PartyCharacter(ben));

        assertEquals(1, events.size());
    }

    @Test
    void theLivingWorldHandsEachEventToCompanionOpsNotInline() throws Exception {
        CompanionExecutor executor = new CompanionExecutor();
        CompanionSlots slots = mock(CompanionSlots.class);
        LivingWorld living = mock(LivingWorld.class);
        when(living.executor()).thenReturn(executor);
        when(living.slots()).thenReturn(slots);
        CompanionPartySync sync = mock(CompanionPartySync.class);
        when(living.partySync()).thenReturn(sync);
        AtomicReference<String> handledOn = new AtomicReference<>();
        AtomicReference<Integer> sizeWhenHandled = new AtomicReference<>();
        CountDownLatch handled = new CountDownLatch(1);
        doAnswer(inv -> {
            handledOn.set(Thread.currentThread().getName());
            sizeWhenHandled.set(party.getMembers().size());
            handled.countDown();
            return null;
        }).when(slots).onPartyChanged(any());
        PartyEvents.install(LivingWorldHooks::partyChanged);

        try (MockedStatic<LivingWorld> livingWorld = mockStatic(LivingWorld.class)) {
            livingWorld.when(LivingWorld::get).thenReturn(living);
            world.updateParty(party.getId(), PartyOperation.JOIN, new PartyCharacter(ben));
            assertTrue(handled.await(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdown();
        }

        verify(sync, timeout(5_000)).onPartyChanged(any()); // after the seat caches, on the same thread
        assertEquals(CompanionExecutor.THREAD_NAME, handledOn.get());
        assertNotEquals(Thread.currentThread().getName(), handledOn.get());
        assertEquals(2, sizeWhenHandled.get());
    }

    private PartyChangeEvent only() {
        assertEquals(1, events.size(), events.toString());
        return events.get(0);
    }

    private static Character character(int id, String name, Client client) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        when(character.getLevel()).thenReturn(30);
        when(character.getClient()).thenReturn(client);
        when(character.getJob()).thenReturn(Job.BEGINNER);
        return character;
    }
}
