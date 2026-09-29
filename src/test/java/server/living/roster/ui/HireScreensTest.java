package server.living.roster.ui;

import client.Character;
import client.Client;
import config.LivingWorldConfig;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content;
import server.living.config.LivingContent;
import server.living.population.Citizen;
import server.living.population.FieldCatalog;
import server.living.population.Population;
import server.living.population.PopulationContent;
import server.living.population.PresenceScheduler;
import server.living.population.VoicePacks;
import server.living.resident.LivingTalk;
import server.living.resident.ResidentRuntime;
import server.living.roster.CompanionBudget;
import server.living.roster.CompanionLeases;
import server.living.roster.CompanionSlots;
import server.living.roster.DirectExecutor;
import server.living.roster.HiredHands;
import server.living.roster.RosterResult;
import server.living.roster.RosterService;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import testutil.NpcTalks;
import testutil.NpcTalks.Talk;
import testutil.Packets;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The shared hire terms window (docs/BOT_MANAGEMENT_PLAN.md P4, flow 9 step 4): the quest-style Accept/Decline window
 * under the Bulletin Board with the citizen's name, level, job, fee and daily wage in its own voice; Accept pays the fee
 * and hires on companion-ops, and the citizen answers in its own words; it refuses politely when the owner cannot pay,
 * their roster is full or their hire cap is reached; Decline answers in character; a click on terms that changed since
 * the window opens them again with the change, and a citizen hired by someone else meanwhile says so.
 */
class HireScreensTest {
    private static final int ANN = 10;
    private static final int BOB = 20;
    private static final int MIRA = 950;
    private static final String HIRE = "hire:950";
    private static final int KERNING = 103000000;
    private static final int HUNTER = 310;
    private static final byte ACCEPT_DECLINE = 0x0C;

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingContent content = mock(LivingContent.class);
    private final LivingStore store = mock(LivingStore.class);
    private final RosterService roster = mock(RosterService.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final CompanionSlots slots = mock(CompanionSlots.class);
    private final CompanionBudget budget = mock(CompanionBudget.class);
    private final LivingWorldConfig config = new LivingWorldConfig();
    private final Population population = mock(Population.class);
    private final Character owner = mock(Character.class);
    private final Client client = mock(Client.class);
    private final List<RosterEntry> rows = new ArrayList<>();
    private final RosterEntry[] hired = {null};
    private final int[] purse = {100_000};
    private HiredHands hands;
    private HireScreens screens;
    private MockedStatic<Population> populations;
    private MockedStatic<AgentRuntimeRegistry> registry;

    @BeforeEach
    void wire() {
        when(owner.getId()).thenReturn(ANN);
        when(owner.getName()).thenReturn("Ann");
        when(owner.getClient()).thenReturn(client);
        when(owner.getMeso()).thenAnswer(inv -> purse[0]);
        doAnswer(inv -> {
            purse[0] += (int) inv.getArgument(0);
            return null;
        }).when(owner).gainMeso(anyInt(), anyBoolean());
        when(client.getPlayer()).thenReturn(owner);
        when(client.canClickNPC()).thenReturn(true);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run(); // the owner's packet thread, at once
            return null;
        }).when(client).runOnIoThread(any());
        when(world.config()).thenReturn(config);
        when(world.content()).thenReturn(content);
        when(world.store()).thenReturn(store);
        when(world.roster()).thenReturn(roster);
        when(world.residents()).thenReturn(residents);
        when(world.slots()).thenReturn(slots);
        when(world.budget()).thenReturn(budget);
        when(world.leases()).thenReturn(new CompanionLeases());
        var ops = DirectExecutor.inline();
        when(world.executor()).thenReturn(ops);
        when(budget.admitInPlace(any(), any(Character.class))).thenReturn(CompanionBudget.Admission.ALLOW);
        when(content.contract(LivingContent.HIRED_HAND_CONTRACT)).thenReturn(new Content.Contract(LivingContent.HIRED_HAND_CONTRACT,
                new Content.OfferWhen(Content.BondStage.NONE, List.of(), null, null), new Content.Price(1_000L, List.of(), null),
                new Content.Terms(0, 0, Content.LootShare.OWN_DROPS, List.of(Content.LeaveRule.DISMISS), 0, 0L, 10), "test"));
        when(store.characterRow(MIRA)).thenReturn(new LivingStore.CharacterRow("Mira", 1, 2, 21001, 31002, 30, HUNTER, KERNING));
        when(store.characterLevelAndJob(MIRA)).thenReturn(new int[] {30, HUNTER});
        when(roster.roster(owner)).thenReturn(rows);
        when(roster.entry(ANN, HIRE)).thenAnswer(inv -> hired[0]);
        when(roster.recruitHire(eq(owner), argThat(persona -> HIRE.equals(persona.id())), anyInt(), any(), any()))
                .thenAnswer(inv -> {
                    ((Runnable) inv.getArgument(4)).run();
                    hired[0] = row(HIRE);
                    return new RosterResult.Travelling(40_000L, KERNING);
                });

        populations = mockStatic(Population.class);
        populations.when(Population::get).thenReturn(population);
        when(population.citizen(MIRA)).thenReturn(new Citizen(MIRA, "Mira", "evening", 0, 0, "victoria", "hunter",
                "relaxed-v1", "default", 1.0d, 0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, ""));
        when(population.presence()).thenReturn(mock(PresenceScheduler.class));
        FieldCatalog fields = mock(FieldCatalog.class);
        when(population.fields()).thenReturn(fields);
        PopulationContent places = mock(PopulationContent.class);
        when(places.field(anyInt())).thenAnswer(inv -> new PopulationContent.Field(inv.getArgument(0), "somewhere", "",
                "victoria", true, 0, 0, 0));
        when(population.content()).thenReturn(places);
        VoicePacks voices = mock(VoicePacks.class);
        when(voices.render(any(), anyString(), anyLong())).thenAnswer(inv -> inv.getArgument(1));
        when(population.voices()).thenReturn(voices);
        registry = mockStatic(AgentRuntimeRegistry.class); // Mira is offline: nothing is loaded for her

        hands = new HiredHands(world);
        when(world.hiredHands()).thenReturn(hands);
        screens = new HireScreens(world);
    }

    @AfterEach
    void close() {
        LivingTalk.forget(owner);
        registry.close();
        populations.close();
    }

    @Test
    void theTermsSpeakForTheCitizenUnderTheBulletinBoard() {
        screens.terms(owner, MIRA, HireScreens.Context.OFFER);

        Talk terms = lastTalk();
        assertEquals(CompanionMenus.BOARD_NPC, terms.npcId(), "a hire has no rendered face: never a borrowed townsperson");
        assertEquals(ACCEPT_DECLINE, terms.msgType(), "the quest-style Accept/Decline window");
        assertEquals(0, terms.speaker());
        assertTrue(terms.text().startsWith("this was fun. want me to stick around?"), terms.text());
        assertTrue(terms.text().contains("#eMira#n, Lv30 Hunter"), terms.text());
        assertTrue(terms.text().contains("30,000 meso#k up front"), terms.text());
        assertTrue(terms.text().contains("21,600 meso#k a day on the bench"), terms.text());

        LivingTalk.forget(owner);
        screens.terms(owner, MIRA, HireScreens.Context.HIRE_LIST);
        assertTrue(lastTalk().text().startsWith("looking for work, actually. want a hand?"), lastTalk().text());
    }

    @Test
    void acceptPaysTheFeeAndHiresOnCompanionOps() {
        screens.terms(owner, MIRA, HireScreens.Context.OFFER);

        answer(1);

        verify(roster).recruitHire(eq(owner), argThat(persona -> HIRE.equals(persona.id())), eq(KERNING), isNull(), any());
        assertEquals(70_000, purse[0], "Lv30 x 1,000 meso");
        verify(residents).whisper(argThat(persona -> HIRE.equals(persona.id())), eq(owner), eq("deal. let's go."));
        verify(owner).dropMessage(eq(5), argThat(line -> line.startsWith("Mira is on the way")));
    }

    @Test
    void anOwnerWhoCannotPayIsToldTheFeeInCharacter() {
        purse[0] = 20_000;
        screens.terms(owner, MIRA, HireScreens.Context.OFFER);

        answer(1);

        assertRefused("you're a bit short. it's 30,000 meso up front.");
        assertEquals(20_000, purse[0]);
    }

    @Test
    void aFullRosterIsRefusedInCharacter() {
        for (int i = 0; i < config.ROSTER_MAX; i++) {
            rows.add(row("persona" + i));
        }
        screens.terms(owner, MIRA, HireScreens.Context.HIRE_LIST);

        answer(1);

        assertRefused("you've got enough people following you around.");
    }

    @Test
    void theHireCapIsRefusedInCharacter() {
        for (int i = 0; i < config.HIRE_MAX_PER_OWNER; i++) {
            rows.add(row("hire:" + (800 + i)));
        }
        screens.terms(owner, MIRA, HireScreens.Context.OFFER);

        answer(1);

        assertRefused("you've got plenty of hired hands already.");
    }

    @Test
    void declineAnswersInCharacterAndHiresNobody() {
        screens.terms(owner, MIRA, HireScreens.Context.OFFER);

        answer(0);

        verify(residents).whisper(argThat(persona -> HIRE.equals(persona.id())), eq(owner), eq("no worries. i'll be around."));
        verify(roster, never()).recruitHire(any(), any(), anyInt(), any(), any());
        assertTrue(NpcTalks.isEnableActions(sent().get(sent().size() - 1)), "the client's actions come back");
        assertEquals(100_000, purse[0]);
    }

    @Test
    void aClickOnTermsThatChangedOpensThemAgainWithTheChange() {
        screens.terms(owner, MIRA, HireScreens.Context.OFFER);
        when(store.characterLevelAndJob(MIRA)).thenReturn(new int[] {31, HUNTER}); // she levelled meanwhile

        answer(1);

        Talk again = lastTalk();
        assertEquals(2, NpcTalks.talks(sent()).size());
        assertTrue(again.text().startsWith(CompanionMenus.THINGS_CHANGED), again.text());
        assertTrue(again.text().contains("31,000 meso#k up front"), again.text());
        verify(roster, never()).recruitHire(any(), any(), anyInt(), any(), any());
        assertEquals(100_000, purse[0]);
    }

    @Test
    void aClickAfterSomeoneElseHiredHerIsAnsweredInCharacter() {
        screens.terms(owner, MIRA, HireScreens.Context.OFFER);
        when(roster.ownerOf(HIRE)).thenReturn(BOB);

        answer(1);

        assertRefused("sorry, i already work for someone.");
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private void assertRefused(String words) {
        verify(residents).whisper(argThat(persona -> HIRE.equals(persona.id())), eq(owner), eq(words));
        verify(roster, never()).recruitHire(any(), any(), anyInt(), any(), any());
        verify(owner, never()).gainMeso(anyInt(), anyBoolean());
    }

    private void answer(int action) {
        assertTrue(LivingTalk.respond(client, ACCEPT_DECLINE, (byte) action, Packets.buildInPacket(out -> { })));
    }

    private List<Packet> sent() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(client, atLeastOnce()).sendPacket(captor.capture());
        return captor.getAllValues();
    }

    private Talk lastTalk() {
        List<Talk> talks = NpcTalks.talks(sent());
        return talks.get(talks.size() - 1);
    }

    private static RosterEntry row(String personaId) {
        return new RosterEntry(ANN, personaId, RosterState.BENCHED, Assignment.TRAIN, 0, 0, KERNING, 0L, 0L, 0L, 0L, 0L, 0L, 0,
                null);
    }
}
