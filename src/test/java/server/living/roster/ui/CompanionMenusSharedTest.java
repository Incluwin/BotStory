package server.living.roster.ui;

import client.Character;
import client.Client;
import client.Job;
import config.LivingWorldConfig;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.resident.LivingTalk;
import server.living.resident.ResidentRuntime;
import server.living.roster.CompanionSlots;
import server.living.roster.CompanionViews;
import server.living.roster.RosterService;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import testutil.NpcTalks;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the two screen tracks share through CompanionMenus (docs/BOT_MANAGEMENT_PLAN.md P3): the menus render from the
 * CompanionViews cache, so a second render reads no database, and an option stamped with the owner's view version
 * re-renders its screen instead of acting once the owner's rows changed after it was shown.
 */
class CompanionMenusSharedTest {
    private static final int ANN = 10;

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingStore store = mock(LivingStore.class);
    private final RosterService roster = mock(RosterService.class);
    private final LivingContent content = mock(LivingContent.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final Character owner = mock(Character.class);
    private final Client client = mock(Client.class);
    private CompanionViews views;

    @BeforeEach
    void wire() {
        when(owner.getId()).thenReturn(ANN);
        when(owner.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(owner);
        when(client.canClickNPC()).thenReturn(true);
        when(world.store()).thenReturn(store);
        when(world.roster()).thenReturn(roster);
        when(world.content()).thenReturn(content);
        when(world.residents()).thenReturn(residents);
        when(world.config()).thenReturn(new LivingWorldConfig());
        when(world.slots()).thenReturn(mock(CompanionSlots.class));
        views = new CompanionViews(world, mapId -> "Ant Tunnel", itemId -> "item " + itemId);
        when(world.views()).thenReturn(views);
    }

    @AfterEach
    void forget() {
        LivingTalk.forget(owner);
    }

    @Test
    void theCrewWindowRendersFromTheCacheWithNoDatabaseReadTheSecondTime() {
        RosterEntry mira = new RosterEntry(ANN, "mira", RosterState.BENCHED, Assignment.TRAIN, 0, 0, 105040300, 0L, 0L, 0L,
                0L, 0L, 0L, 0, null);
        when(store.roster(ANN)).thenReturn(List.of(mira));
        Persona persona = mock(Persona.class);
        when(persona.id()).thenReturn("mira");
        when(persona.name()).thenReturn("Mira");
        when(content.persona("mira")).thenReturn(persona);
        when(roster.describeAssignment(any())).thenReturn("train");
        when(residents.characterIdOf("mira")).thenReturn(902);
        when(store.characterLevelAndJob(902)).thenReturn(new int[] {28, Job.HUNTER.getId()});
        CompanionMenus menus = new CompanionMenus(world);

        menus.crew(owner);
        LivingTalk.forget(owner); // the window closed
        menus.crew(owner);

        assertEquals(List.of("Mira, Lv28 Hunter - train @ Ant Tunnel"), lastTalk().labels().subList(0, 1));

        verify(store, times(1)).roster(ANN);
        verify(store, times(1)).characterLevelAndJob(902);
        verify(store, times(1)).haul(ANN, "mira");
        verify(roster, times(0)).roster(owner);
    }

    private NpcTalks.Talk lastTalk() {
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(client, atLeastOnce()).sendPacket(sent.capture());
        List<NpcTalks.Talk> talks = NpcTalks.talks(sent.getAllValues());
        return talks.get(talks.size() - 1);
    }

    @Test
    void anOptionClickedAfterTheOwnersRowsChangedReRendersInsteadOfActing() {
        CompanionMenus menus = new CompanionMenus(world);
        AtomicInteger acted = new AtomicInteger();
        AtomicInteger rerendered = new AtomicInteger();
        Runnable pick = menus.ifUnchanged(owner, menus.version(owner), acted::incrementAndGet, rerendered::incrementAndGet);

        pick.run();
        assertEquals(1, acted.get());

        views.invalidate(ANN); // a roster write or a party change touched Ann's rows
        pick.run();
        assertEquals(1, acted.get());
        assertEquals(1, rerendered.get());

        menus.ifUnchanged(owner, menus.version(owner), acted::incrementAndGet, rerendered::incrementAndGet).run(); // re-rendered
        assertEquals(2, acted.get());
    }

    @Test
    void aChangeBetweenReadingTheRowsAndBuildingTheOptionsStillReRenders() {
        CompanionMenus menus = new CompanionMenus(world);
        AtomicInteger acted = new AtomicInteger();
        AtomicInteger rerendered = new AtomicInteger();
        long rendered = menus.version(owner); // taken before the screen reads its rows
        views.rows(ANN);
        views.invalidate(ANN); // companion-ops wrote a row while the screen was being built

        menus.ifUnchanged(owner, rendered, acted::incrementAndGet, rerendered::incrementAndGet).run();

        assertEquals(0, acted.get(), "the option was built after the change but shows the rows read before it");
        assertEquals(1, rerendered.get());
    }

    @Test
    void withoutACacheAnOptionAlwaysActs() {
        when(world.views()).thenReturn(null);
        CompanionMenus menus = new CompanionMenus(world);
        AtomicInteger acted = new AtomicInteger();

        menus.ifUnchanged(owner, menus.version(owner), acted::incrementAndGet, () -> { }).run();

        assertEquals(1, acted.get());
    }
}
