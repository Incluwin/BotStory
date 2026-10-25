package server.agents.capabilities.build.profiles;

import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentApBuildProfileServiceTest {
    private final Map<String, AgentApBuildProfile> profiles = AgentApBuildProfileRepository.defaultRepository().all()
            .stream().collect(Collectors.toMap(AgentApBuildProfile::profileId, Function.identity()));

    @Test
    void catalogContainsAllIndependentLevelThirtyProfiles() {
        assertEquals(17, profiles.size()); // 11 Explorer, 6 Cygnus Knight and Aran
    }

    @Test
    void warriorProfilesApplyRequestedPerLevelRatiosAndCaps() {
        AgentApBuildProfile dex20 = profile("warrior-dex20-str-lv30-v1");
        AgentApBuildProfile dex40 = profile("warrior-dex40-str-lv30-v1");

        assertEquals(4, dex20.targetAtLevel(10));
        assertEquals(new AgentApBuildProfileService.Allocation(4, 1, 0, 0),
                AgentApBuildProfileService.allocation(dex20, 11, 5, 4));
        assertEquals(20, dex20.targetAtLevel(30));

        assertEquals(new AgentApBuildProfileService.Allocation(3, 2, 0, 0),
                AgentApBuildProfileService.allocation(dex40, 11, 5, 4));
        assertEquals(40, dex40.targetAtLevel(30));
    }

    @Test
    void levelFormulaProfilesReachRequestedLevelThirtyTargets() {
        assertEquals(30, profile("bowman-str30-dex-lv30-v1").targetAtLevel(30));
        assertEquals(4, profile("bowman-str4-dex-lv30-v1").targetAtLevel(30));
        assertEquals(60, profile("thief-dex60-luk-lv30-v1").targetAtLevel(30));
        assertEquals(25, profile("thief-dex25-luk-lv30-v1").targetAtLevel(30));
        assertEquals(33, profile("magician-luk33-int-lv30-v1").targetAtLevel(30));
        assertEquals(4, profile("magician-luk4-int-lv30-v1").targetAtLevel(30));
        assertEquals(30, profile("pirate-str30-dex-lv30-v1").targetAtLevel(30));
        assertEquals(20, profile("pirate-str20-dex-lv30-v1").targetAtLevel(30));
        assertEquals(30, profile("pirate-dex30-str-lv30-v1").targetAtLevel(30));
    }

    @Test
    void existingMandatoryStatFloorIsNeverReduced() {
        AgentApBuildProfile thief = profile("thief-dex60-luk-lv30-v1");
        AgentApBuildProfile pirate = profile("pirate-dex30-str-lv30-v1");

        assertEquals(new AgentApBuildProfileService.Allocation(0, 0, 0, 5),
                AgentApBuildProfileService.allocation(thief, 10, 5, 25));
        assertEquals(new AgentApBuildProfileService.Allocation(5, 0, 0, 0),
                AgentApBuildProfileService.allocation(pirate, 10, 5, 20));
    }

    @Test
    void autoAssignDeclinesAJobOutsideTheProfileFamilySoTheFallbackRuns() {
        Character warrior = warrior(29, 10, 121, 26);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(warrior, null, null);
        entry.apBuildProfileState().assign(profile("pirate-str20-dex-lv30-v1"));

        assertFalse(AgentApBuildProfileService.autoAssign(entry, warrior));
        verify(warrior, never()).assignStrDexIntLuk(anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void autoAssignKeepsSpendingPastTheLastProfiledLevelAtTheCappedTarget() {
        Character warrior = warrior(31, 20, 121, 26);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(warrior, null, null);
        entry.apBuildProfileState().assign(profile("warrior-dex40-str-lv30-v1"));

        assertTrue(AgentApBuildProfileService.autoAssign(entry, warrior));
        verify(warrior).assignStrDexIntLuk(6, 14, 0, 0);
    }

    private static Character warrior(int level, int remainingAp, int str, int dex) {
        Character agent = mock(Character.class);
        when(agent.getJob()).thenReturn(Job.WARRIOR);
        when(agent.getLevel()).thenReturn(level);
        when(agent.getRemainingAp()).thenReturn(remainingAp);
        when(agent.getStr()).thenReturn(str);
        when(agent.getDex()).thenReturn(dex);
        when(agent.assignStrDexIntLuk(anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(true);
        return agent;
    }

    private AgentApBuildProfile profile(String profileId) {
        return profiles.get(profileId);
    }
}
