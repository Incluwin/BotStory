package server.living.population.needs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The needs board's PARTY page names the leader to whisper only while players' whispers reach citizens (F5c). */
class NeedScreensPartyPageTest {
    @Test
    void thePartyPageSaysWhisperOnlyWhileWhispersAreOn() {
        assertEquals("#eLia#n is forming a party at #bKerning City#k and wants 1 more. Go there, or whisper #eLia#n for an"
                        + " invite; the leader invites people whose level fits.",
                NeedScreens.partyPage("#eLia#n", "Kerning City", "1 more", "Lia"));
        assertEquals("#eLia#n is forming a party at #bKerning City#k and wants 1 more. Go there; the leader invites people"
                        + " whose level fits.",
                NeedScreens.partyPage("#eLia#n", "Kerning City", "1 more", null));
    }
}
