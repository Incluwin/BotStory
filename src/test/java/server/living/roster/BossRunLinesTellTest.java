package server.living.roster;

import client.Character;
import net.server.Server;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * Where a companion's boss-run line goes (P7 Part B, BossRunLines.tell): a party companion tells its party, as in Part
 * A; a bench ally, which leads itself in a party of bots, says it on its map when its anchor (its owner, or the human
 * it sees the run through for) stands on that map, else whispers it to the anchor, each intent once per run, so five
 * allies down in the fight are one whisper. "{eta}" reads as the ally's recall ETA.
 */
class BossRunLinesTellTest {
    private static final int THANE = 902;
    private static final int ORRIN = 911;
    private static final int MAGNA = 912;
    private static final int FRIEND = 20;
    private static final int ROOM = 105100100;

    private final LifecycleHarness game = new LifecycleHarness();
    /** Every map-chat line, as "Name: line". */
    private final List<String> mapChat = new ArrayList<>();
    /** Every whisper, as "Persona -> Player: line". */
    private final List<String> whispers = new ArrayList<>();
    private final CompanionBossRun run = new CompanionBossRun("easy_balrog", 7L, ANN);
    private MockedStatic<AgentDialogueTransportRuntime> transport;
    private boolean mapChatWasOn;
    private Character thane;
    private Character orrin;
    private Character magna;
    private MapleMap room;

    @BeforeEach
    void annAtMuYoungWithThaneInHerPartyAndTwoAlliesBesideHer() {
        game.moveAnn(ROOM, 617, 246);
        Persona thanePersona = game.companion("thane", "Thane", THANE, RosterState.ACTIVE, ROOM);
        game.companion("orrin", "Orrin", ORRIN, RosterState.BENCHED, 105040300);
        game.companion("magna", "Magna", MAGNA, RosterState.BENCHED, 105040300);
        game.roster();
        game.outWithAnn(thanePersona);
        thane = game.bots.get(THANE);
        orrin = game.bots.get(ORRIN);
        magna = game.bots.get(MAGNA);
        game.standsBesideAnn(orrin); // loaded, led by itself, in no party with Ann
        game.standsBesideAnn(magna);
        room = game.map(ROOM);
        when(room.getCharacterById(ANN)).thenAnswer(inv -> game.ann.getMap() == room ? game.ann : null);
        doAnswer(inv -> whispers.add(((Persona) inv.getArgument(0)).name() + " -> "
                + ((Character) inv.getArgument(1)).getName() + ": " + inv.getArgument(2)))
                .when(game.residents).whisper(any(), any(), anyString());
        transport = mockStatic(AgentDialogueTransportRuntime.class);
        transport.when(() -> AgentDialogueTransportRuntime.sayMapNow(any(), anyString())).thenAnswer(inv ->
                mapChat.add(((Character) inv.getArgument(0)).getName() + ": " + inv.getArgument(1)));
        mapChatWasOn = config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED;
        config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED = true;
    }

    @AfterEach
    void close() {
        config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED = mapChatWasOn;
        transport.close();
        game.close();
    }

    @Test
    void aCompanionInItsOwnersPartyTellsItsPartyAsInPartA() {
        assertTrue(BossRunLines.tell(game.world, thane, BossRunLines.DOWN, null, null, run, ANN));
        assertTrue(BossRunLines.say(game.world, thane, BossRunLines.DOWN, null, 0), "say is unchanged");

        assertEquals(2, game.partyChat.size(), game.partyChat.toString());
        for (String line : game.partyChat) {
            assertSays("Thane", BossRunLines.DOWN, line);
        }
        assertEquals(List.of(), mapChat);
        assertEquals(List.of(), whispers);
    }

    @Test
    void anAllyBesideItsAnchorSaysItOnTheirMapEveryTime() {
        assertTrue(BossRunLines.tell(game.world, orrin, BossRunLines.ALLY_HERE, null, null, run, ANN));
        assertTrue(BossRunLines.tell(game.world, magna, BossRunLines.ALLY_HERE, null, null, run, ANN));

        assertEquals(2, mapChat.size(), mapChat.toString());
        assertSays("Orrin", BossRunLines.ALLY_HERE, mapChat.get(0));
        assertSays("Magna", BossRunLines.ALLY_HERE, mapChat.get(1));
        assertFalse(wording("Orrin", mapChat.get(0)).equals(wording("Magna", mapChat.get(1))),
                "two allies of one run say it in their own words: " + mapChat);
        assertEquals(List.of(), game.partyChat, "an ally in no party has no party chat");
        assertEquals(List.of(), whispers);
    }

    @Test
    void anAllyInAPartyOfBotsSaysItOnItsAnchorsMapOrWhispersNeverInThatPartysChat() {
        Party allies = party(member(ORRIN, true, true), member(MAGNA, true, true)); // the overflow party formParty makes
        doReturn(allies).when(orrin).getParty();
        doReturn(allies).when(magna).getParty();

        assertTrue(BossRunLines.tell(game.world, orrin, BossRunLines.ALLY_HERE, null, null, run, ANN));
        game.annEntersInstance(105100400);
        assertTrue(BossRunLines.tell(game.world, magna, BossRunLines.ALLY_GOOD_FIGHT, null, null, run, ANN));

        assertEquals(List.of(), game.partyChat, "no human hears a party of bots: " + game.partyChat);
        assertEquals(1, mapChat.size(), mapChat.toString());
        assertSays("Orrin", BossRunLines.ALLY_HERE, mapChat.get(0));
        assertEquals(1, whispers.size(), whispers.toString());
        assertWhispers("Magna", "Ann", BossRunLines.ALLY_GOOD_FIGHT, whispers.get(0));
    }

    @Test
    void aHumanWhoLoggedOffDoesNotMakeAnAllysPartyChatHeard() {
        Party allies = party(member(ORRIN, true, true), member(FRIEND, false, false), member(MAGNA, true, true));
        doReturn(allies).when(orrin).getParty();

        assertTrue(BossRunLines.tell(game.world, orrin, BossRunLines.ALLY_HERE, null, null, run, ANN));

        assertEquals(List.of(), game.partyChat, "its only human is offline: " + game.partyChat);
        assertEquals(1, mapChat.size(), mapChat.toString());
        assertSays("Orrin", BossRunLines.ALLY_HERE, mapChat.get(0));
        assertEquals(List.of(), whispers);
    }

    @Test
    void anAllyAwayFromItsAnchorWhispersEachIntentOncePerRun() {
        game.annEntersInstance(105100400); // Ann fights Balrog; her allies went down and wait in the room

        assertTrue(BossRunLines.tell(game.world, orrin, BossRunLines.DOWN, null, null, run, ANN));
        assertFalse(BossRunLines.tell(game.world, magna, BossRunLines.DOWN, null, null, run, ANN),
                "one down whisper per run");
        assertTrue(BossRunLines.tell(game.world, magna, BossRunLines.ALLY_NEXT_TIME, null, null, run, ANN),
                "another intent is whispered too");
        CompanionBossRun nextRun = new CompanionBossRun("easy_balrog", 8L, ANN);
        assertTrue(BossRunLines.tell(game.world, magna, BossRunLines.DOWN, null, null, nextRun, ANN),
                "the next run whispers it again");
        Character friend = mock(Character.class);
        when(friend.getId()).thenReturn(FRIEND);
        when(friend.getName()).thenReturn("Ohboy");
        when(Server.getInstance().getWorld(0).getPlayerStorage().getCharacterById(FRIEND)).thenReturn(friend);
        assertTrue(BossRunLines.tell(game.world, magna, BossRunLines.DOWN, null, null, run, FRIEND),
                "the human it sees the run through for hears it once too");

        assertEquals(4, whispers.size(), whispers.toString());
        assertWhispers("Orrin", "Ann", BossRunLines.DOWN, whispers.get(0));
        assertWhispers("Magna", "Ann", BossRunLines.ALLY_NEXT_TIME, whispers.get(1));
        assertWhispers("Magna", "Ann", BossRunLines.DOWN, whispers.get(2));
        assertWhispers("Magna", "Ohboy", BossRunLines.DOWN, whispers.get(3));
        assertEquals(List.of(), mapChat, "Ann is not on their map");
        assertEquals(List.of(), game.partyChat);
    }

    @Test
    void withBotsMapChatOffAnAllyBesideItsAnchorWhispersInstead() {
        config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED = false;

        assertTrue(BossRunLines.tell(game.world, orrin, BossRunLines.ALLY_HERE, null, null, run, ANN));

        assertEquals(List.of(), mapChat);
        assertEquals(1, whispers.size(), whispers.toString());
        assertWhispers("Orrin", "Ann", BossRunLines.ALLY_HERE, whispers.get(0));
    }

    @Test
    void theEtaIsReadIntoTheLineInEachWording() {
        String eta = RecallTravel.describe(45_000L);
        Set<String> said = new HashSet<>();
        for (int turn = 0; turn < 3; turn++) {
            assertTrue(BossRunLines.tell(game.world, orrin, BossRunLines.ALLY_ON_MY_WAY, null, eta, run, ANN));
        }

        assertEquals(3, mapChat.size(), mapChat.toString());
        for (String line : mapChat) {
            String words = wording("Orrin", line);
            assertTrue(words.contains("~45 s") && !words.contains("{eta}"), words);
            assertTrue(BossRunLines.GENERIC.get(BossRunLines.ALLY_ON_MY_WAY).contains(words.replace("~45 s", "{eta}")),
                    words);
            said.add(words);
        }
        assertEquals(3, said.size(), "every wording once: " + mapChat);
    }

    @Test
    void nobodyToTellSaysNothing() {
        assertFalse(BossRunLines.tell(game.world, null, BossRunLines.ALLY_HERE, null, null, run, ANN), "no bot");
        assertFalse(BossRunLines.tell(game.world, orrin, "boss.ally.nosuchintent", null, null, run, ANN), "no line");
        assertFalse(BossRunLines.tell(game.world, orrin, BossRunLines.ALLY_HERE, null, null, run, 0), "no anchor");
        game.moveAnn(LifecycleHarness.HENESYS, 1000, 200);
        game.annOnline = false;
        assertFalse(BossRunLines.tell(game.world, orrin, BossRunLines.ALLY_HERE, null, null, run, ANN),
                "its anchor logged off");

        assertEquals(List.of(), mapChat);
        assertEquals(List.of(), whispers);
        assertEquals(List.of(), game.partyChat);
    }

    @Test
    void aMissedStartLineIsTrueWhenTheAllyWentInAndItsOwnerDidNot() {
        // Ann stood on the stairway when the leader pressed Start: the Start took Orrin in from the room, the run
        // moved him back out after the grace (his anchor was not in it), and he whispers her. She never went in.
        game.moveAnn(105100000, 0, 0);

        assertTrue(BossRunLines.tell(game.world, orrin, BossRunLines.ALLY_MISSED, null, null, run, ANN));

        assertEquals(1, whispers.size(), whispers.toString());
        assertWhispers("Orrin", "Ann", BossRunLines.ALLY_MISSED, whispers.get(0));
        for (String line : BossRunLines.GENERIC.get(BossRunLines.ALLY_MISSED)) {
            for (String claim : List.of("you went", "went in", "without me")) {
                assertFalse(line.contains(claim), "no missed-start line says who went in: " + line);
            }
        }
    }

    @Test
    void aPersonaWithItsOwnMissedStartLinesSaysThoseInstead() {
        Persona orrinPersona = game.world.residents().personaOfCharacterId(ORRIN);
        when(game.world.arcs().lines(orrinPersona, BossRunLines.ALLY_MISSED))
                .thenReturn(List.of("late again... back to camp for me."));

        assertTrue(BossRunLines.tell(game.world, orrin, BossRunLines.ALLY_MISSED, null, null, run, ANN));
        assertTrue(BossRunLines.tell(game.world, magna, BossRunLines.ALLY_MISSED, null, null, run, ANN));

        assertEquals(2, mapChat.size(), mapChat.toString());
        assertEquals("Orrin: late again... back to camp for me.", mapChat.get(0), "the persona's own line");
        assertSays("Magna", BossRunLines.ALLY_MISSED, mapChat.get(1));
    }

    @Test
    void everyAllyIntentHasItsOwnPlayerLinesAndOnlyTheOnMyWayLinesNameTheEta() {
        Map<String, List<String>> plan = Map.ofEntries(
                Map.entry(BossRunLines.ALLY_ON_MY_WAY, List.of("heading down to the temple. {eta}.",
                        "omw to mu young, {eta}.", "coming! be there in {eta}.")),
                Map.entry(BossRunLines.ALLY_NO_ROOM, List.of(
                        "too many of us out right now, can't make it down. sorry!")),
                Map.entry(BossRunLines.ALLY_CANT_COME, List.of("can't get away right now, sorry.",
                        "something came up, can't make it this time.")),
                Map.entry(BossRunLines.ALLY_HERE, List.of("made it down. signing up.", "here! putting my name down.",
                        "phew, long way down. where do i sign?")),
                Map.entry(BossRunLines.ALLY_LOW_POTS, List.of("bit low on pots, i'll make it work.",
                        "running low on pots and no shop down here. i'll manage.")),
                Map.entry(BossRunLines.ALLY_LOW_AMMO, List.of("low on {ammo}, i'll make it last.",
                        "not many {ammo} left and no shop down here. i'll make them count.")),
                Map.entry(BossRunLines.ALLY_TURNED_BACK, List.of(
                        "list closed before i got there. heading back to camp.",
                        "won't make it down in time, heading back. good luck!")),
                Map.entry(BossRunLines.ALLY_NOT_LISTED, List.of("couldn't get my name down. heading back to camp.",
                        "missed the list, sorry. back to camp for me.")),
                Map.entry(BossRunLines.ALLY_CALLED_OFF, List.of("guess we're not going. back to camp.",
                        "no fight today huh. back to camp then.")),
                Map.entry(BossRunLines.ALLY_MISSED, List.of("missed the start. back to camp for me.",
                        "didn't make it in with you. heading back to camp.")),
                Map.entry(BossRunLines.ALLY_GOOD_FIGHT, List.of("good fight! back to camp.",
                        "good fight. see ya around.", "gg, good fight. heading back to camp.")),
                Map.entry(BossRunLines.ALLY_NEXT_TIME, List.of("we'll get him next time. back to camp.",
                        "rough one. heading back to camp.")),
                Map.entry(BossRunLines.ALLY_GOODBYE, List.of("heading back to camp. see ya.")));
        for (Map.Entry<String, List<String>> intent : plan.entrySet()) {
            assertTrue(intent.getKey().startsWith("boss.ally."), intent.getKey());
            assertEquals(intent.getValue(), BossRunLines.GENERIC.get(intent.getKey()), intent.getKey());
            for (String line : intent.getValue()) {
                assertEquals(line.toLowerCase(Locale.ROOT), line, line);
                assertEquals(intent.getKey().equals(BossRunLines.ALLY_ON_MY_WAY), line.contains("{eta}"), line);
            }
        }
        long allyIntents = BossRunLines.GENERIC.keySet().stream().filter(key -> key.startsWith("boss.ally.")).count();
        assertEquals(plan.size(), allyIntents, "the plan's twelve ally intents, the low-ammo line (R5) and no other");
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** A party of {@code members} (a mock: only its member list answers). */
    private static Party party(PartyCharacter... members) {
        Party party = mock(Party.class);
        when(party.getMembers()).thenReturn(List.of(members));
        return party;
    }

    /** A party row for {@code id}: a bot or a human, online or offline. */
    private static PartyCharacter member(int id, boolean bot, boolean online) {
        PartyCharacter member = mock(PartyCharacter.class);
        when(member.getId()).thenReturn(id);
        when(member.isBot()).thenReturn(bot);
        when(member.isOnline()).thenReturn(online);
        return member;
    }

    /** {@code line} is "{@code name}: " and one of the generic wordings for {@code intent}. */
    private static void assertSays(String name, String intent, String line) {
        assertTrue(BossRunLines.GENERIC.get(intent).contains(wording(name, line)), intent + ": " + line);
    }

    /** {@code line} is "{@code from} -> {@code to}: " and one of the generic wordings for {@code intent}. */
    private static void assertWhispers(String from, String to, String intent, String line) {
        assertSays(from + " -> " + to, intent, line);
    }

    /** What {@code name} said in {@code line} ("Name: words"). */
    private static String wording(String name, String line) {
        assertTrue(line.startsWith(name + ": "), line);
        return line.substring(name.length() + 2);
    }
}
