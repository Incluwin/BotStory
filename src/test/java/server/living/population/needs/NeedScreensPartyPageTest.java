package server.living.population.needs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The needs board's PARTY page names the leader to whisper only while players' whispers reach citizens (F5c), on a line
 * of its own: the NPC window cuts a paragraph after two wrapped lines.
 */
class NeedScreensPartyPageTest {
    @Test
    void thePartyPageSaysWhisperOnlyWhileWhispersAreOn() {
        assertEquals("#eLia#n is forming a party at #bKerning City#k and wants 1 more.\r\nGo there, or whisper #eLia#n for"
                        + " an invite.\r\nThe leader invites people whose level fits.",
                NeedScreens.partyPage("#eLia#n", "Kerning City", "1 more", "Lia"));
        assertEquals("#eLia#n is forming a party at #bKerning City#k and wants 1 more.\r\nGo there.\r\nThe leader invites"
                        + " people whose level fits.",
                NeedScreens.partyPage("#eLia#n", "Kerning City", "1 more", null));
    }
}
