package server.living.resident;

import client.Character;
import client.Client;
import net.packet.InPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tiny NPC-window conversation for Residents: the client renders any NPC id we send, so a
 * Persona borrows a townsperson's portrait (plan 5.2) and quest text gets the familiar dialog
 * instead of chat bubbles. Pages first (Next / Prev), then one final prompt: OK, Yes/No or a
 * menu. Responses arrive through {@code NPCMoreTalkHandler} -> {@link #respond}.
 */
public final class LivingTalk {
    private static final Logger log = LoggerFactory.getLogger(LivingTalk.class);
    private static final Map<Integer, LivingTalk> OPEN = new ConcurrentHashMap<>();

    public record Option(String label, Runnable action) { }

    private enum Kind { OK, YES_NO, MENU }

    private final Character chr;
    private final int npcId;
    private final List<String> pages = new ArrayList<>();
    private int page;
    private Kind kind = Kind.OK;
    private String promptText;
    private Runnable onYes;
    private Runnable onNo;
    private final List<Option> options = new ArrayList<>();
    private boolean shown;

    private LivingTalk(Character chr, int npcId) {
        this.chr = chr;
        this.npcId = npcId;
    }

    /** Starts a new talk for the player, replacing any open one. */
    public static LivingTalk open(Character chr, int npcId) {
        LivingTalk previous = OPEN.remove(chr.getId());
        if (previous != null) {
            previous.release();
        }
        return new LivingTalk(chr, npcId);
    }

    public static boolean active(Client client) {
        Character player = client == null ? null : client.getPlayer();
        return player != null && OPEN.containsKey(player.getId());
    }

    public static void closeFor(Character chr) {
        LivingTalk talk = OPEN.remove(chr.getId());
        if (talk != null) {
            talk.release();
        }
    }

    // ---- building ------------------------------------------------------------------------

    public LivingTalk say(String text) {
        if (text != null && !text.isBlank()) {
            pages.add(text);
        }
        return this;
    }

    public LivingTalk ok(String text) {
        kind = Kind.OK;
        promptText = text;
        return this;
    }

    public LivingTalk yesNo(String text, Runnable yes, Runnable no) {
        kind = Kind.YES_NO;
        promptText = text;
        onYes = yes;
        onNo = no;
        return this;
    }

    public LivingTalk menu(String text, List<Option> choices) {
        kind = Kind.MENU;
        promptText = text;
        options.clear();
        options.addAll(choices);
        return this;
    }

    /** Sends the first page or the prompt when there are no pages. */
    public void show() {
        if (pages.isEmpty() && promptText == null) {
            return;
        }
        OPEN.put(chr.getId(), this);
        shown = true;
        page = 0;
        render();
    }

    private void render() {
        Client client = chr.getClient();
        if (client == null) {
            release();
            return;
        }
        if (page < pages.size()) {
            boolean hasPrev = page > 0;
            boolean hasNext = page + 1 < pages.size() || promptText != null;
            String end = hasPrev ? (hasNext ? "01 01" : "01 00") : (hasNext ? "00 01" : "00 00");
            client.sendPacket(PacketCreator.getNPCTalk(npcId, (byte) 0, pages.get(page), end, (byte) 0));
            return;
        }
        switch (kind) {
            case OK -> client.sendPacket(PacketCreator.getNPCTalk(npcId, (byte) 0, promptText == null ? "" : promptText,
                    pages.isEmpty() ? "00 00" : "01 00", (byte) 0));
            case YES_NO -> client.sendPacket(PacketCreator.getNPCTalk(npcId, (byte) 1, promptText, "", (byte) 0));
            case MENU -> {
                StringBuilder text = new StringBuilder(promptText == null ? "" : promptText);
                for (int index = 0; index < options.size(); index++) {
                    text.append("\r\n#L").append(index).append("#").append(options.get(index).label()).append("#l");
                }
                client.sendPacket(PacketCreator.getNPCTalk(npcId, (byte) 4, text.toString(), "", (byte) 0));
            }
        }
    }

    // ---- responses -----------------------------------------------------------------------

    /**
     * Called by the packet handler after {@code lastMsg} and {@code action} were read; reads the
     * rest of the packet itself. Returns true when the response belonged to an open talk.
     */
    public static boolean respond(Client client, byte lastMsg, byte action, InPacket packet) {
        Character player = client == null ? null : client.getPlayer();
        LivingTalk talk = player == null ? null : OPEN.get(player.getId());
        if (talk == null) {
            return false;
        }
        int selection = -1;
        if (lastMsg == 2) {
            if (action != 0 && packet.available() > 0) {
                packet.readString();
            }
        } else if (packet.available() >= 4) {
            selection = packet.readInt();
        } else if (packet.available() > 0) {
            selection = packet.readUnsignedByte();
        }
        try {
            talk.handle(lastMsg, action, selection);
        } catch (RuntimeException failure) {
            log.warn("living talk response failed for {}", player.getName(), failure);
            talk.close();
        }
        return true;
    }

    private void handle(byte lastMsg, byte action, int selection) {
        if (!shown) {
            close();
            return;
        }
        if (page < pages.size()) {
            if (action == 1) {
                page++;
                if (page < pages.size() || promptText != null) {
                    render();
                } else {
                    close();
                }
            } else if (action == 0 && page > 0 && lastMsg == 0) {
                page--;
                render();
            } else {
                close();
            }
            return;
        }
        switch (kind) {
            case OK -> close();
            case YES_NO -> {
                Runnable next = action == 1 ? onYes : onNo;
                close();
                if (next != null) {
                    next.run();
                }
            }
            case MENU -> {
                Option chosen = selection >= 0 && selection < options.size() ? options.get(selection) : null;
                close();
                if (chosen != null && chosen.action() != null) {
                    chosen.action().run();
                }
            }
        }
    }

    /** Ends the talk and re-enables the client's actions unless another talk was opened meanwhile. */
    public void close() {
        if (OPEN.remove(chr.getId(), this)) {
            release();
        }
    }

    private void release() {
        shown = false;
        Client client = chr.getClient();
        if (client != null && !OPEN.containsKey(chr.getId())) {
            client.sendPacket(PacketCreator.enableActions());
        }
    }
}
