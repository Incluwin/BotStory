package server.living.probe;

import client.BotClient;
import client.Character;
import client.Client;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import net.packet.Packet;
import net.server.coordinator.world.InviteCoordinator;
import net.server.coordinator.world.InviteCoordinator.InviteType;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import scripting.npc.NPCScriptManager;
import scripting.quest.QuestScriptManager;
import server.ItemInformationProvider;
import server.TimerManager;
import server.life.PlayerNPC;
import server.life.PlayerNPCFactory;
import server.living.resident.LivingTalk;
import tools.PacketCreator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code !companionprobe}: the GM6 harness for the P0 live client probes in
 * docs/BOT_MANAGEMENT_PLAN.md (P0 f). It sends the stock packets a probe needs (a party invite,
 * NPC windows with explicit speaker bytes, an imitated-NPC look) and turns on
 * {@link CompanionProbeObserver} so the client's answers show up in chat and in the log.
 *
 * <p>Probe windows have no NPC conversation behind them; {@link #answerOrphanTalk} re-enables the
 * GM's actions when the client answers one.</p>
 */
public final class CompanionProbeService {
    public static final int DEFAULT_ITEM_ID = 2430011;
    public static final int DEFAULT_NPC_ID = 9010000;
    /**
     * Imitated-NPC template ids. All 5600 ids here have an Npc.wz image with {@code info/imitate=1}
     * in the server wz and in the MapleCommons client, whose String.wz also names them (checked
     * 2026-09-27; the clean v83 client only has blocks of 9901000-9901909). An id with a String.wz
     * name but no Npc.wz image (the client's 9999999 "test") crashes the v83 client, and a
     * non-imitate template has no avatar to draw, so imitate refuses anything outside this range.
     * The Hall of Fame player NPCs take their script ids from the low end of each 100-id branch.
     */
    public static final int IMITATE_MIN_NPC_ID = 9901000;
    public static final int IMITATE_MAX_NPC_ID = 9906599;
    private static final Set<Integer> TALK_TYPES = Set.of(0, 1, 4, 12); // OK, Yes/No, menu, Accept/Decline
    private static final long REFRESH_DELAY_MS = 2000;
    private static final int ECHO = 6; // light blue; pink is kept for the observed packets
    private static final Set<Integer> OPEN_WINDOWS = ConcurrentHashMap.newKeySet();

    private CompanionProbeService() {
    }

    public sealed interface Request permits Help, Log, Invite, Talk, Refresh, Imitate, GiveItem, Status {
    }

    /** Print the usage; {@code problem} is null when no arguments were given. */
    public record Help(String problem) implements Request {
    }

    public record Log(boolean on) implements Request {
    }

    public record Invite(String name) implements Request {
    }

    public record Talk(int speaker, int npcId, int msgType) implements Request {
    }

    public record Refresh(int npcId) implements Request {
    }

    public record Imitate(String name, int npcId) implements Request {
    }

    public record GiveItem(int itemId) implements Request {
    }

    public record Status() implements Request {
    }

    public static List<String> usage() {
        return List.of(
                "!companionprobe log on|off - echo every packet your client sends (pink = probe opcodes)",
                "!companionprobe invite <name> - party invite from <name>'s real party (probes 1, 12)",
                "!companionprobe talk <speakerByte> [npcId=" + DEFAULT_NPC_ID + "] [msgType 0|1|4|12] (probe 5)",
                "!companionprobe refresh [npcId] - re-send text 2 s later into the open window (probe 6)",
                "!companionprobe imitate <name> <npcId " + IMITATE_MIN_NPC_ID + "-" + IMITATE_MAX_NPC_ID + "> (probe 4)",
                "!companionprobe item [itemId=" + DEFAULT_ITEM_ID + "] - one item to bind to a hotkey (probe 2)",
                "!companionprobe status - your party and the probe hints");
    }

    public static Request parse(String[] params) {
        if (params == null || params.length == 0) {
            return new Help(null);
        }
        String verb = params[0].toLowerCase(Locale.ROOT);
        return switch (verb) {
            case "log" -> {
                String mode = params.length > 1 ? params[1].toLowerCase(Locale.ROOT) : "";
                yield switch (mode) {
                    case "on" -> new Log(true);
                    case "off" -> new Log(false);
                    default -> new Help("Usage: log on|off");
                };
            }
            case "invite" -> params.length > 1 ? new Invite(params[1]) : new Help("Usage: invite <name>");
            case "talk" -> {
                Integer speaker = params.length > 1 ? number(params[1]) : null;
                Integer npcId = params.length > 2 ? number(params[2]) : Integer.valueOf(DEFAULT_NPC_ID);
                Integer msgType = params.length > 3 ? number(params[3]) : Integer.valueOf(0);
                if (speaker == null || speaker < 0 || speaker > 255) {
                    yield new Help("Usage: talk <speakerByte 0-255> [npcId] [msgType]");
                }
                if (npcId == null || npcId <= 0) {
                    yield new Help("npcId must be a positive number.");
                }
                if (msgType == null || !TALK_TYPES.contains(msgType)) {
                    yield new Help("msgType must be 0 (OK), 1 (Yes/No), 4 (menu) or 12 (Accept/Decline).");
                }
                yield new Talk(speaker, npcId, msgType);
            }
            case "refresh" -> {
                Integer npcId = params.length > 1 ? number(params[1]) : Integer.valueOf(DEFAULT_NPC_ID);
                yield npcId == null || npcId <= 0 ? new Help("Usage: refresh [npcId]") : new Refresh(npcId);
            }
            case "imitate" -> {
                Integer npcId = params.length > 2 ? number(params[2]) : null;
                if (npcId == null) {
                    yield new Help("Usage: imitate <characterName> <npcId>");
                }
                if (npcId < IMITATE_MIN_NPC_ID || npcId > IMITATE_MAX_NPC_ID) {
                    yield new Help("imitate needs an imitated-NPC id in " + IMITATE_MIN_NPC_ID + "-" + IMITATE_MAX_NPC_ID
                            + "; other ids have no avatar template and can crash the client.");
                }
                yield new Imitate(params[1], npcId);
            }
            case "item" -> {
                Integer itemId = params.length > 1 ? number(params[1]) : Integer.valueOf(DEFAULT_ITEM_ID);
                yield itemId == null || itemId <= 0 ? new Help("Usage: item [itemId]") : new GiveItem(itemId);
            }
            case "status" -> new Status();
            default -> new Help("Unknown subcommand: " + params[0]);
        };
    }

    public static void execute(Character gm, String[] params) {
        switch (parse(params)) {
            case Help help -> {
                if (help.problem() != null) {
                    gm.dropMessage(ECHO, help.problem());
                }
                usage().forEach(line -> gm.dropMessage(ECHO, line));
            }
            case Log log -> {
                CompanionProbeObserver.setObserving(gm.getId(), log.on());
                gm.dropMessage(ECHO, log.on()
                        ? "Probe log ON: each packet your client sends is echoed here and logged as [companion-probe]; "
                        + "probe opcodes are pink. Movement, regen and pong are skipped."
                        : "Probe log OFF.");
            }
            case Invite invite -> invite(gm, invite.name());
            case Talk talk -> talk(gm, talk);
            case Refresh refresh -> refresh(gm, refresh.npcId());
            case Imitate imitate -> imitate(gm, imitate.name(), imitate.npcId());
            case GiveItem give -> giveItem(gm, give.itemId());
            case Status ignored -> status(gm);
        }
    }

    /**
     * Called by {@code NPCMoreTalkHandler} for a reply that has no NPC conversation behind it. If it
     * answers one of this GM's probe windows, re-enables the client's actions. With no probe window
     * open anywhere it is a single empty-set check.
     */
    public static boolean answerOrphanTalk(Client client) {
        if (OPEN_WINDOWS.isEmpty()) {
            return false;
        }
        Character chr = client.getPlayer();
        if (chr == null || !OPEN_WINDOWS.remove(chr.getId())) {
            return false;
        }
        client.sendPacket(PacketCreator.enableActions());
        return true;
    }

    static void windowOpened(int characterId) {
        OPEN_WINDOWS.add(characterId);
    }

    static boolean windowOpen(int characterId) {
        return OPEN_WINDOWS.contains(characterId);
    }

    // ---- subcommands ---------------------------------------------------------------------

    private static void invite(Character gm, String name) {
        Character from = gm.getWorldServer().getPlayerStorage().getCharacterByName(name);
        if (from == null) {
            gm.dropMessage(ECHO, name + " is not online in this world.");
            return;
        }
        if (from.getId() == gm.getId()) {
            gm.dropMessage(ECHO, "The invite must come from another character.");
            return;
        }
        Party party = from.getParty();
        if (party == null) {
            gm.dropMessage(ECHO, from.getName() + " has no party, so there is no party id to invite you into.");
            return;
        }
        if (!InviteCoordinator.createInvite(InviteType.PARTY, from, party.getId(), gm.getId())) {
            gm.dropMessage(ECHO, "You already have a pending party invite; answer it or wait for it to expire (about 3 min).");
            return;
        }
        gm.sendPacket(PacketCreator.partyInvite(from));
        int size = party.getMembers().size();
        PartyCharacter leader = party.getLeader();
        gm.dropMessage(ECHO, "Sent a party invite from " + from.getName() + " (party " + party.getId() + ", " + size
                + "/6, leader " + (leader == null ? "?" : leader.getName()) + "). Accept sends PARTY_OPERATION op=3 "
                + "into the stock join; decline sends DENY_PARTY_REQUEST.");
        Party own = gm.getParty();
        if (own != null) {
            gm.dropMessage(ECHO, "You are in party " + own.getId() + ": probe 1 (does a partied client show it and send op=3?).");
        }
        if (size >= 6) {
            gm.dropMessage(ECHO, "That party is full: probe 12 (does accepting still send op=3?).");
        }
        warnIfNotObserving(gm);
    }

    private static void talk(Character gm, Talk talk) {
        if (!npcExists(gm, talk.npcId())) {
            return;
        }
        String text = "Probe 5: speaker byte " + talk.speaker() + " (0x" + Integer.toHexString(talk.speaker()).toUpperCase()
                + "), npc " + talk.npcId() + ", msgType " + talk.msgType() + ".\r\nWhose portrait is shown, and on which side?";
        if (talk.msgType() == 4) {
            text += "\r\n#L0#First option#l\r\n#L1#Second option#l";
        }
        openWindow(gm, PacketCreator.getNPCTalk(talk.npcId(), (byte) talk.msgType(), text,
                talk.msgType() == 0 ? "00 00" : "", (byte) talk.speaker()));
        gm.dropMessage(ECHO, "Opened a probe window (speaker " + talk.speaker() + ", npc " + talk.npcId() + ", msgType "
                + talk.msgType() + ").");
    }

    private static void refresh(Character gm, int npcId) {
        if (!npcExists(gm, npcId)) {
            return;
        }
        openWindow(gm, PacketCreator.getNPCTalk(npcId, (byte) 0,
                "Probe 6: FIRST text. Leave this window open; new text follows in 2 seconds.", "00 00", (byte) 0));
        gm.dropMessage(ECHO, "Opened a probe window; the second text follows in 2 s.");
        TimerManager.getInstance().schedule(() -> {
            if (!gm.isLoggedinWorld() || !windowOpen(gm.getId())) {
                gm.dropMessage(ECHO, "The probe window was answered before the refresh; run it again and wait 2 s.");
                return;
            }
            openWindow(gm, PacketCreator.getNPCTalk(npcId, (byte) 0,
                    "Probe 6: SECOND text, sent 2 s later. Same window, a new window, or nothing?", "00 00", (byte) 0));
            gm.dropMessage(ECHO, "Sent the second text into the open window.");
        }, REFRESH_DELAY_MS);
    }

    private static void imitate(Character gm, String name, int npcId) {
        Character target = gm.getWorldServer().getPlayerStorage().getCharacterByName(name);
        if (target == null) {
            gm.dropMessage(ECHO, name + " is not online in this world.");
            return;
        }
        if (!npcExists(gm, npcId)) {
            return;
        }
        Map<Short, Integer> equips = new LinkedHashMap<>();
        for (Item item : target.getInventory(InventoryType.EQUIPPED)) {
            int position = Math.abs(item.getPosition());
            if ((position < 12 && position > 0) || (position > 100 && position < 112)) { // as PlayerNPC stores them
                equips.put(item.getPosition(), item.getItemId());
            }
        }
        PlayerNPC look = new PlayerNPC(target.getName(), npcId, target.getFace(), target.getHair(), target.getGender(),
                (byte) target.getSkinColor().getId(), equips, 0, 0, 0, 0, 0, 0, 0);
        gm.sendPacket(PacketCreator.getPlayerNPC(look));
        openWindow(gm, PacketCreator.getNPCTalk(npcId, (byte) 0, "Probe 4: npc " + npcId + " imitating " + target.getName()
                + ".\r\nIs this portrait " + target.getName() + "'s own face, hair and outfit?", "00 00", (byte) 0));
        gm.dropMessage(ECHO, "Sent " + target.getName() + "'s look as npc " + npcId + " and opened its window. "
                + "Your client keeps that look on " + npcId + " until you relog.");
    }

    private static void giveItem(Character gm, int itemId) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        if (ii.getName(itemId) == null || !ii.hasData(itemId)) {
            gm.dropMessage(ECHO, "Item " + itemId + " does not exist.");
            return;
        }
        if (ItemConstants.isPet(itemId)) {
            gm.dropMessage(ECHO, "Pets need !item with an expiration.");
            return;
        }
        if (!gm.canHold(itemId)) {
            gm.dropMessage(ECHO, "No free slot for " + itemId + ".");
            return;
        }
        if (!InventoryManipulator.addById(gm.getClient(), itemId, (short) 1)) {
            gm.dropMessage(ECHO, "Could not add " + itemId + ".");
            return;
        }
        gm.dropMessage(ECHO, "Gave you one " + ii.getName(itemId) + " (" + itemId + "). Probe 2: drag it onto a hotkey "
                + "(CHANGE_KEYMAP shows the binding), then press the key (SCRIPTED_ITEM or USE_ITEM?).");
        warnIfNotObserving(gm);
    }

    private static void status(Character gm) {
        gm.dropMessage(ECHO, "Probe log " + (CompanionProbeObserver.isObserving(gm.getId()) ? "ON" : "OFF")
                + ", probe window " + (windowOpen(gm.getId()) ? "open" : "closed") + ".");
        Party party = gm.getParty();
        int size = 0;
        if (party == null) {
            gm.dropMessage(ECHO, "No party. Probe 1 needs you in one.");
        } else {
            size = party.getMembers().size();
            gm.dropMessage(ECHO, "Party " + party.getId() + ", " + size + "/6, leader id " + party.getLeaderId() + ":");
            for (PartyCharacter member : party.getMembers()) {
                Character online = gm.getWorldServer().getPlayerStorage().getCharacterById(member.getId());
                String kind = online == null ? "offline" : online.getClient() instanceof BotClient ? "bot" : "human";
                gm.dropMessage(ECHO, "  " + member.getId() + " " + member.getName() + " [" + kind + "]"
                        + (member.getId() == party.getLeaderId() ? " leader" : "")
                        + (member.isOnline() ? " ch" + member.getChannel() + " map " + member.getMapId() : ""));
            }
        }
        gm.dropMessage(ECHO, "Hints: 1) partied: invite <someone in another party>, accept -> op=3?  "
                + "2) item, bind it, press it -> SCRIPTED_ITEM?  4) imitate <companion> " + IMITATE_MAX_NPC_ID + "  "
                + "5) talk 0|1|2|3|4|5|8 [npc] [msgType]  6) refresh");
        gm.dropMessage(ECHO, "Hints: 7) double-click a bot or citizen, Party -> CHAR_INFO_REQUEST then op=4?  "
                + "8) buddy list, offline buddy, Party -> op=4?  13) trade a companion -> PLAYER_INTERACTION mode=0 type=3, mode=2");
        gm.dropMessage(ECHO, "Hint 12) at 6/6: party-window Invite and char-info Party -> op=4?; invite <member of a full party>, "
                + "accept -> op=3?" + (size >= 6 ? "  (you are at 6/6 now)" : ""));
        warnIfNotObserving(gm);
    }

    // ---- helpers -------------------------------------------------------------------------

    /** Closes whatever conversation the GM has open so the probe window's reply has none behind it. */
    private static void openWindow(Character gm, Packet talk) {
        Client client = gm.getClient();
        NPCScriptManager.getInstance().dispose(client);
        QuestScriptManager.getInstance().dispose(client);
        LivingTalk.closeFor(gm);
        windowOpened(gm.getId());
        gm.sendPacket(talk);
    }

    private static boolean npcExists(Character gm, int npcId) {
        if (PlayerNPCFactory.isExistentScriptid(npcId)) {
            return true;
        }
        gm.dropMessage(ECHO, "Npc.wz has no " + npcId + ".img; the client could crash on it. Pick an existing NPC id.");
        return false;
    }

    private static void warnIfNotObserving(Character gm) {
        if (!CompanionProbeObserver.isObserving(gm.getId())) {
            gm.dropMessage(ECHO, "Tip: !companionprobe log on to see what your client sends back.");
        }
    }

    private static Integer number(String text) {
        try {
            String trimmed = text.trim().toLowerCase(Locale.ROOT);
            return trimmed.startsWith("0x") ? Integer.parseInt(trimmed.substring(2), 16) : Integer.parseInt(trimmed);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }
}
