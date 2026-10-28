package server.living.population;

import client.Character;
import client.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.population.CitizenSession.Activity;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Players' whispers to citizens and the party megaphone (docs/BOT_CONCEPT_PLAN.md F5c): a seat request to a short
 * party's leader gets its invite by the lobby invite's rules, refusals say why, a whisper back to the citizen's own
 * whisper answers that thread (never "busy rn"), other whispers get a short reply by what it is doing, and the leader of
 * a party still short megaphones once with a real megaphone. With the switches off, whispers are ignored as before.
 */
class CitizenWhispersTest {
    private static final long MINUTE = 60_000L;

    private final CitizenLobbyFixture lobby = new CitizenLobbyFixture();
    private final CitizenLobbyFixture.Door door = new CitizenLobbyFixture.Door();
    private final CitizenPartyQuests quests = lobby.quests(door);
    private final CitizenWhispers whispers = new CitizenWhispers(lobby.population, new Random(3L));
    /** "trigger -> player" for every whispered answer; the fixture's compose answers with the trigger. */
    private final List<String> answers = new ArrayList<>();
    /** The megaphone lines sent (text, consumeItem). */
    private final List<String> megaphones = new ArrayList<>();
    private boolean capOpen = true;

    @BeforeEach
    void setUp() {
        lobby.config.CITIZEN_WHISPERS_ENABLED = true;
        lobby.config.LF_SMEGA_ENABLED = true;
        when(lobby.population.partyQuests()).thenReturn(quests);
        when(lobby.population.citizenByName(anyString())).thenAnswer(call -> lobby.sessions.values().stream()
                .filter(session -> session.name().equalsIgnoreCase(call.getArgument(0))).map(session -> session.citizen)
                .findFirst().orElse(null));
        VoicePacks voices = mock(VoicePacks.class, withSettings().stubOnly());
        when(lobby.population.voices()).thenReturn(voices);
        ChatterEngine chatter = lobby.population.chatter();
        when(chatter.compose(any())).thenAnswer(call -> {
            ChatterEngine.Event event = call.getArgument(0);
            String leader = event.slots.get("leader");
            return event.trigger + (event.need > 0 ? " need=" + event.need : "") + (leader == null ? "" : " leader=" + leader);
        });
        doAnswer(call -> answers.add(call.getArgument(2) + " -> " + ((Character) call.getArgument(1)).getName()))
                .when(chatter).whisperReply(any(), any(), anyString());
        doAnswer(call -> {
            if (!capOpen) {
                return false;
            }
            megaphones.add(call.getArgument(1) + " item=" + call.getArgument(2));
            return true;
        }).when(chatter).megaphone(any(), anyString(), anyBoolean());
    }

    @AfterEach
    void tearDown() {
        lobby.close();
    }

    private Character onAnotherMap(Character human) {
        lobby.away(human, 100_000_000);
        return human;
    }

    private static void onChannel(Character chr, int channel) {
        Client client = mock(Client.class, withSettings().stubOnly());
        when(client.getChannel()).thenReturn(channel);
        when(chr.getClient()).thenReturn(client);
    }

    @Test
    void meToAShortPartysLeaderGetsTheInviteAndWhereToCome() {
        Character lia = lobby.citizen(1, "Lia", 25);
        lobby.citizen(2, "Mira", 22);
        Character ann = onAnotherMap(lobby.human(10, "Ann", 24));
        quests.tick(0L);

        assertTrue(whispers.onWhisper(ann, "Lia", "me", MINUTE));

        assertTrue(lobby.parties.hasPendingPartyInvite(ann.getId()));
        assertEquals(List.of("whisper.join.where -> Ann"), answers, "she is not on the lobby: it says where");
        assertTrue(lobby.session(lia).journal.summary(300).contains("invited Ann to KPQ (they whispered)"));
    }

    @Test
    void canIJoinFromSomeoneAtTheLobbyIsJustInvited() {
        lobby.citizen(1, "Lia", 25);
        lobby.citizen(2, "Mira", 22);
        Character ann = lobby.human(10, "Ann", 24);
        onChannel(ann, CitizenLobbyFixture.CHANNEL);
        quests.tick(0L); // the lobby invite goes to Ann at once: she declines, then asks after all
        lobby.parties.decline(ann);

        assertTrue(whispers.onWhisper(ann, "lia", "hey can i join pls?", MINUTE));

        assertTrue(lobby.parties.hasPendingPartyInvite(ann.getId()), "she asked: the 10-minute invite gap does not apply");
        assertEquals(List.of("whisper.join.invited -> Ann"), answers);
    }

    @Test
    void refusalsSayWhy() {
        lobby.citizen(1, "Lia", 25);
        lobby.citizen(2, "Mira", 22);
        Character old = onAnotherMap(lobby.human(10, "Old", 45));
        Character partied = onAnotherMap(lobby.human(11, "Pat", 24));
        Character friend = onAnotherMap(lobby.human(12, "Fay", 24));
        lobby.parties.lead(partied, friend);
        Character bo = lobby.human(13, "Bo", 24); // at the lobby: gets the lobby invite
        quests.tick(0L);
        assertTrue(lobby.parties.hasPendingPartyInvite(bo.getId()));
        Character ann = onAnotherMap(lobby.human(14, "Ann", 24));

        long held = 30_000L; // Bo's seat waits PQ_HUMAN_SEAT_HOLD_MS (60 s) for his answer
        whispers.onWhisper(old, "Lia", "inv", held);
        whispers.onWhisper(partied, "Lia", "inv", held);
        whispers.onWhisper(ann, "Lia", "inv", held);

        assertEquals(List.of("whisper.join.level -> Old", "whisper.join.party -> Pat", "whisper.join.wait -> Ann"), answers);
        assertFalse(lobby.parties.hasPendingPartyInvite(ann.getId()), "one invite out at a time");
    }

    @Test
    void aFullPartySaysSo() {
        lobby.citizen(1, "Lia", 25);
        lobby.citizen(2, "Mira", 22);
        lobby.citizen(3, "Pip", 28);
        lobby.human(13, "Bo", 24); // the fourth seat waits for Bo's answer
        quests.tick(0L);
        Character ann = onAnotherMap(lobby.human(14, "Ann", 24));

        whispers.onWhisper(ann, "Lia", "room?", 30_000L); // within Bo's 60 s seat hold

        assertEquals(List.of("whisper.join.full -> Ann"), answers);
    }

    @Test
    void aMemberPointsToItsLeader() {
        lobby.citizen(1, "Lia", 25);
        lobby.citizen(2, "Mira", 22);
        quests.tick(0L);
        Character ann = onAnotherMap(lobby.human(10, "Ann", 24));

        whispers.onWhisper(ann, "Mira", "can i come", MINUTE);

        assertEquals(List.of("whisper.join.leader leader=Lia -> Ann"), answers);
        assertFalse(lobby.parties.hasPendingPartyInvite(ann.getId()));
    }

    @Test
    void theInvitedWalkerIsWaitedForAndToldWhereInPartyChat() {
        lobby.citizen(1, "Lia", 25);
        lobby.citizen(2, "Mira", 22);
        lobby.citizen(3, "Pip", 28);
        Character ann = onAnotherMap(lobby.human(10, "Ann", 24));
        quests.tick(0L);
        long invitedAt = lobby.config.PQ_FORM_PATIENCE_MS - 2 * MINUTE;
        whispers.onWhisper(ann, "Lia", "me", invitedAt);
        lobby.parties.accept(ann);

        quests.tick(invitedAt + 10_000L);
        assertEquals(1, lobby.count("pq.where"), "she hears where the party waits, once");
        quests.tick(invitedAt + 20_000L);
        assertEquals(1, lobby.count("pq.where"));

        quests.tick(lobby.config.PQ_FORM_PATIENCE_MS + MINUTE);
        assertTrue(door.admitted.isEmpty(), "never in without her at the npc");
        assertEquals(0, lobby.count("pq.giveup"), "she is still within WHISPER_JOIN_COMING_MS of her invite");

        quests.tick(invitedAt + lobby.config.WHISPER_JOIN_COMING_MS);
        assertEquals(1, lobby.count("pq.giveup"), "she never came: the party gives up after all");
    }

    @Test
    void aWhisperBackAnswersTheCitizensOwnWhisperNeverBusy() {
        Character lia = lobby.elsewhere(1, "Lia", 25, Activity.TRAIN, 101_000_000);
        Character ann = onAnotherMap(lobby.human(10, "Ann", 24));
        List<String> rivalReplies = new ArrayList<>();
        whispers.onReply("rival", (citizen, human, text) -> rivalReplies.add(citizen.name() + ":" + text));

        whispers.opened(lia.getId(), ann.getId(), CitizenWhispers.NEED, 0L);
        whispers.onWhisper(ann, "Lia", "yes i have it", MINUTE);
        whispers.opened(lia.getId(), ann.getId(), CitizenWhispers.CHAT, 2 * MINUTE);
        whispers.onWhisper(ann, "Lia", "ty!!", 3 * MINUTE);
        whispers.onWhisper(ann, "Lia", "who r u?", 4 * MINUTE);
        whispers.opened(lia.getId(), ann.getId(), "rival", 5 * MINUTE);
        whispers.onWhisper(ann, "Lia", "gg", 6 * MINUTE);

        assertEquals(List.of("whisper.need.yes -> Ann", "whisper.ack.thanks -> Ann", "whisper.ack.question -> Ann"), answers);
        assertEquals(List.of("Lia:gg"), rivalReplies, "a registered topic answers its own threads");
    }

    @Test
    void outOfTheBlueItAnswersByWhatItIsDoingOncePerGapAndNotWhileAfk() {
        lobby.elsewhere(1, "Lia", 25, Activity.TRAIN, 101_000_000);
        Character ann = onAnotherMap(lobby.human(10, "Ann", 24));
        Character bo = onAnotherMap(lobby.human(11, "Bo", 24));

        assertTrue(whispers.onWhisper(ann, "Lia", "wanna party?", 0L));
        assertTrue(whispers.onWhisper(ann, "Lia", "hello??", MINUTE));
        assertTrue(whispers.onWhisper(bo, "Lia", "hi", MINUTE));
        lobby.sessions.get(1).afkUntilMs = 10 * MINUTE;
        assertTrue(whispers.onWhisper(ann, "Lia", "hi", 5 * MINUTE));

        assertEquals(List.of("whisper.training -> Ann", "whisper.hi -> Bo"), answers);
    }

    @Test
    void switchedOffWhispersAreIgnoredAsBefore() {
        lobby.citizen(1, "Lia", 25);
        lobby.citizen(2, "Mira", 22);
        quests.tick(0L);
        Character ann = onAnotherMap(lobby.human(10, "Ann", 24));
        lobby.config.CITIZEN_WHISPERS_ENABLED = false;

        assertFalse(whispers.onWhisper(ann, "Lia", "me", MINUTE));
        assertFalse(whispers.onWhisper(ann, "Nobody", "me", MINUTE));

        assertTrue(answers.isEmpty());
        assertFalse(lobby.parties.hasPendingPartyInvite(ann.getId()));
    }

    @Test
    void aShortPartysLeaderMegaphonesOnceWithARealMegaphone() {
        Character lia = lobby.citizen(1, "Lia", 25);
        lobby.citizen(2, "Mira", 22);
        when(lia.getItemQuantity(ChatterEngine.SUPER_MEGAPHONE, false)).thenReturn(1);
        quests.tick(0L);

        quests.tick(lobby.config.LF_SMEGA_AFTER_MS - 10_000L);
        assertTrue(megaphones.isEmpty(), "not before LF_SMEGA_AFTER_MS");

        capOpen = false;
        quests.tick(lobby.config.LF_SMEGA_AFTER_MS);
        assertTrue(megaphones.isEmpty(), "the cap is full: try again next tick");

        capOpen = true;
        quests.tick(lobby.config.LF_SMEGA_AFTER_MS + 10_000L);
        quests.tick(lobby.config.LF_SMEGA_AFTER_MS + 120_000L);

        assertEquals(List.of("pq.smega need=2 item=true"), megaphones, "one per party, using a Super Megaphone up");
        assertTrue(lobby.session(lia).journal.summary(300).contains("megaphoned for its kpq party"));
    }

    @Test
    void noMegaphoneWithoutOneInTheBagOrWithWhispersOff() {
        Character lia = lobby.citizen(1, "Lia", 25);
        lobby.citizen(2, "Mira", 22);
        quests.tick(0L);
        quests.tick(lobby.config.LF_SMEGA_AFTER_MS);
        assertTrue(megaphones.isEmpty(), "it carries none");

        when(lia.getItemQuantity(ChatterEngine.SUPER_MEGAPHONE, false)).thenReturn(2);
        lobby.config.CITIZEN_WHISPERS_ENABLED = false;
        quests.tick(lobby.config.LF_SMEGA_AFTER_MS + 10_000L);
        assertTrue(megaphones.isEmpty(), "\"whisper me\" with whispers off would be a lie");
    }
}
