package server.living.story;

import client.Character;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.life.MonsterListener;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The private copy the crew camp stands on (docs/WORLD_SYSTEMS_PLAN.md N7.1, DuelGround with a listener): whoever leaves
 * it on its own (a portal, a scroll, a disconnect) is told to the listener, and closing it puts everyone still inside
 * back on the channel's own map at the very spot they stood on before, then lets the copy go.
 */
class DuelGroundCampTest {
    private static final int FIELD = 104010001;
    private static final int CAMP = 220000306;
    private static final int TOWN = 104000000;

    private final MapleMap field = mock(MapleMap.class);
    private final MapleMap copy = mock(MapleMap.class);
    private final FakeHands hands = new FakeHands();

    private Character at(int id, Point where) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getMapId()).thenReturn(FIELD);
        when(chr.getPosition()).thenReturn(where);
        when(chr.getMap()).thenReturn(field);
        when(chr.getClient()).thenReturn(mock(client.Client.class));
        return chr;
    }

    @Test
    void closingPutsEveryoneBackOnTheSpotTheyStoodOnAndLetsTheCopyGo() {
        Character ann = at(10, new Point(-312, 274));
        Character mira = at(900, new Point(-280, 274));
        DuelGround ground = DuelGround.open(hands, ann, CAMP, TOWN, chr -> { });

        assertEquals(List.of(CAMP + " exit " + TOWN), hands.opened, "a copy of the camp map whose logouts save the town");
        ground.enter(ann, new Point(0, 145));
        ground.enter(mira, new Point(0, 145));
        when(ann.getMap()).thenReturn(copy);
        when(mira.getMap()).thenReturn(copy);
        ground.close(Set.of(900));

        assertEquals(List.of("10 -> " + CAMP + " (0,145)", "900 -> " + CAMP + " (0,145)"), hands.warps.subList(0, 2));
        assertEquals(Set.of("10 -> " + FIELD + " (-312,274)", "900 -> " + FIELD + " (-280,274)"),
                Set.copyOf(hands.warps.subList(2, 4)), "each back on the very spot it stood on");
        assertEquals(4, hands.warps.size());
        assertEquals(List.of(900), hands.followed, "the companion follows its owner again");
        assertTrue(hands.stage.ended);
    }

    @Test
    void whoeverLeavesOnTheirOwnIsToldAndNotWarpedBackAgain() {
        Character ann = at(10, new Point(-312, 274));
        List<Integer> left = new ArrayList<>();
        DuelGround ground = DuelGround.open(hands, ann, CAMP, TOWN, chr -> left.add(chr.getId()));
        ground.enter(ann, new Point(0, 145));

        hands.exits.disconnected(ann);

        assertEquals(List.of(10), left, "a disconnect is heard");
        assertTrue(ground.left(10));
        ground.close(Set.of());
        assertEquals(1, hands.warps.size(), "only the way in: the one who left is not warped from wherever they are now");
        assertTrue(hands.stage.ended);
        assertSame(copy, ground.map());
    }

    private final class FakeHands implements Stagehand {
        final List<String> opened = new ArrayList<>();
        final List<String> warps = new ArrayList<>();
        final List<Integer> followed = new ArrayList<>();
        SceneInstance.Exits exits;
        final FakeStage stage = new FakeStage();

        @Override
        public Stage open(Character owner, int mapId, int exitMapId, SceneInstance.Exits exits) {
            opened.add(mapId + " exit " + exitMapId);
            this.exits = exits;
            return stage;
        }

        @Override
        public Monster spawn(Stage stage, int mobId, Point at, Function<Monster, MonsterListener> listener) {
            return null;
        }

        @Override
        public void say(Character speaker, String line) {
        }

        @Override
        public void warp(Character chr, MapleMap to, Point at) {
            warps.add(chr.getId() + " -> " + (to == copy ? CAMP : FIELD) + " (" + at.x + "," + at.y + ")");
        }

        @Override
        public MapleMap channelMap(Character owner, int mapId) {
            return field;
        }

        @Override
        public void hunt(Character bot) {
        }

        @Override
        public void follow(Character bot) {
            followed.add(bot.getId());
        }
    }

    private final class FakeStage implements Stagehand.Stage {
        boolean ended;

        @Override
        public MapleMap map() {
            return copy;
        }

        @Override
        public void register(Character chr) {
        }

        @Override
        public void unregister(Character chr) {
        }

        @Override
        public void end() {
            ended = true;
        }

        @Override
        public boolean ended() {
            return ended;
        }
    }
}
