package server.agents.capabilities.townlife;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A TownLife checkpoint dropped by character id (its Agent not loaded) leaves its next load nothing to restore. */
class AgentTownLifeCheckpointRuntimeTest {
    /** No live character has it: the checkpoint directory is the working tree's own. */
    private static final int CHARACTER = 2_000_000_835;

    @Test
    void aCheckpointDroppedByCharacterIdIsNotRestoredByItsNextLoad() throws IOException {
        Path checkpoint = AgentTownLifeCheckpointRuntime.path(CHARACTER);
        Files.createDirectories(checkpoint.getParent());
        Files.writeString(checkpoint, "{}");
        try {
            AgentTownLifeCheckpointRuntime.delete(CHARACTER);

            assertFalse(Files.exists(checkpoint));
            Character agent = mock(Character.class);
            when(agent.getId()).thenReturn(CHARACTER);
            assertFalse(AgentTownLifeCheckpointRuntime.restore(new AgentRuntimeEntry(agent, null, null), agent, 0L));
            AgentTownLifeCheckpointRuntime.delete(CHARACTER); // none left: nothing happens
            AgentTownLifeCheckpointRuntime.delete(0);
        } finally {
            Files.deleteIfExists(checkpoint);
        }
    }
}
