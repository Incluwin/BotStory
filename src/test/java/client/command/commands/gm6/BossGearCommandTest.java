package client.command.commands.gm6;

import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.living.LivingWorld;
import server.living.roster.CompanionGearTool;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code !bossgear <me|name> [preview|remove]} (GM 6): the line's forms, and a remove reaching CompanionGearTool.remove
 * with its reply to the GM (here with the living world off, where it runs on the command's thread).
 */
class BossGearCommandTest {
    private final Client client = mock(Client.class);
    private final Character gm = mock(Character.class);
    private final CompanionGearTool tool = mock(CompanionGearTool.class);

    @Test
    void theLineHasAGiftAPreviewAndARemoveForm() {
        assertEquals(new BossGearCommand.Line("Flint", false, false), BossGearCommand.parse(new String[] {"Flint"}));
        assertEquals(new BossGearCommand.Line("Flint", true, false),
                BossGearCommand.parse(new String[] {"Flint", "preview"}));
        assertEquals(new BossGearCommand.Line("Flint", false, true), BossGearCommand.parse(new String[] {"Flint", "remove"}));
        assertEquals(new BossGearCommand.Line("me", false, true), BossGearCommand.parse(new String[] {"me", "REMOVE"}));
        assertNull(BossGearCommand.parse(new String[] {}));
    }

    @Test
    void aSecondWordNamesTheBossWhoseAccuracyTheKitReaches() {
        // the tool refuses a key boss-rules.json lacks ("No boss rules for 'strip'"), so any other word is a boss key
        assertEquals(new BossGearCommand.Line("Flint", false, false, "showa"),
                BossGearCommand.parse(new String[] {"Flint", "showa"}));
        assertEquals(new BossGearCommand.Line("Flint", true, false, "showa"),
                BossGearCommand.parse(new String[] {"Flint", "showa", "preview"}));
        assertNull(BossGearCommand.parse(new String[] {"Flint", "showa", "remove"}), "a removal takes no boss");
        assertNull(BossGearCommand.parse(new String[] {"Flint", "remove", "preview"}));
        assertNull(BossGearCommand.parse(new String[] {"Flint", "a", "b", "c"}));
    }

    @Test
    void aRemoveRepliesWithWhatTheToolDid() {
        when(client.getPlayer()).thenReturn(gm);
        when(tool.remove(gm, "me")).thenReturn(CompletableFuture.completedFuture("2 boss pieces were taken from you."));

        try (MockedStatic<LivingWorld> living = mockStatic(LivingWorld.class)) {
            living.when(LivingWorld::get).thenReturn(null);
            new BossGearCommand(world -> tool).execute(client, new String[] {"me", "remove"});
        }

        verify(gm).yellowMessage("2 boss pieces were taken from you.");
        verify(tool, never()).use(gm, "me", false);
    }
}
