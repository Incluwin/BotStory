package server.living.probe;

import client.BotClient;
import client.Character;
import client.Client;
import net.packet.InPacket;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapObject;

import java.util.HashSet;
import java.util.Set;

/**
 * {@code !companionprobe log on}: echoes every packet an observing GM's own client sends, with a
 * decoded summary for the probe opcodes ({@link CompanionProbePackets}). Hooked once at the channel
 * dispatch point ({@code Client.channelRead}); with nobody observing it costs one volatile read of
 * an empty set. It never consumes the packet: it decodes a copy of the unread payload.
 */
public final class CompanionProbeObserver {
    private static final Logger log = LoggerFactory.getLogger(CompanionProbeObserver.class);
    private static final int ECHO_DECODED = 5; // pink
    private static final int ECHO_OTHER = 6; // light blue
    private static volatile Set<Integer> observers = Set.of();

    private CompanionProbeObserver() {
    }

    /** Turns observation on or off for one character; returns whether anything changed. */
    public static synchronized boolean setObserving(int characterId, boolean on) {
        Set<Integer> next = new HashSet<>(observers);
        boolean changed = on ? next.add(characterId) : next.remove(characterId);
        observers = Set.copyOf(next);
        return changed;
    }

    public static boolean isObserving(int characterId) {
        return observers.contains(characterId);
    }

    /** Called with the opcode already read; the packet's reader position is left untouched. */
    public static void recordInbound(Client client, short opcode, InPacket packet) {
        Set<Integer> current = observers;
        if (current.isEmpty()) {
            return;
        }
        Character chr = client.getPlayer();
        if (chr == null || !current.contains(chr.getId())) {
            return;
        }
        int value = Short.toUnsignedInt(opcode);
        if (CompanionProbePackets.quiet(value)) {
            return;
        }
        try {
            String summary = CompanionProbePackets.summarize(value, packet.getBytes(), id -> nameOf(chr, id));
            String line = CompanionProbePackets.name(value) + (summary.isEmpty() ? "" : " " + summary);
            log.info("[companion-probe] {} {}", chr.getName(), line);
            chr.dropMessage(CompanionProbePackets.decoded(value) ? ECHO_DECODED : ECHO_OTHER, "[probe] " + line);
        } catch (RuntimeException failure) {
            log.warn("[companion-probe] could not echo opcode 0x{} for {}", Integer.toHexString(value), chr.getName(), failure);
        }
    }

    /** A character id (or a character's map object id) as "Name" or "Name[bot]", else null. */
    private static String nameOf(Character observer, int id) {
        Character target = null;
        if (observer.getMap() != null) {
            MapObject object = observer.getMap().getMapObject(id);
            if (object instanceof Character onMap) {
                target = onMap;
            }
        }
        World world = observer.getWorldServer();
        if (target == null && world != null) {
            target = world.getPlayerStorage().getCharacterById(id);
        }
        if (target == null) {
            return null;
        }
        return target.getClient() instanceof BotClient ? target.getName() + "[bot]" : target.getName();
    }
}
