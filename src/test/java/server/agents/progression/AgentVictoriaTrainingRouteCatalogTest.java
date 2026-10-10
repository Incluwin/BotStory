package server.agents.progression;

import org.junit.jupiter.api.Test;
import scripting.AbstractScriptManager;
import scripting.portal.PortalPlayerInteraction;
import scripting.portal.PortalScript;

import javax.script.Invocable;
import javax.script.ScriptEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class AgentVictoriaTrainingRouteCatalogTest {
    @Test
    void resolvesInstructorToTrainingAndReturnRoutesOnePortalAtATime() {
        assertEquals(102000000, AgentVictoriaTrainingRouteCatalog.nextHop(102000003, 102010000));
        assertEquals(102000003, AgentVictoriaTrainingRouteCatalog.nextHop(102000000, 102000003));
        assertEquals(101010000, AgentVictoriaTrainingRouteCatalog.nextHop(101000000, 101010101));
        assertEquals(100000200, AgentVictoriaTrainingRouteCatalog.nextHop(100000201, 100040000));
        assertEquals(103000000, AgentVictoriaTrainingRouteCatalog.nextHop(103000003, 103010000));
        assertEquals(120000100, AgentVictoriaTrainingRouteCatalog.nextHop(120000101, 120010000));
        assertEquals(120000100, AgentVictoriaTrainingRouteCatalog.nextHop(120000101, 100040003));
        assertEquals(100040000, AgentVictoriaTrainingRouteCatalog.nextHop(100040003, 120000101));
        assertEquals(26, AgentVictoriaTrainingRouteCatalog.scriptedPortalId(101000000, 101000003));
        assertEquals(9, AgentVictoriaTrainingRouteCatalog.scriptedPortalId(100000200, 100000201));
    }

    @Test
    void resolvesEveryInstructorToItsVerifiedPotionShopAndBack() {
        assertEquals(102000000, AgentVictoriaTrainingRouteCatalog.nextHop(102000003, 102000002));
        assertEquals(102000000, AgentVictoriaTrainingRouteCatalog.nextHop(102000002, 102000003));
        assertEquals(101000000, AgentVictoriaTrainingRouteCatalog.nextHop(101000003, 101000002));
        assertEquals(101000000, AgentVictoriaTrainingRouteCatalog.nextHop(101000002, 101000003));
        assertEquals(100000200, AgentVictoriaTrainingRouteCatalog.nextHop(100000201, 100000102));
        assertEquals(100000100, AgentVictoriaTrainingRouteCatalog.nextHop(100000102, 100000201));
        assertEquals(103000000, AgentVictoriaTrainingRouteCatalog.nextHop(103000003, 103000002));
        assertEquals(103000000, AgentVictoriaTrainingRouteCatalog.nextHop(103000002, 103000003));
        assertEquals(120000100, AgentVictoriaTrainingRouteCatalog.nextHop(120000101, 120000200));
        assertEquals(120000100, AgentVictoriaTrainingRouteCatalog.nextHop(120000200, 120000101));
    }

    @Test
    void kerningSubwayCampsWalkBackFromThePharmacy() {
        // Live 2026-10-09: a benched companion camped on Line 2 <Area 1> (103000200) walked to the Kerning City Pharmacy
        // (103000002) and logged "no return route from map 103000002". A player walks back with no ticket: the Subway
        // Ticketing Booth's in00 (portal 2, script subway_in2) warps to Line 1 <Area 1> with no check. The generated
        // graph drops every scripted portal, so the way in is the authored entry.
        assertEquals(5, AgentVictoriaTrainingRouteCatalog.distance(103000200, 103000002));
        assertEquals(5, AgentVictoriaTrainingRouteCatalog.distance(103000002, 103000200));
        assertEquals(2, AgentVictoriaTrainingRouteCatalog.scriptedPortalId(103000100, 103000101));
    }

    /** The router presses that portal with no ticket and no quest, so the script must check nothing. */
    @Test
    void theTicketBoothEntranceWarpsAnyoneToLine1() throws Exception {
        ScriptEngine engine = new Loader().load("portal/subway_in2.js");
        assertNotNull(engine, "the script evaluates");
        PortalPlayerInteraction pi = mock(PortalPlayerInteraction.class);

        assertTrue(((Invocable) engine).getInterface(PortalScript.class).enter(pi));

        verify(pi).playPortalSound();
        verify(pi).warp(103000101, 3);
        verifyNoMoreInteractions(pi);
    }

    /** Loads a script the way the portal script manager does (Graal.js with host access). */
    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    @Test
    void generalGraphConnectsTheLevel15SliceToTheRestOfVictoria() {
        assertNotNull(AgentVictoriaTrainingRouteCatalog.nextHop(104000000, 102010000));
    }

    @Test
    void routesForwardThroughMushroomCastleAfterLeavingSecretRoom() {
        assertEquals(106021100,
                AgentVictoriaTrainingRouteCatalog.nextHop(106021000, 106021400));
    }

    @Test
    void measuresQuestReturnDistanceInMapTransitions() {
        assertEquals(2, AgentVictoriaTrainingRouteCatalog.distance(102040000, 103000000));
        assertTrue(AgentVictoriaTrainingRouteCatalog.distance(100050000, 103000000) > 2);
        assertTrue(AgentQuestReturnScrollPolicy.qualifies(100050000, 103000000));
        assertFalse(AgentQuestReturnScrollPolicy.qualifies(102040000, 103000000));
    }

    @Test
    void selectsAUsefulTownScrollForQuestNpcsOutsideTown() {
        int huntMapId = 102040000;
        int completionMapId = 100050000;
        AgentVictoriaLevel15Catalog.ReturnScroll scroll =
                AgentQuestReturnScrollPolicy.selectScroll(huntMapId, completionMapId);

        assertNotNull(scroll);
        assertTrue(AgentVictoriaTrainingRouteCatalog.distance(scroll.townMapId(), completionMapId)
                < AgentVictoriaTrainingRouteCatalog.distance(huntMapId, completionMapId));
    }

    @Test
    void everyThiefTrainingInstanceCanExitTowardTheInstructor() {
        for (int instanceMapId : AgentVictoriaLevel15CatalogRepository.defaultRepository()
                .careerForFirstJob(400).trainingGround().instanceMapIds()) {
            assertEquals(103010000,
                    AgentVictoriaTrainingRouteCatalog.nextHop(instanceMapId, 103000003));
        }
    }
}
