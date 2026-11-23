package server.living.population;

import client.Character;
import client.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.population.CitizenSession.Activity;
import server.living.population.records.Clear;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Guild chat that answers (docs/WORLD_SYSTEMS_PLAN.md N5.1): at most two guild mates answer a human's line, each after
 * a pause; citizens of another guild and a human with no bot guild get nothing; a question takes one conversation
 * turn; logins are greeted but not a channel change; an LFG line ends in a real party invite, from a party already
 * forming or from a guild mate who goes to the lobby for it.
 */
class GuildChatTest {
    private static final long T0 = 3_600_000L;
    private static final int GUILD = 7;
    private static final int OTHER_GUILD = 8;
    private static final int FIELD = 103_000_101;

    private final CitizenLobbyFixture lobby = new CitizenLobbyFixture();
    private final CitizenLobbyFixture.Door door = new CitizenLobbyFixture.Door();
    private final CitizenPartyQuests quests = lobby.quests(door);
    private final Conversation conversation = mock(Conversation.class);
    private final VoicePacks voices = mock(VoicePacks.class, withSettings().stubOnly());
    /** Guild lines out: who, the trigger (compose returns it), and the pause before typing. */
    private final List<Said> said = new ArrayList<>();
    private final List<String> turns = new ArrayList<>();
    private final GuildChat chat = new GuildChat(lobby.population, new Random(3L),
            guildId -> new ArrayList<>(lobby.sessions.values()), guildId -> true);

    record Said(String who, String trigger, long noticeMs, UnaryOperator<String> atSend) { }

    @BeforeEach
    void setUp() {
        lobby.config.GUILD_CHAT_ENABLED = true;
        when(lobby.population.conversation()).thenReturn(conversation);
        when(lobby.population.voices()).thenReturn(voices);
        when(lobby.population.partyQuests()).thenReturn(quests);
        when(lobby.fields.name(org.mockito.ArgumentMatchers.anyInt())).thenReturn("Lakelis");
        when(conversation.guildTurn(any(), any(), anyString())).thenAnswer(call -> {
            turns.add(((CitizenSession) call.getArgument(0)).name());
            return true;
        });
        ChatterEngine chatter = lobby.population.chatter();
        when(chatter.compose(any())).thenAnswer(call -> ((ChatterEngine.Event) call.getArgument(0)).trigger);
        doAnswer(call -> {
            said.add(new Said(((CitizenSession) call.getArgument(0)).name(), call.getArgument(1), call.getArgument(2),
                    call.getArgument(3)));
            return 0L;
        }).when(chatter).guildSay(any(), anyString(), anyLong(), any());
    }

    @AfterEach
    void tearDown() {
        lobby.close();
    }

    /** A citizen of {@code guildId} in town. */
    private Character mate(int id, String name, int level, int guildId) {
        Character chr = lobby.elsewhere(id, name, level, Activity.TOWN, 103_000_000);
        when(chr.getGuildId()).thenReturn(guildId);
        return chr;
    }

    /** A human of {@code guildId} training on a field of Kerning, on the fixture's channel. */
    private Character human(int id, String name, int level, int guildId) {
        Character human = lobby.human(id, name, level);
        lobby.away(human, FIELD);
        when(human.getGuildId()).thenReturn(guildId);
        Client client = mock(Client.class, withSettings().stubOnly());
        when(client.getChannel()).thenReturn(CitizenLobbyFixture.CHANNEL);
        when(human.getClient()).thenReturn(client);
        return human;
    }

    private void fourMates() {
        mate(1, "Lia", 25, GUILD);
        mate(2, "Mira", 22, GUILD);
        mate(3, "Pip", 28, GUILD);
        mate(4, "Tobi", 24, GUILD);
    }

    // ---- replies ---------------------------------------------------------------------------------------------------

    @Test
    void aGreetingGetsAtMostTwoAnswersEachAfterAPause() {
        fourMates();
        Character ann = human(10, "Ann", 24, GUILD);

        for (int i = 0; i < 20; i++) { // every draw: never more than two
            said.clear();
            assertTrue(chat.heard(ann, "hi all", T0 + i * GuildChat.GUILD_ANSWER_GAP_MS * 2));
            assertTrue(said.size() >= 1 && said.size() <= GuildChat.REPLIERS_MAX, said.toString());
            assertTrue(said.stream().allMatch(line -> line.trigger().equals("guild.reply.hi")));
            assertTrue(said.get(0).noticeMs() >= GuildChat.NOTICE_MIN_MS, "read before answered");
            if (said.size() == 2) {
                assertTrue(said.get(1).noticeMs() >= said.get(0).noticeMs() + GuildChat.SECOND_MIN_MS,
                        "the second a few seconds after the first");
                assertFalse(said.get(0).who().equals(said.get(1).who()));
            }
        }
    }

    @Test
    void aGreetingTypedAgainAtOnceGetsNoSecondRound() {
        fourMates();
        Character ann = human(10, "Ann", 24, GUILD);

        chat.heard(ann, "hey", T0);
        int first = said.size();
        chat.heard(ann, "heyy guys", T0 + 5_000L);

        assertEquals(first, said.size());
    }

    @Test
    void citizensOfAnotherGuildNeverAnswer() {
        mate(1, "Lia", 25, OTHER_GUILD);
        mate(2, "Mira", 22, OTHER_GUILD);
        Character ann = human(10, "Ann", 24, GUILD);
        Character loner = human(11, "Loner", 24, 0);

        assertFalse(chat.heard(ann, "hi all", T0));
        assertFalse(chat.heard(ann, "anyone know where to train?", T0));
        assertFalse(chat.heard(loner, "hi", T0));
        assertTrue(said.isEmpty());
        assertTrue(turns.isEmpty());
    }

    @Test
    void nothingWhileTheSwitchIsOffOrFromABot() {
        fourMates();
        Character ann = human(10, "Ann", 24, GUILD);
        Character companion = lobby.companion(20, "Brannock", 24);
        when(companion.getGuildId()).thenReturn(GUILD);

        assertFalse(chat.heard(companion, "hi all", T0));
        lobby.config.GUILD_CHAT_ENABLED = false;
        assertFalse(chat.heard(ann, "hi all", T0));
        assertTrue(said.isEmpty());
    }

    @Test
    void aQuestionTakesOneConversationTurnAndANamedMateAnswersItself() {
        fourMates();
        Character ann = human(10, "Ann", 24, GUILD);

        assertTrue(chat.heard(ann, "where do you guys train at 25?", T0));
        assertEquals(1, turns.size());
        assertTrue(said.isEmpty(), "the conversation path types it");

        turns.clear();
        assertTrue(chat.heard(ann, "pip u there", T0 + 60_000L));
        assertEquals(List.of("Pip"), turns);
    }

    @Test
    void anAwayMateDoesNotAnswer() {
        Character lia = mate(1, "Lia", 25, GUILD);
        lobby.session(lia).afkUntilMs = T0 + 600_000L;
        Character ann = human(10, "Ann", 24, GUILD);

        assertFalse(chat.heard(ann, "hi", T0));
        assertTrue(said.isEmpty());
    }

    // ---- logins and levels -----------------------------------------------------------------------------------------

    @Test
    void aLoginIsGreetedButAChannelChangeIsNot() {
        fourMates();
        Character ann = human(10, "Ann", 24, GUILD);

        chat.memberOnline(ann, true, T0);
        assertTrue(said.size() >= 1 && said.size() <= 2);
        assertTrue(said.stream().allMatch(line -> line.trigger().equals("guild.member.hi")));

        said.clear();
        chat.memberOnline(ann, true, T0 + 30_000L); // a channel change: online again with no logout
        assertTrue(said.isEmpty());

        for (int i = 0; i < 10 && said.isEmpty(); i++) { // seen off half the time
            chat.memberOnline(ann, false, T0 + 60_000L);
            chat.memberOnline(ann, true, T0 + 61_000L);
            said.removeIf(line -> line.trigger().equals("guild.member.hi"));
        }
        Said bye = said.get(0);
        assertEquals("guild.member.bye", bye.trigger());
        assertNull(bye.atSend().apply("bye Ann"), "she is back: the line is dropped at send");
    }

    @Test
    void aLevelUpGetsAGz() {
        fourMates();
        Character ann = human(10, "Ann", 25, GUILD);

        chat.levelUp(ann, T0);

        assertTrue(said.size() >= 1 && said.size() <= 2);
        assertEquals("guild.gz", said.get(0).trigger());
    }

    @Test
    void aClearWithAHumanMemberGetsAGzFromAMateNotInIt() {
        fourMates();
        Character ann = human(10, "Ann", 25, GUILD);
        Clear clear = new Clear(1, "KerningPQ", "", 1, 103_000_805, 0L, T0, 0L, Clear.Kind.HUMAN, 0,
                List.of(new Clear.Member(10, Clear.AS_PLAYER, "Ann"), new Clear.Member(1, Clear.AS_CITIZEN, "Lia")));

        chat.cleared(clear, T0);

        assertEquals(1, said.size());
        assertEquals("guild.clear.gz", said.get(0).trigger());
        assertFalse(said.get(0).who().equals("Lia"), "Lia was in it");
    }

    // ---- LFG ---------------------------------------------------------------------------------------------------------

    @Test
    void lfgLinesNameTheQuest() {
        assertEquals("kpq", GuildChat.lfgQuest("anyone up for kpq"));
        assertEquals("kpq", GuildChat.lfgQuest("kpq anyone?"));
        assertEquals("lpq", GuildChat.lfgQuest("lf2m lpq"));
        assertEquals("opq", GuildChat.lfgQuest("who wants to do opq"));
        assertNull(GuildChat.lfgQuest("kpq was fun yesterday"));
        assertNull(GuildChat.lfgQuest("anyone up for a drink"));
    }

    @Test
    void anLfgLineGetsTheInviteOfAPartyAGuildMateIsFormingAtTheLobby() {
        Character lia = lobby.citizen(1, "Lia", 25);
        when(lia.getGuildId()).thenReturn(GUILD);
        lobby.citizen(2, "Mira", 22);
        lobby.citizen(3, "Pip", 28);
        Character ann = human(10, "Ann", 24, GUILD);
        quests.tick(T0); // Lia's party forms at Lakelis
        assertFalse(lobby.parties.hasPendingPartyInvite(ann.getId()));

        assertTrue(chat.heard(ann, "anyone up for kpq", T0 + 1_000L));

        assertTrue(lobby.parties.hasPendingPartyInvite(ann.getId()), "a real invite");
        assertEquals(1, said.size());
        assertEquals("Lia", said.get(0).who());
        assertEquals("guild.lfg.invited", said.get(0).trigger());
    }

    @Test
    void anLfgLineSendsAFreeGuildMateToTheLobbyWhoseFormingPartyThenInvites() {
        Character lia = mate(1, "Lia", 25, GUILD);
        Character ann = human(10, "Ann", 24, GUILD);

        assertTrue(chat.heard(ann, "anyone up for kpq?", T0));
        assertEquals("guild.lfg.going", said.get(0).trigger());
        assertTrue(lobby.asked.contains("travel Lia PQ " + CitizenLobbyFixture.KPQ.mapId()), lobby.asked.toString());
        assertEquals(1, chat.going().size());

        chat.tick(T0 + 10_000L); // not there yet: nothing
        assertFalse(lobby.parties.hasPendingPartyInvite(ann.getId()));

        lobby.citizen(2, "Mira", 22);
        lobby.citizen(3, "Pip", 28);
        lobby.arrives(lia);
        quests.tick(T0 + 60_000L);
        chat.tick(T0 + 61_000L);

        assertTrue(lobby.parties.hasPendingPartyInvite(ann.getId()), "the party she asked for invites her");
        assertEquals("guild.lfg.ready", said.get(said.size() - 1).trigger());
        assertTrue(chat.going().isEmpty());
    }

    @Test
    void anLfgLineFromAHumanOutsideTheBandIsToldSo() {
        mate(1, "Lia", 25, GUILD);
        Character high = human(10, "High", 45, GUILD);

        assertTrue(chat.heard(high, "anyone up for kpq", T0));

        assertEquals("guild.lfg.level", said.get(0).trigger());
        assertTrue(lobby.asked.stream().noneMatch(line -> line.startsWith("travel")));
        assertTrue(chat.going().isEmpty());
    }

    @Test
    void aQuestCitizensDoNotRunIsJustChat() {
        fourMates();
        Character ann = human(10, "Ann", 24, GUILD);
        lobby.config.PQ_RUN_QUESTS = "hpq";

        chat.heard(ann, "anyone up for kpq?", T0);

        assertTrue(chat.going().isEmpty());
        verify(conversation, times(1)).guildTurn(any(), any(), anyString());
    }
}
