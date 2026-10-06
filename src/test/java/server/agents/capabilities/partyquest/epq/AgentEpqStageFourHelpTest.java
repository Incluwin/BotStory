package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import server.life.EpqPoisonFlowerCaptureService;
import server.life.Monster;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Point;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EPQ base fix 8: stage-4 Agents help catch the last flowers. An Agent whose own flowers are caught takes the nearest
 * live flower of any owner instead of calling a respawn that can never spawn (930000400's flowers are one-shot); only
 * with no live flower at all does today's assist run.
 */
class AgentEpqStageFourHelpTest {
    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.STAGE_FOUR_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.STAGE_FOUR_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.STAGE_FOUR_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.STAGE_FOUR_MAP);

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void ownFlowersFirstThenAnyNearestThenNone() {
        record Flower(String name, int owner, int x) { }
        Flower a = new Flower("a", 2, 900);
        Flower b = new Flower("b", 3, 100);
        Flower c = new Flower("c", 2, 300);
        Flower d = new Flower("d", 4, 50);
        List<Flower> live = List.of(a, b, c, d);

        assertEquals(c, AgentEpqParticipationPolicy.captureTarget(live, Flower::owner, 2, f -> (double) f.x() * f.x()),
                "its own nearest, though another owner's is nearer");
        assertEquals(d, AgentEpqParticipationPolicy.captureTarget(live, Flower::owner, 5, f -> (double) f.x() * f.x()),
                "none of its own: the nearest of any owner");
        assertEquals(d, AgentEpqParticipationPolicy.captureTarget(List.of(b, d), Flower::owner, 2,
                f -> (double) f.x() * f.x()));
        assertNull(AgentEpqParticipationPolicy.captureTarget(List.<Flower>of(), Flower::owner, 2, f -> 0.0));
    }

    /**
     * Helpers go for the nearest live flower of any owner, so an owner and a helper on two tick threads can throw at
     * the same ready flower. The stock catch checks, then kills and then hands out the marble with no lock, so both
     * would spend a purifier and both get a Monster Marble. One Agent throws at a given flower at a time; the claim
     * ends with the throw (also when it fails or throws), and the next throw finds the flower gone.
     */
    @Test
    void twoAgentsNeverCatchOneFlowerAtOnce() throws Exception {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.STAGE_FOUR, ben, mira, tobi, una);
        Monster flower = flower(100);
        when(run.map.getMonsterByOid(flower.getObjectId())).thenReturn(flower);
        AtomicInteger throwsMade = new AtomicInteger();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BiFunction<Character, Monster, EpqPoisonFlowerCaptureService.Result> slowCatch = (agent, mob) -> {
            throwsMade.incrementAndGet();
            inside.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return EpqPoisonFlowerCaptureService.Result.CAPTURED;
        };
        BiFunction<Character, Monster, EpqPoisonFlowerCaptureService.Result> quickCatch = (agent, mob) -> {
            throwsMade.incrementAndGet();
            return EpqPoisonFlowerCaptureService.Result.CAPTURED;
        };

        AtomicBoolean ownerCaught = new AtomicBoolean();
        Thread owner = new Thread(() -> ownerCaught.set(AgentEpqCaptureRuntime.capture(mira, flower, slowCatch)));
        owner.start();
        assertTrue(inside.await(10, TimeUnit.SECONDS));
        AtomicBoolean helperCaught = new AtomicBoolean(true);
        Thread helper = new Thread(() -> helperCaught.set(AgentEpqCaptureRuntime.capture(tobi, flower, quickCatch)));
        helper.start();
        helper.join(10_000L);
        release.countDown();
        owner.join(10_000L);

        assertTrue(ownerCaught.get());
        assertFalse(helperCaught.get(), "the helper does not throw while the owner's throw is on the flower");
        assertEquals(1, throwsMade.get());

        assertTrue(AgentEpqCaptureRuntime.capture(tobi, flower, quickCatch), "the claim ended with the throw");
        assertEquals(2, throwsMade.get());
        assertThrows(IllegalStateException.class, () -> AgentEpqCaptureRuntime.capture(una, flower, (agent, mob) -> {
            throw new IllegalStateException("inventory unloading");
        }));
        assertTrue(AgentEpqCaptureRuntime.capture(una, flower, quickCatch), "a throw that threw ends its claim too");
        assertTrue(session.lastProgressAtMs() > 1_000L);
    }

    private Monster flower(int x) {
        Monster flower = run.monster(AgentEpqDefinition.POISON_FLOWER);
        when(flower.getMap()).thenReturn(run.map);
        when(flower.getPosition()).thenReturn(new Point(x, 0));
        return flower;
    }

    private int owner(AgentEpqSession session, Monster flower) {
        return AgentEpqParticipationPolicy.assignedAgent(session.seed(), flower.getObjectId(), List.of(2, 3, 4));
    }

    private Character helperFor(int owner) {
        return owner == mira.getId() ? tobi : mira;
    }

    @Test
    void anAgentWhoseFlowersAreCaughtHelpsWithAnothersInsteadOfCallingARespawn() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.STAGE_FOUR, ben, mira, tobi, una);
        Monster last = flower(600);
        Character helper = helperFor(owner(session, last));
        when(run.actions.itemCount(helper, AgentEpqDefinition.PURIFICATION_MARBLE)).thenReturn(4);

        AgentEpqCoordinator.tickMember(session, run.entry(helper), helper, 2_000L);

        verify(run.actions).navigate(run.entry(helper), new Point(600, 0), true);
        verify(run.map, never()).instanceMapForceRespawn();
        assertEquals(Map.of(), session.rescueCounts());
    }

    @Test
    void aHelpersCaptureIsLoggedAsNotItsOwn() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.STAGE_FOUR, ben, mira, tobi, una);
        Monster last = flower(100);
        Character helper = helperFor(owner(session, last));
        when(run.actions.itemCount(helper, AgentEpqDefinition.PURIFICATION_MARBLE)).thenReturn(4);
        when(run.actions.itemCount(helper, AgentEpqDefinition.MONSTER_MARBLE)).thenReturn(19);
        try (MockedStatic<AgentEpqCaptureRuntime> capture = mockStatic(AgentEpqCaptureRuntime.class);
             EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            capture.when(() -> AgentEpqCaptureRuntime.ready(last)).thenReturn(true);
            capture.when(() -> AgentEpqCaptureRuntime.capture(helper, last)).thenReturn(true);

            AgentEpqCoordinator.tickMember(session, run.entry(helper), helper, 2_000L);

            assertEquals(List.of("EPQ capture: session=" + session.sessionId() + " member=" + helper.getName()
                            + " flower=" + last.getObjectId() + " own=false marbles=19 map=930000400"),
                    lines.startingWith("EPQ capture:"));
        }
    }

    @Test
    void withNoLiveFlowerAtAllTodaysAssistStillRuns() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.STAGE_FOUR, ben, mira, tobi, una);
        Monster caught = flower(100);
        when(caught.isAlive()).thenReturn(false);
        when(run.actions.itemCount(mira, AgentEpqDefinition.PURIFICATION_MARBLE)).thenReturn(1);

        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);

        verify(run.map).instanceMapForceRespawn();
        assertEquals(1, session.rescueCounts().get("respawn-flowers"));
    }

    @Test
    void theStageFourFlowersAreOneShot() throws Exception {
        Element map = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(Path.of("wz/Map.wz/Map/Map9/930000400.img.xml").toFile()).getDocumentElement();
        Element life = null;
        for (Node node = map.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && "life".equals(element.getAttribute("name"))) life = element;
        }
        assertTrue(life != null);
        int flowers = 0;
        int npcs = 0;
        for (Node node = life.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element entry)) continue;
            String type = value(entry, "type");
            String id = value(entry, "id");
            if ("m".equals(type)) {
                assertEquals("9300175", id, "every mob is a Poison Flower");
                assertEquals("-1", value(entry, "mobTime"), "and never respawns");
                flowers++;
            } else {
                assertEquals("n", type);
                assertEquals("2133001", id, "the only NPC is Ellin");
                npcs++;
            }
        }
        assertEquals(20, flowers);
        assertEquals(1, npcs);
    }

    private static String value(Element entry, String name) {
        for (Node node = entry.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getAttribute("name"))) {
                return element.getAttribute("value");
            }
        }
        return null;
    }
}
