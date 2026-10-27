package server.living.roster;

import client.BuddyList;
import client.Character;
import client.Client;
import client.Job;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.integration.AgentPresence;
import server.living.bond.BondService;
import server.living.bond.ContractService;
import server.living.config.LivingContent;
import server.living.population.Citizen;
import server.living.population.CitizenStore;
import server.living.population.CitizenStore.Acquaintance;
import server.living.population.FieldCatalog;
import server.living.population.PersonMemories;
import server.living.population.Population;
import server.living.population.PopulationContent;
import server.living.population.PopulationContent.Field;
import server.living.population.PopulationContent.Voice;
import server.living.population.PresenceScheduler;
import server.living.population.SocialFabric;
import server.living.population.VoicePacks;
import server.living.resident.LivingTalk;
import server.living.resident.ResidentDialogue;
import server.living.roster.CompanionDirectory.Relation;
import server.living.roster.ui.CompanionMenus;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.CharacterRow;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import testutil.NpcTalks;
import testutil.NpcTalks.Talk;
import testutil.Packets;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.HENESYS;
import static server.living.roster.LifecycleHarness.KERNING;

/**
 * Party-time hire offers (docs/BOT_MANAGEMENT_PLAN.md P4, flow 9 step 4, "Citizen guest"): a citizen guest counts
 * HIRE_OFFER_PARTY_MINUTES with its host (both online, one party), then whispers its offer once in its own voice and
 * holds it open for 10 minutes; double-clicking it then opens the terms window instead of char info, and Accept adopts
 * it where it stands; Decline or expiry keeps it from asking that owner again for a while; there is one offer at a time
 * per owner and none while their roster or hire cap is full or they are in a run, and a reserved guest may keep to
 * itself.
 */
class HireOfferTest {
    private static final int MIRA = 950;
    private static final int TESS = 951;
    private static final int BOB = 20;
    private static final String HIRE = "hire:950";
    private static final int HUNTER = 310;
    private static final byte ACCEPT_DECLINE = 0x0C;
    private static final byte MENU = 4;
    private static final String OFFER = "i like running with you. want me to stick around? double-click me";
    /** Mira's open offer on the hire list (loaded on a map with no name: "around"). */
    private static final String OFFER_ROW = "Mira, Lv30 Hunter - around (offered to stay)";

    private final LifecycleHarness game = new LifecycleHarness();
    private final Population population = mock(Population.class);
    private final PresenceScheduler presence = mock(PresenceScheduler.class);
    private final VoicePacks voices = mock(VoicePacks.class);
    private final SocialFabric social = mock(SocialFabric.class);
    private final Map<Integer, Citizen> citizens = new HashMap<>();
    private final long[] now = {1_000_000L};
    private final int[] purse = {100_000};
    /** living_acquaintance.party_minutes of Mira and Ann (the population's own count, on a shared map). */
    private final int[] partyMinutesTogether = {0};
    private final HiredHands hands = new HiredHands(game.world);
    private final HireOffers offers = new HireOffers(game.world, () -> now[0]);
    private final Character mira = game.bot(MIRA, "Mira");
    private final Client client = game.ann.getClient();
    private CompanionDirectory directory;
    private MockedStatic<Population> populations;

    @BeforeEach
    void wire() {
        populations = mockStatic(Population.class);
        populations.when(Population::get).thenReturn(population);
        when(population.citizen(anyInt())).thenAnswer(inv -> citizens.get((int) inv.getArgument(0)));
        when(population.isCitizen(anyInt())).thenAnswer(inv -> citizens.containsKey((int) inv.getArgument(0)));
        when(population.citizenByName(anyString())).thenAnswer(inv -> citizens.values().stream()
                .filter(citizen -> citizen.name().equalsIgnoreCase(inv.getArgument(0))).findFirst().orElse(null));
        doAnswer(inv -> citizens.put(((Citizen) inv.getArgument(0)).characterId(), inv.getArgument(0)))
                .when(population).save(any());
        when(population.setHired(anyInt(), anyBoolean())).thenAnswer(inv -> citizens.computeIfPresent(inv.getArgument(0),
                (id, citizen) -> inv.<Boolean>getArgument(1) ? citizen.withFlag(Citizen.FLAG_HIRED)
                        : citizen.withoutFlag(Citizen.FLAG_HIRED)));
        when(population.presence()).thenReturn(presence);
        when(presence.handOver(anyInt(), anyBoolean(), anyString())).thenAnswer(inv -> {
            citizens.computeIfPresent(inv.getArgument(0), (key, citizen) -> citizen.withFlag(Citizen.FLAG_HIRED));
            return game.loaded.contains((int) inv.getArgument(0));
        });
        FieldCatalog fields = mock(FieldCatalog.class);
        when(fields.homeTown(any(), any())).thenReturn(HENESYS);
        when(population.fields()).thenReturn(fields);
        PopulationContent places = mock(PopulationContent.class);
        when(places.field(anyInt())).thenAnswer(inv -> new Field(inv.getArgument(0), "somewhere", "", "victoria", false, 10, 20, 12));
        when(population.content()).thenReturn(places);
        when(voices.render(any(), anyString(), anyLong())).thenAnswer(inv -> inv.getArgument(1));
        when(voices.voiceOf(any())).thenReturn(voice(0.15d)); // casual-typos: easygoing
        when(population.voices()).thenReturn(voices);
        when(social.sociability(any())).thenReturn(55);
        when(population.social()).thenReturn(social);
        CitizenStore acquaintances = mock(CitizenStore.class);
        when(acquaintances.acquaintance(anyInt(), anyInt())).thenAnswer(inv -> new Acquaintance(inv.getArgument(0),
                inv.getArgument(1), partyMinutesTogether[0], 0, false, false));
        when(population.store()).thenReturn(acquaintances);

        citizen(mira, "Mira");
        when(game.store.store.characterRow(MIRA)).thenReturn(new CharacterRow("Mira", 1, 2, 21001, 31002, 30, HUNTER, KERNING));
        when(game.content.contract(LivingContent.HIRED_HAND_CONTRACT)).thenReturn(HiredHandsTest.hiredHand());
        when(game.content.persona(HIRE)).thenAnswer(inv -> hands.persona(HIRE));
        when(game.world.hiredHands()).thenReturn(hands);
        when(game.world.hireOffers()).thenReturn(offers);
        when(game.world.bonds()).thenReturn(mock(BondService.class));
        when(game.world.contracts()).thenReturn(new ContractService(game.world));
        when(game.residents.ensureCharacter(argThat(persona -> persona != null && HIRE.equals(persona.id())))).thenReturn(MIRA);
        when(game.residents.characterIdOf(HIRE)).thenReturn(MIRA);
        when(game.residents.personaOfCharacterId(MIRA)).thenAnswer(inv -> game.roster().cachedOwnerOf(HIRE) != 0
                ? hands.persona(HIRE) : null);
        game.personaOfBot.put(MIRA, HIRE);
        when(game.ann.getMeso()).thenAnswer(inv -> purse[0]);
        doAnswer(inv -> {
            purse[0] += (int) inv.getArgument(0);
            return null;
        }).when(game.ann).gainMeso(anyInt(), anyBoolean());
        when(game.ann.tryPayMeso(anyInt(), anyBoolean())).thenAnswer(inv -> {
            int fee = inv.getArgument(0);
            if (purse[0] < fee) {
                return false;
            }
            purse[0] -= fee;
            return true;
        });
        game.roster().installHiredHandRelease(hands::onReleased);

        // the owner's client: a window may open for their own click, and an answer runs at once on their thread
        when(client.getPlayer()).thenReturn(game.ann);
        when(client.canClickNPC()).thenReturn(true);
        when(client.getQM()).thenReturn(null);
        when(client.getCM()).thenReturn(null);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(client).runOnIoThread(any());
        // "who is this to me?" is the real directory's answer
        AgentPresence.install(chr -> citizens.containsKey(chr.getId()));
        directory = new CompanionDirectory(game.world);
        when(game.world.directory().resolve(any(), any())).thenAnswer(inv -> directory.resolve(inv.getArgument(0),
                inv.getArgument(1)));
    }

    @AfterEach
    void close() {
        LivingTalk.forget(game.ann);
        AgentPresence.install(null);
        populations.close();
        game.close();
    }

    // ---- the timer ------------------------------------------------------------------------------

    @Test
    void aGuestOffersToStayOnceAfterItsPartyMinutes() {
        guestOfAnn(mira);

        minutes(game.config.HIRE_OFFER_PARTY_MINUTES - 1);
        verify(game.residents, never()).whisper(any(), any(), anyString());
        assertTrue(offers.pendingOffers(game.ann).isEmpty());

        minutes(1);
        verify(game.residents).whisper(argThat(persona -> HIRE.equals(persona.id())), eq(game.ann), eq(OFFER));
        assertEquals(List.of(new HireOffers.Offer(ANN, MIRA, "Mira", now[0], now[0] + HireOffers.OPEN_MS)),
                offers.pendingOffers(game.ann));
        assertNotNull(offers.pending(ANN, MIRA));
        assertEquals(List.of(ANN + " " + HIRE + " hire.offer after 10 party minutes"), game.store.events(HiredHands.EVENT_OFFER));

        minutes(5);
        verify(game.residents, times(1)).whisper(any(), any(), anyString()); // one whisper, never again while it is open
    }

    @Test
    void partyTimeCountsOnlyWhileBothAreOnlineInOneParty() {
        guestOfAnn(mira);
        minutes(5);
        game.annOnline = false;
        minutes(20);
        game.annOnline = true;
        minutes(4);
        assertTrue(offers.pendingOffers(game.ann).isEmpty(), "9 minutes together, not 29");

        minutes(1);
        assertEquals(1, offers.pendingOffers(game.ann).size());
    }

    @Test
    void partyTimeCountsOnlySideBySide() {
        guestOfAnn(mira);
        minutes(5);
        game.moveAnn(KERNING, 1000, 200); // Ann went to town; Mira still hunts where she was
        minutes(20);

        assertTrue(offers.pendingOffers(game.ann).isEmpty(), "5 minutes side by side, not 25");
        verify(game.residents, never()).whisper(any(), any(), anyString());
    }

    @Test
    void aGuestThatLeftThePartyStartsOverAsANewGuest() {
        guestOfAnn(mira);
        minutes(9);
        game.members.remove((Integer) MIRA);
        game.slots.release(CompanionSlots.characterKey(MIRA), CompanionSlots.Kind.GUEST);
        minutes(1);

        guestOfAnn(mira);
        minutes(9);
        assertTrue(offers.pendingOffers(game.ann).isEmpty());
        minutes(1);
        assertEquals(1, offers.pendingOffers(game.ann).size());
    }

    // ---- the pending offer ----------------------------------------------------------------------

    @Test
    void anOpenOfferIsAPendingRelationForItsOwnerAlone() {
        guestOfAnn(mira);
        assertEquals(new Relation.Citizen(MIRA), directory.resolve(game.ann, mira));

        offerMade();

        assertEquals(new Relation.HireOfferPending(MIRA), directory.resolve(game.ann, mira));
        assertEquals(new Relation.HireOfferPending(MIRA), directory.resolveName(game.ann, "Mira"));
        Character bob = mock(Character.class);
        when(bob.getId()).thenReturn(BOB);
        assertEquals(new Relation.Citizen(MIRA), directory.resolve(bob, mira), "nobody else's offer");
    }

    @Test
    void doubleClickingTheCitizenOpensItsTermsAsTheOfferWhileItsOfferIsOpen() {
        game.standsBesideAnn(mira);
        ResidentDialogue dialogue = new ResidentDialogue(game.world);
        assertFalse(dialogue.onCharacterClicked(game.ann, mira), "not her guest: the stock char info");

        guestOfAnn(mira);
        assertTrue(dialogue.onCharacterClicked(game.ann, mira), "her own guest: its terms, the way to hire without a book");
        assertTrue(lastTalk().text().startsWith("looking for work, actually. want a hand?"), lastTalk().text());
        LivingTalk.forget(game.ann);

        offerMade();
        assertTrue(dialogue.onCharacterClicked(game.ann, mira));

        Talk terms = lastTalk();
        assertEquals(CompanionMenus.BOARD_NPC, terms.npcId(), "a hire has no rendered face");
        assertEquals(ACCEPT_DECLINE, terms.msgType());
        assertTrue(terms.text().startsWith("this was fun. want me to stick around?"), terms.text());
        assertTrue(terms.text().contains("#eMira#n, Lv30 Hunter"), terms.text());
    }

    @Test
    void acceptingAdoptsTheGuestWhereItStands() {
        guestOfAnn(mira);
        offerMade();
        new ResidentDialogue(game.world).onCharacterClicked(game.ann, mira);

        answer(1);

        assertEquals(RosterState.ACTIVE, game.store.row(ANN, HIRE).state());
        assertEquals(List.of("stop Mira", "register Mira", "follow Mira"), game.calls, "no despawn, no spawn, no party churn");
        assertTrue(game.members.contains(MIRA));
        assertEquals(70_000, purse[0]);
        assertEquals(1, game.store.events(HiredHands.EVENT_ACCEPT).size());
        verify(game.residents).whisper(any(), eq(game.ann), eq("deal. let's go."));
        assertNull(offers.pending(ANN, MIRA), "the offer is over");
        assertTrue(offers.pendingOffers(game.ann).isEmpty());
        assertTrue(directory.resolve(game.ann, mira) instanceof Relation.OwnCompanion);

        minutes(30);
        verify(game.residents, times(1)).whisper(any(), any(), eq(OFFER));
    }

    @Test
    void aDeclinedOfferKeepsItQuietWithThatOwnerForAWhile() {
        guestOfAnn(mira);
        offerMade();
        new ResidentDialogue(game.world).onCharacterClicked(game.ann, mira);

        answer(0);

        verify(game.residents).whisper(any(), eq(game.ann), eq("no worries. i'll be around."));
        assertNull(offers.pending(ANN, MIRA));
        assertEquals(new Relation.Citizen(MIRA), directory.resolve(game.ann, mira), "char info again");
        assertTrue(game.store.events(HiredHands.EVENT_OFFER).contains(ANN + " " + HIRE + " hire.offer declined"));
        assertNull(game.store.row(ANN, HIRE));

        minutes(60);
        verify(game.residents, times(1)).whisper(any(), any(), eq(OFFER));

        now[0] += game.config.HIRE_OFFER_COOLDOWN_MINUTES * 60_000L;
        minutes(1);
        verify(game.residents, times(2)).whisper(any(), any(), eq(OFFER));
    }

    @Test
    void anUnansweredOfferExpiresAfterTenMinutesAndItStaysQuiet() {
        guestOfAnn(mira);
        offerMade();

        minutes(9);
        assertNotNull(offers.pending(ANN, MIRA));
        minutes(1);

        assertNull(offers.pending(ANN, MIRA));
        assertTrue(game.store.events(HiredHands.EVENT_OFFER).contains(ANN + " " + HIRE + " hire.offer expired"));
        minutes(60);
        verify(game.residents, times(1)).whisper(any(), any(), eq(OFFER));
    }

    // ---- when it does not ask -------------------------------------------------------------------

    @Test
    void oneOfferAtATimePerOwner() {
        Character tess = game.bot(TESS, "Tess");
        citizen(tess, "Tess");
        when(game.store.store.characterRow(TESS)).thenReturn(new CharacterRow("Tess", 1, 1, 21002, 31003, 30, HUNTER, KERNING));
        guestOfAnn(mira);
        guestOfAnn(tess);

        minutes(game.config.HIRE_OFFER_PARTY_MINUTES);

        assertEquals(List.of(MIRA), offers.pendingOffers(game.ann).stream().map(HireOffers.Offer::citizenCharacterId).toList());
        verify(game.residents, times(1)).whisper(any(), any(), eq(OFFER));

        game.world.executor().submit("hire.decline", () -> offers.declined(ANN, MIRA));
        minutes(1);
        assertEquals(List.of(TESS), offers.pendingOffers(game.ann).stream().map(HireOffers.Offer::citizenCharacterId).toList(),
                "Tess waited her turn");
    }

    @Test
    void noOfferWhileTheRosterIsFull() {
        for (int i = 0; i < game.config.ROSTER_MAX; i++) {
            game.store.seed(row("persona" + i));
        }
        guestOfAnn(mira);

        minutes(30);

        assertTrue(offers.pendingOffers(game.ann).isEmpty());
        verify(game.residents, never()).whisper(any(), any(), anyString());
    }

    @Test
    void noOfferWhileTheHireCapIsFull() {
        game.config.HIRE_MAX_PER_OWNER = 5; // below ROSTER_MAX, which it defaults to
        for (int i = 0; i < game.config.HIRE_MAX_PER_OWNER; i++) {
            game.store.seed(row("hire:" + (800 + i)));
        }
        guestOfAnn(mira);

        minutes(30);

        assertTrue(offers.pendingOffers(game.ann).isEmpty());
    }

    @Test
    void noOfferWhileTheOwnerIsCommittedToARunAndOneRightAfter() {
        guestOfAnn(mira);
        game.commitments.commit("kpq-1", ANN, 777);

        minutes(30);
        assertTrue(offers.pendingOffers(game.ann).isEmpty());

        game.commitments.releaseKey("kpq-1");
        minutes(1);
        assertEquals(1, offers.pendingOffers(game.ann).size(), "its minutes were kept for after the run");
    }

    @Test
    void noOfferWhileTheOwnerIsInAnEventInstance() {
        guestOfAnn(mira);
        game.annEntersInstance(KERNING);

        minutes(30);

        assertTrue(offers.pendingOffers(game.ann).isEmpty());
    }

    @Test
    void aReservedGuestMayKeepToItself() {
        when(voices.voiceOf(any())).thenReturn(voice(0.4d)); // terse-grinder
        when(social.sociability(any())).thenReturn(10);
        guestOfAnn(mira);

        minutes(60);

        assertTrue(offers.pendingOffers(game.ann).isEmpty(), "Mira's own roll for Ann (27) is over a reserved 10");
        verify(game.residents, times(1)).whisper(any(), eq(game.ann), eq(HireOffers.NOT_YET_LINE)); // once a stint
        verify(game.residents, times(1)).whisper(any(), any(), anyString());
    }

    @Test
    void aGuestThatSaysNoNowMaySayYesAfterMoreTimeTogether() {
        when(voices.voiceOf(any())).thenReturn(voice(0.4d)); // reserved: a chance of 10 against her roll for Ann of 27
        when(social.sociability(any())).thenReturn(10);
        guestOfAnn(mira);
        minutes(game.config.HIRE_OFFER_PARTY_MINUTES);
        assertTrue(offers.pendingOffers(game.ann).isEmpty());

        partyMinutesTogether[0] = 20; // the population's count of their time on one map: +20
        minutes(game.config.HIRE_OFFER_PARTY_MINUTES);

        assertEquals(1, offers.pendingOffers(game.ann).size(), "another stint's minutes, a little warmer for them");
        verify(game.residents).whisper(any(), eq(game.ann), eq(OFFER));
    }

    // ---- by standing (docs/BOT_CONCEPT_PLAN.md C1.3, HIRE_FRIENDSHIP_ENABLED) ----------------------------

    @Test
    void aGuestWithAGrudgeNeverOffers() {
        standing(-40);
        when(voices.voiceOf(any())).thenReturn(voice(0.05d)); // outgoing: today's roll would say yes
        when(social.sociability(any())).thenReturn(90);
        guestOfAnn(mira);

        minutes(120);

        assertTrue(offers.pendingOffers(game.ann).isEmpty());
        verify(game.residents, never()).whisper(any(), any(), anyString()); // not even "not looking for steady work"
        assertEquals(List.of(), game.store.events(HiredHands.EVENT_OFFER));
    }

    @Test
    void aStrangerDoesNotOfferByStanding() {
        standing(10);
        guestOfAnn(mira);

        minutes(60);

        assertTrue(offers.pendingOffers(game.ann).isEmpty(), "warmth 10 is under REGULAR (15)");
        verify(game.residents, times(1)).whisper(any(), eq(game.ann), eq(HireOffers.NOT_YET_LINE)); // once a stint
    }

    @Test
    void aRegularOffersAfterItsPartyTime() {
        standing(20);
        guestOfAnn(mira);

        minutes(game.config.HIRE_OFFER_PARTY_MINUTES - 1);
        assertTrue(offers.pendingOffers(game.ann).isEmpty(), "standing plus some party time");
        minutes(1);

        verify(game.residents).whisper(any(), eq(game.ann), eq(OFFER));
        assertEquals(List.of(ANN + " " + HIRE + " hire.offer after 10 party minutes, warmth 20"),
                game.store.events(HiredHands.EVENT_OFFER));
    }

    @Test
    void aReservedFriendOffersWithoutItsTemperamentsRoll() {
        standing(45);
        when(voices.voiceOf(any())).thenReturn(voice(0.4d)); // reserved: its roll for Ann (27) is over a chance of 10
        when(social.sociability(any())).thenReturn(10);
        guestOfAnn(mira);

        offerMade();

        verify(game.residents).whisper(any(), eq(game.ann), eq(OFFER));
    }

    @Test
    void aCooldownSurvivesARestart() {
        standing(20);
        long[] declinedAt = {0L};
        when(game.store.store.countEvents(eq(ANN), eq(HIRE), eq(HiredHands.EVENT_OFFER), anyLong())).thenAnswer(inv ->
                declinedAt[0] > 0 && (long) inv.getArgument(3) <= declinedAt[0] ? 2 : 0); // its offer and its decline
        guestOfAnn(mira);
        offerMade();
        offers.declined(ANN, MIRA);
        declinedAt[0] = now[0];

        HireOffers restarted = new HireOffers(game.world, () -> now[0]); // a restart: nothing in memory
        for (int i = 0; i < 60; i++) {
            now[0] += HireOffers.TICK_MS;
            restarted.tick();
        }
        assertTrue(restarted.pendingOffers(game.ann).isEmpty(), "living_event_log still says it was declined");

        now[0] += game.config.HIRE_OFFER_COOLDOWN_MINUTES * 60_000L;
        for (int i = 0; i < game.config.HIRE_OFFER_PARTY_MINUTES; i++) {
            now[0] += HireOffers.TICK_MS;
            restarted.tick();
        }
        assertEquals(1, restarted.pendingOffers(game.ann).size(), "asks again once the cooldown is over");
    }

    // ---- answering by whisper ---------------------------------------------------------------------

    @Test
    void aWhisperedYesOpensTheOfferAndANoClosesIt() {
        guestOfAnn(mira);
        offerMade();
        ResidentDialogue dialogue = new ResidentDialogue(game.world);

        assertTrue(dialogue.onWhisper(game.ann, "Mira", "sure!"));
        Talk terms = lastTalk();
        assertEquals(ACCEPT_DECLINE, terms.msgType());
        assertTrue(terms.text().startsWith("this was fun. want me to stick around?"), terms.text());
        LivingTalk.forget(game.ann);

        assertTrue(dialogue.onWhisper(game.ann, "Mira", "no thanks"));
        verify(game.residents).whisper(any(), eq(game.ann), eq("no worries. i'll be around."));
        assertNull(offers.pending(ANN, MIRA));
        assertTrue(game.store.events(HiredHands.EVENT_OFFER).contains(ANN + " " + HIRE + " hire.offer declined"));
        assertFalse(dialogue.onWhisper(game.ann, "Mira", "sure"), "no open offer: an ordinary whisper");
    }

    // ---- the hire list (plan "Citizen guest": an open offer stays reachable from the hire list) ----

    @Test
    void anOpenOfferTopsTheHireListAndOpensTheSameTermsWindowAsTheDoubleClick() {
        onTheHireList();
        guestOfAnn(mira);
        CompanionMenus menus = new CompanionMenus(game.world);
        menus.hireList(game.ann);
        assertEquals(List.of("Back"), lastTalk().labels(), "no offer yet, and nobody else she has met");
        LivingTalk.forget(game.ann);

        offerMade();
        new ResidentDialogue(game.world).onCharacterClicked(game.ann, mira);
        String doubleClicked = lastTalk().text();
        LivingTalk.forget(game.ann); // she walked away from that window

        menus.hireList(game.ann);
        assertEquals(List.of(OFFER_ROW, "Back"), lastTalk().labels());
        pick(OFFER_ROW);

        Talk terms = lastTalk();
        assertEquals(ACCEPT_DECLINE, terms.msgType());
        assertEquals(CompanionMenus.BOARD_NPC, terms.npcId(), "a hire has no rendered face");
        assertEquals(doubleClicked, terms.text(), "one terms window for the offer, whichever way it was opened");

        answer(1);

        assertEquals(RosterState.ACTIVE, game.store.row(ANN, HIRE).state());
        assertEquals(List.of("stop Mira", "register Mira", "follow Mira"), game.calls, "adopted where she stands");
        assertEquals(1, game.store.events(HiredHands.EVENT_ACCEPT).size());
        assertNull(offers.pending(ANN, MIRA), "the offer is over");
        LivingTalk.forget(game.ann);
        menus.hireList(game.ann);
        assertEquals(List.of("Back"), lastTalk().labels(), "hired: off the list");
    }

    @Test
    void decliningTheOfferFromTheHireListClosesItAsTheDoubleClickDoes() {
        onTheHireList();
        guestOfAnn(mira);
        offerMade();
        new CompanionMenus(game.world).hireList(game.ann);
        pick(OFFER_ROW);

        answer(0);

        verify(game.residents).whisper(any(), eq(game.ann), eq("no worries. i'll be around."));
        assertNull(offers.pending(ANN, MIRA));
        assertTrue(game.store.events(HiredHands.EVENT_OFFER).contains(ANN + " " + HIRE + " hire.offer declined"));
        assertNull(game.store.row(ANN, HIRE));
        minutes(60);
        verify(game.residents, times(1)).whisper(any(), any(), eq(OFFER));
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** HIRE_FRIENDSHIP_ENABLED, and Mira feels {@code warmth} toward Ann (her memory of Ann). */
    private void standing(int warmth) {
        game.config.HIRE_FRIENDSHIP_ENABLED = true;
        PersonMemories memories = mock(PersonMemories.class);
        when(memories.warmth(MIRA, ANN)).thenReturn(warmth);
        when(population.memories()).thenReturn(memories);
    }

    /** Ann can read the hire list: her level (Mira is in range) and her buddy list. */
    private void onTheHireList() {
        when(game.ann.getLevel()).thenReturn(30);
        when(game.ann.getBuddylist()).thenReturn(mock(BuddyList.class));
    }

    private void guestOfAnn(Character guest) {
        game.standsBesideAnn(guest);
        if (!game.members.contains(guest.getId())) {
            game.members.add(guest.getId());
        }
        assertTrue(game.slots.reserve(ANN, CompanionSlots.characterKey(guest.getId()), CompanionSlots.Kind.GUEST,
                Long.MAX_VALUE));
    }

    private void offerMade() {
        minutes(game.config.HIRE_OFFER_PARTY_MINUTES);
        assertNotNull(offers.pending(ANN, MIRA));
    }

    /** {@code count} minutes pass, each with the offers' minute on companion-ops. */
    private void minutes(int count) {
        for (int i = 0; i < count; i++) {
            now[0] += HireOffers.TICK_MS;
            game.world.executor().submit("hire.offers", offers::tick);
        }
    }

    private void citizen(Character character, String name) {
        citizens.put(character.getId(), HiredHandsTest.citizen(character.getId(), name));
        when(character.getLevel()).thenReturn(30);
        when(character.getJob()).thenReturn(Job.HUNTER);
        game.store.levels.put(character.getId(), 30);
        game.store.jobs.put(character.getId(), HUNTER);
    }

    private void pick(String label) {
        int selection = lastTalk().selection(label);
        assertTrue(LivingTalk.respond(client, MENU, (byte) 1, Packets.buildInPacket(out -> out.writeInt(selection))));
    }

    private void answer(int action) {
        assertTrue(LivingTalk.respond(client, ACCEPT_DECLINE, (byte) action, Packets.buildInPacket(out -> { })));
    }

    private Talk lastTalk() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(client, atLeastOnce()).sendPacket(captor.capture());
        List<Talk> talks = NpcTalks.talks(captor.getAllValues());
        return talks.get(talks.size() - 1);
    }

    private static RosterEntry row(String personaId) {
        return new RosterEntry(ANN, personaId, RosterState.BENCHED, Assignment.TRAIN, 0, 0, KERNING, 0L, 0L, 0L, 0L, 0L, 0L, 0,
                null);
    }

    private static Voice voice(double ignoreRate) {
        return new Voice("test", "friendly-casual-v1", 100, 0.8d, 0.0d, 0.0d, Map.of(), List.of(), 0.0d, List.of(), 0.0d, 50,
                ignoreRate);
    }
}
