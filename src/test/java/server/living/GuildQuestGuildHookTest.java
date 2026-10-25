package server.living;

import client.Character;
import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.living.roster.CompanionDirectory;
import server.living.roster.RosterService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * GPQ plan decision 5: LivingWorldHooks.guildQuestGuild, which GuildQuest.js and Shuang ask. An owned companion counts
 * in its online owner's guild; a citizen, a human, a companion whose owner is offline or guildless, and everyone while
 * the switch leaves gpq out count in their own guild, as stock.
 */
class GuildQuestGuildHookTest {
    private static final int ANN = 10;
    private static final int OLWEN = 901;
    private static final int CITIZEN = 950;
    private static final int ANNS_GUILD = 77;

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingWorldConfig config = new LivingWorldConfig();
    private final CompanionDirectory directory = mock(CompanionDirectory.class);
    private final RosterService roster = mock(RosterService.class);
    private final Character ann = mock(Character.class);
    private MockedStatic<LivingWorld> living;

    @BeforeEach
    void annLeadsAGuild() {
        config.COMPANION_BOSS_ENABLED_TYPES = "cwkpq,gpq";
        when(world.config()).thenReturn(config);
        when(world.directory()).thenReturn(directory);
        when(world.roster()).thenReturn(roster);
        when(directory.ownerOfCharacter(OLWEN)).thenReturn(ANN);
        when(roster.onlineCharacter(ANN)).thenReturn(ann);
        when(ann.getGuildId()).thenReturn(ANNS_GUILD);
        living = mockStatic(LivingWorld.class);
        living.when(LivingWorld::get).thenReturn(world);
    }

    @AfterEach
    void close() {
        living.close();
    }

    @Test
    void anOwnedCompanionCountsInItsOwnersGuild() {
        assertEquals(ANNS_GUILD, LivingWorldHooks.guildQuestGuild(OLWEN, 0));
        assertEquals(ANNS_GUILD, LivingWorldHooks.guildQuestGuild(OLWEN, 31), "a hired citizen's own guild too");
    }

    @Test
    void everyoneElseCountsInTheirOwnGuild() {
        assertEquals(31, LivingWorldHooks.guildQuestGuild(CITIZEN, 31), "a citizen: its own");
        assertEquals(ANNS_GUILD, LivingWorldHooks.guildQuestGuild(ANN, ANNS_GUILD), "a human: their own");

        when(ann.getGuildId()).thenReturn(0);
        assertEquals(0, LivingWorldHooks.guildQuestGuild(OLWEN, 0), "a guildless owner gives none");
        when(roster.onlineCharacter(ANN)).thenReturn(null);
        assertEquals(0, LivingWorldHooks.guildQuestGuild(OLWEN, 0), "an offline owner gives none");
    }

    @Test
    void withTheSwitchOffItIsStock() {
        config.COMPANION_BOSS_ENABLED_TYPES = "cwkpq";
        assertEquals(0, LivingWorldHooks.guildQuestGuild(OLWEN, 0));

        living.when(LivingWorld::get).thenReturn(null);
        assertEquals(5, LivingWorldHooks.guildQuestGuild(OLWEN, 5), "no living world");
    }
}
