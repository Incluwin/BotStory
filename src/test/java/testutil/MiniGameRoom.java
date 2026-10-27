package testutil;

import client.Character;
import client.Client;
import net.packet.Packet;
import org.mockito.MockedStatic;
import server.maps.MapObject;
import server.maps.MapleMap;
import server.maps.MiniGame;
import tools.PacketCreator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * A town map and mocked players for minigame tests. Every {@link PacketCreator} call made on the test thread returns a
 * tag naming the method and its arguments ("addOmokBox(Ann,1,0)"), so a test reads who was sent what, in order, without
 * building character looks. Close it to restore PacketCreator.
 */
public final class MiniGameRoom implements AutoCloseable {
    public final MapleMap map = mock(MapleMap.class);
    /** Tags broadcast to the whole map, in order. */
    public final List<String> mapPackets = new ArrayList<>();
    private final List<MapObject> objects = new ArrayList<>();
    private final List<Player> players = new ArrayList<>();
    private final MockedStatic<PacketCreator> packets;

    public MiniGameRoom() {
        packets = mockStatic(PacketCreator.class, invocation -> {
            if (!Packet.class.isAssignableFrom(invocation.getMethod().getReturnType())) {
                return null;
            }
            StringJoiner args = new StringJoiner(",", invocation.getMethod().getName() + "(", ")");
            for (Object arg : invocation.getArguments()) {
                args.add(describe(arg));
            }
            return new Tag(args.toString());
        });
        lenient().when(map.getFieldLimit()).thenReturn(0);
        doAnswer(invocation -> {
            MapObject object = invocation.getArgument(0);
            object.setObjectId(500 + objects.size());
            objects.add(object);
            return null;
        }).when(map).addMapObject(any());
        lenient().when(map.getMapObject(anyInt())).thenAnswer(invocation -> {
            int oid = invocation.getArgument(0);
            return objects.stream().filter(object -> object.getObjectId() == oid).findFirst().orElse(null);
        });
        doAnswer(invocation -> {
            mapPackets.add(invocation.getArgument(0).toString());
            return null;
        }).when(map).broadcastMessage(any(Packet.class));
    }

    /** A living player on the map with every omok set and the match cards. */
    public Player player(String name, int id) {
        Player player = new Player(name, id, this);
        players.add(player);
        return player;
    }

    /** The minigames added to the map, oldest first. */
    public List<MiniGame> games() {
        return objects.stream().filter(MiniGame.class::isInstance).map(MiniGame.class::cast).toList();
    }

    /** Forgets every packet seen so far, the map's and each player's. */
    public void clearPackets() {
        mapPackets.clear();
        players.forEach(player -> player.packets.clear());
    }

    private String describe(Object arg) {
        if (arg instanceof Character chr) {
            return players.stream().filter(player -> player.chr == chr).map(player -> player.name).findFirst().orElse("?");
        }
        if (arg instanceof Client client) {
            return "client:" + players.stream().filter(player -> player.client == client).map(player -> player.name)
                    .findFirst().orElse("?");
        }
        if (arg instanceof MiniGame) {
            return "game";
        }
        return String.valueOf(arg);
    }

    @Override
    public void close() {
        packets.close();
    }

    /** A tagged packet: what PacketCreator was asked for. */
    public record Tag(String text) implements Packet {
        @Override
        public byte[] getBytes() {
            return text.getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /** A mocked player: its character, its client, and the tags it was sent (by either), in order. */
    public static final class Player {
        public final String name;
        public final Character chr = mock(Character.class);
        public final Client client = mock(Client.class);
        public final List<String> packets = new ArrayList<>();
        private final AtomicReference<MiniGame> game = new AtomicReference<>();

        Player(String name, int id, MiniGameRoom room) {
            this.name = name;
            lenient().when(chr.getName()).thenReturn(name);
            lenient().when(chr.getId()).thenReturn(id);
            lenient().when(chr.getMap()).thenReturn(room.map);
            lenient().when(chr.isAlive()).thenReturn(true);
            lenient().when(chr.haveItem(anyInt())).thenReturn(true);
            lenient().when(chr.getClient()).thenReturn(client);
            lenient().when(chr.getMiniGame()).thenAnswer(invocation -> game.get());
            doAnswer(invocation -> {
                game.set(invocation.getArgument(0));
                return null;
            }).when(chr).setMiniGame(any());
            doCallRealMethod().when(chr).closeMiniGame(anyBoolean());
            doAnswer(invocation -> {
                packets.add(invocation.getArgument(0).toString());
                return null;
            }).when(chr).sendPacket(any(Packet.class));
            lenient().when(client.getPlayer()).thenReturn(chr);
            lenient().when(client.tryacquireClient()).thenReturn(true);
            doAnswer(invocation -> {
                packets.add(invocation.getArgument(0).toString());
                return null;
            }).when(client).sendPacket(any(Packet.class));
        }

        public MiniGame game() {
            return game.get();
        }
    }
}
