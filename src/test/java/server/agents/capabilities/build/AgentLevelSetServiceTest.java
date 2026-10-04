package server.agents.capabilities.build;

import client.Character;
import client.Job;
import client.LevelSetPlan;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class AgentLevelSetServiceTest {
    private final Character bot = mock(Character.class);
    private final Character owner = mock(Character.class);
    private final AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, owner, null);

    /** Records every step by name; the set changes {@code bot}'s level and job as the real one would. */
    private final class Recording implements AgentLevelSetService.Steps {
        final List<String> steps = new ArrayList<>();
        String busy;
        LevelSetPlan.GearPlan gear = new LevelSetPlan.GearPlan(List.of(), null);
        boolean apProfile = true;
        String unequipped = "unequipped a sword";
        long clock = 1_000L;

        @Override
        public String busy(Character bot) {
            return busy;
        }

        @Override
        public LevelSetPlan.GearPlan gearPlan(Character bot, LevelSetPlan.Ok ok) {
            steps.add("gearPlan");
            return gear;
        }

        @Override
        public String unequip(Character bot, List<Short> slots) {
            steps.add("unequip " + slots);
            return unequipped;
        }

        @Override
        public LevelSetPlan.AdminLevelChange adminSetLevel(Character bot, LevelSetPlan.Ok ok) {
            steps.add("adminSetLevel");
            LevelSetPlan.AdminLevelChange change = new LevelSetPlan.AdminLevelChange(bot.getLevel(), bot.getJob(),
                    new LevelSetPlan.Stats(4, 4, 4, 4, 0), ok.level(), ok.job(), 0, 0, 0, List.of());
            when(bot.getLevel()).thenReturn(ok.level());
            when(bot.getJob()).thenReturn(ok.job());
            return change;
        }

        @Override
        public void assignOffline(Character bot) {
            steps.add("assignOffline");
        }

        @Override
        public void restoreOrAssign(AgentRuntimeEntry entry, long nowMs) {
            steps.add("restoreOrAssign");
        }

        @Override
        public boolean resetApToProfile(AgentRuntimeEntry entry, Character bot) {
            steps.add("resetApToProfile");
            return apProfile;
        }

        @Override
        public void autoAssignConfiguredAp(AgentRuntimeEntry entry, Character bot) {
            steps.add("autoAssignConfiguredAp");
        }

        @Override
        public void autoAssignSp(AgentRuntimeEntry entry, Character bot) {
            steps.add("autoAssignSp");
        }

        @Override
        public void setJobPromptSent(AgentRuntimeEntry entry, int level) {
            steps.add("setJobPromptSent " + level);
        }

        @Override
        public void unequipInfeasibleEquipped(Character bot) {
            steps.add("unequipInfeasibleEquipped");
        }

        @Override
        public void autoEquip(Character bot, Character owner) {
            steps.add("autoEquip");
        }

        @Override
        public void heal(Character bot) {
            steps.add("heal");
        }

        @Override
        public void checkBuildStatus(AgentRuntimeEntry entry, Character bot) {
            steps.add("checkBuildStatus");
        }

        @Override
        public void save(Character bot) {
            steps.add("save");
        }

        @Override
        public long nowMs() {
            clock += 7L;
            return clock;
        }
    }

    private AgentLevelSetService.Outcome apply(Recording steps, LevelSetPlan.Request request) {
        return AgentLevelSetService.apply(entry, bot, request, new LevelSetPlan.Companion(null, null), owner, 5_000L,
                steps);
    }

    @Test
    void aJobChangeRunsEveryStepInOrderWithTheChainProfilesBeforeTheBundle() {
        when(bot.getLevel()).thenReturn(30);
        when(bot.getJob()).thenReturn(Job.WARRIOR);
        Recording steps = new Recording();

        AgentLevelSetService.Outcome outcome = apply(steps, LevelSetPlan.Request.to(40, Job.FIGHTER));

        assertEquals(List.of("gearPlan", "adminSetLevel", "assignOffline", "restoreOrAssign", "resetApToProfile",
                "autoAssignSp", "setJobPromptSent 30", "unequipInfeasibleEquipped", "autoEquip", "heal",
                "checkBuildStatus", "save"), steps.steps);
        AgentLevelSetService.Applied applied = assertInstanceOf(AgentLevelSetService.Applied.class, outcome);
        assertEquals(40, applied.change().toLevel());
        assertEquals(7L, applied.elapsedMs());
    }

    @Test
    void theSameJobSkipsTheBundleAndAMissingApProfileFallsBackToTheConfiguredBuild() {
        when(bot.getLevel()).thenReturn(30);
        when(bot.getJob()).thenReturn(Job.FIGHTER);
        Recording steps = new Recording();
        steps.apProfile = false;

        apply(steps, LevelSetPlan.Request.to(45, null));

        assertEquals(List.of("gearPlan", "adminSetLevel", "assignOffline", "resetApToProfile",
                "autoAssignConfiguredAp", "autoAssignSp", "setJobPromptSent 30", "unequipInfeasibleEquipped",
                "autoEquip", "heal", "checkBuildStatus", "save"), steps.steps);
    }

    @Test
    void aSetTheAgentAlreadyReachedIsUnchanged() {
        when(bot.getLevel()).thenReturn(40);
        when(bot.getJob()).thenReturn(Job.FIGHTER);
        Recording steps = new Recording();

        assertEquals(new AgentLevelSetService.Unchanged(false), apply(steps, LevelSetPlan.Request.to(40, null)));
        assertEquals(new AgentLevelSetService.Unchanged(true),
                apply(steps, LevelSetPlan.Request.restoreTo(40, Job.FIGHTER)));
        assertEquals(List.of(), steps.steps);
    }

    @Test
    void aGearRefusalOrABusyAgentChangesNothing() {
        when(bot.getLevel()).thenReturn(40);
        when(bot.getJob()).thenReturn(Job.FIGHTER);
        Recording steps = new Recording();
        steps.gear = new LevelSetPlan.GearPlan(List.of((short) -11), "no room");

        assertEquals(new AgentLevelSetService.Refused("no room"), apply(steps, LevelSetPlan.Request.to(20, null)));
        assertEquals(List.of("gearPlan"), steps.steps);

        Recording busy = new Recording();
        busy.busy = "Yara is in a party quest.";
        assertEquals(new AgentLevelSetService.Refused("Yara is in a party quest."),
                apply(busy, LevelSetPlan.Request.to(20, null)));
        assertEquals(List.of(), busy.steps);
    }

    @Test
    void theGearTheLevelForbidsComesOffBeforeTheSetAndBeforeAnythingElseIsTakenOff() {
        when(bot.getLevel()).thenReturn(40);
        when(bot.getJob()).thenReturn(Job.FIGHTER);
        Recording steps = new Recording();
        steps.gear = new LevelSetPlan.GearPlan(List.of((short) -11, (short) -5), null);

        apply(steps, LevelSetPlan.Request.to(20, null));

        assertEquals(List.of("gearPlan", "unequip [-11, -5]", "adminSetLevel", "assignOffline", "restoreOrAssign",
                "resetApToProfile", "autoAssignSp", "setJobPromptSent 10", "unequipInfeasibleEquipped", "autoEquip",
                "heal", "checkBuildStatus", "save"), steps.steps);
    }

    @Test
    void gearThatCouldNotComeOffRefusesTheSet() {
        when(bot.getLevel()).thenReturn(40);
        when(bot.getJob()).thenReturn(Job.FIGHTER);
        Recording steps = new Recording();
        steps.gear = new LevelSetPlan.GearPlan(List.of((short) -11), null);
        steps.unequipped = "equip bag full";

        AgentLevelSetService.Outcome outcome = apply(steps, LevelSetPlan.Request.to(20, null));

        assertInstanceOf(AgentLevelSetService.Refused.class, outcome);
        assertEquals(List.of("gearPlan", "unequip [-11]"), steps.steps);
    }

    @Test
    void theLiveBusyCheckRefusesADownAgentOneInAnEventInstanceAndOneInAPartyQuest() {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(901);
        when(agent.getName()).thenReturn("Yara");
        assertEquals("Yara is down.", AgentLevelSetService.LIVE.busy(agent));

        when(agent.isAlive()).thenReturn(true);
        when(agent.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        assertEquals("Yara is in an event instance.", AgentLevelSetService.LIVE.busy(agent));

        when(agent.getEventInstance()).thenReturn(null);
        try (MockedStatic<AgentPartyQuestEngagementRegistry> engagements =
                     mockStatic(AgentPartyQuestEngagementRegistry.class)) {
            engagements.when(() -> AgentPartyQuestEngagementRegistry.forMember(901))
                    .thenReturn(mock(AgentPartyQuestEngagement.class));
            assertEquals("Yara is in a party quest.", AgentLevelSetService.LIVE.busy(agent));
        }
        assertNull(AgentLevelSetService.LIVE.busy(agent));
    }

    @Test
    void theFamilyRuleIsCheckedAgainOnTheMailbox() {
        when(bot.getLevel()).thenReturn(30);
        when(bot.getJob()).thenReturn(Job.WARRIOR);
        Recording steps = new Recording();

        assertInstanceOf(AgentLevelSetService.Refused.class, apply(steps, LevelSetPlan.Request.to(40, Job.HUNTER)));
        assertEquals(List.of(), steps.steps);
    }

    @Test
    void lastKnownLevelIsLeftForTheAgentsOwnTick() {
        when(bot.getLevel()).thenReturn(30);
        when(bot.getJob()).thenReturn(Job.WARRIOR);
        AgentBuildStateRuntime.setLastKnownLevel(entry, 30);

        apply(new Recording(), LevelSetPlan.Request.to(40, Job.FIGHTER));

        assertEquals(30, AgentBuildStateRuntime.lastKnownLevel(entry));
    }

    @Test
    void thePromptTierIsTheAdvancementBehindTheJob() {
        assertEquals(0, AgentLevelSetService.promptTier(Job.BEGINNER));
        assertEquals(10, AgentLevelSetService.promptTier(Job.WARRIOR));
        assertEquals(30, AgentLevelSetService.promptTier(Job.FIGHTER));
        assertEquals(70, AgentLevelSetService.promptTier(Job.CRUSADER));
        assertEquals(120, AgentLevelSetService.promptTier(Job.HERO));
    }
}
