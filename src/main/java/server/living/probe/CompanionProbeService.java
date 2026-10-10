package server.living.probe;

import client.BotClient;
import client.BuddyList;
import client.BuddylistEntry;
import client.Character;
import client.Client;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.NpcId;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code !companionprobe}: the GM6 harness for the P0 live client probes in
 * docs/BOT_MANAGEMENT_PLAN.md (P0 f). It sends the stock packets a probe needs (a party invite,
 * NPC windows with explicit speaker bytes, an imitated-NPC look) and turns on
 * {@link CompanionProbeObserver} so the client's answers show up in chat and in the log.
 *
 * <p>Probe windows have no NPC conversation behind them; {@link #answerOrphanTalk} re-enables the
 * GM's actions when the client answers one. {@link #forget} drops a GM's probe state when its
 * client disconnects.</p>
 */
public final class CompanionProbeService {
    public static final int DEFAULT_ITEM_ID = 2430011;
    public static final int DEFAULT_NPC_ID = 9010000;
    /**
     * Imitated-NPC template ids. All 5600 ids here have an Npc.wz image with {@code info/imitate=1}
     * in the server wz and in the BotStory client, whose String.wz also names them (checked
     * 2026-09-27; the clean v83 client only has blocks of 9901000-9901909). An id with a String.wz
     * name but no Npc.wz image (the client's 9999999 "test") crashes the v83 client, and a
     * non-imitate template has no avatar to draw, so imitate refuses anything outside this range.
     * The Hall of Fame player NPCs take their script ids from the low end of each 100-id branch.
     * The only other imitate template in the server wz is {@link NpcId#CUSTOM_DEV}; imitate refuses
     * it, and talk and refresh refuse every imitate template until imitate has sent the GM its look.
     */
    public static final int IMITATE_MIN_NPC_ID = 9901000;
    public static final int IMITATE_MAX_NPC_ID = 9906599;
    /** {@link Talk#templateId()} when no speaker template int is written. */
    public static final int NO_TEMPLATE = -1;
    private static final Set<Integer> TALK_TYPES = Set.of(0, 1, 4, 12); // OK, Yes/No, menu, Accept/Decline
    private static final int SPEAKER_TEMPLATE_BIT = 0x04; // later clients read a template int after the speaker byte
    private static final long REFRESH_DELAY_MS = 2000;
    private static final int ECHO = 6; // light blue; pink is kept for the observed packets
    private static final AtomicLong WINDOW_TICKETS = new AtomicLong();
    /** Character id to the ticket of its open probe window. */
    private static final Map<Integer, Long> OPEN_WINDOWS = new ConcurrentHashMap<>();
    /** Character id to the npc ids imitate has sent that character's client a look for. */
    private static final Map<Integer, Set<Integer>> IMITATED = new ConcurrentHashMap<>();

    private CompanionProbeService() {
    }

    public sealed interface Request permits Help, Log, Invite, Talk, Refresh, Imitate, GiveItem, Status, Buddy {
    }

    /** Print the usage; {@code problem} is null when no arguments were given. */
    public record Help(String problem) implements Request {
    }

    public record Log(boolean on) implements Request {
    }

    /** {@code force} allows a human inviter, who gets the stock "denied" notice if the GM declines. */
    public record Invite(String name, boolean force) implements Request {
    }

    /** {@code templateId} is written as an int after the speaker byte, unless it is {@link #NO_TEMPLATE}. */
    public record Talk(int speaker, int npcId, int msgType, int templateId) implements Request {
    }

    public record Refresh(int npcId) implements Request {
    }

    public record Imitate(String name, int npcId) implements Request {
    }

    public record GiveItem(int itemId) implements Request {
    }

    public record Status() implements Request {
    }

    /** A buddy request to the GM from the bot {@code name}, as if the bot had added the GM (probe 8). */
    public record Buddy(String name) implements Request {
    }

    public static List<String> usage() {
        return List.of(
                "!companionprobe log on|off - echo every packet your client sends (pink = probe opcodes)",
                "!companionprobe invite <name> [force] - party invite from <name>'s real party (probes 1, 12); "
                        + "force allows a human inviter",
                "!companionprobe talk <speakerByte> [npcId=" + DEFAULT_NPC_ID + "] [msgType 0|1|4|12] [+templateId] "
                        + "(probe 5; speaker bit 0x04, e.g. 4 or 5, may disconnect you)",
                "!companionprobe refresh [npcId] - re-send text 2 s later into the open window (probe 6)",
                "!companionprobe imitate <name> <npcId " + IMITATE_MIN_NPC_ID + "-" + IMITATE_MAX_NPC_ID + "> (probe 4)",
                "!companionprobe item [itemId=" + DEFAULT_ITEM_ID + "] - one item to bind to a hotkey (probe 2)",
                "!companionprobe status - your party and the probe hints",
                "!companionprobe buddy <botName> - a stock buddy request from that bot to you (probe 8)");
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
            case "invite" -> {
                if (params.length < 2 || (params.length > 2 && !params[2].equalsIgnoreCase("force"))) {
                    yield new Help("Usage: invite <name> [force]");
                }
                yield new Invite(params[1], params.length > 2);
            }
            case "talk" -> {
                int end = params.length;
                Integer templateId = NO_TEMPLATE;
                if (end > 2 && params[end - 1].startsWith("+")) {
                    templateId = number(params[end - 1].substring(1));
                    if (templateId == null || templateId < 0) {
                        yield new Help("+templateId must be a number of 0 or more, e.g. +" + DEFAULT_NPC_ID + ".");
                    }
                    end--;
                }
                Integer speaker = end > 1 ? number(params[1]) : null;
                Integer npcId = end > 2 ? number(params[2]) : Integer.valueOf(DEFAULT_NPC_ID);
                Integer msgType = end > 3 ? number(params[3]) : Integer.valueOf(0);
                if (speaker == null || speaker < 0 || speaker > 255) {
                    yield new Help("Usage: talk <speakerByte 0-255> [npcId] [msgType] [+templateId]");
                }
                if (npcId == null || npcId <= 0) {
                    yield new Help("npcId must be a positive number.");
                }
                if (msgType == null || !TALK_TYPES.contains(msgType)) {
                    yield new Help("msgType must be 0 (OK), 1 (Yes/No), 4 (menu) or 12 (Accept/Decline).");
                }
                yield new Talk(speaker, npcId, msgType, templateId);
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
            case "buddy" -> params.length == 2 ? new Buddy(params[1]) : new Help("Usage: buddy <botName>");
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
                        ? "Probe log ON until you log out or change channel: each packet your client sends is echoed "
                        + "here and logged as [companion-probe]; probe opcodes are pink. Movement, regen and pong are skipped."
                        : "Probe log OFF.");
            }
            case Invite invite -> invite(gm, invite);
            case Talk talk -> talk(gm, talk);
            case Refresh refresh -> refresh(gm, refresh.npcId());
            case Imitate imitate -> imitate(gm, imitate.name(), imitate.npcId());
            case GiveItem give -> giveItem(gm, give.itemId());
            case Status ignored -> status(gm);
            case Buddy buddy -> buddy(gm, buddy.name());
        }
    }

    /**
     * Called by {@code NPCMoreTalkHandler} for a reply that has no NPC conversation behind it. If it
     * answers one of this GM's probe windows, re-enables the client's actions. With no probe window
     * open anywhere it is a single empty-map check.
     */
    public static boolean answerOrphanTalk(Client client) {
        if (OPEN_WINDOWS.isEmpty()) {
            return false;
        }
        Character chr = client.getPlayer();
        if (chr == null || OPEN_WINDOWS.remove(chr.getId()) == null) {
            return false;
        }
        client.sendPacket(PacketCreator.enableActions());
        return true;
    }

    /**
     * Drops a character's probe state: its packet log, open probe window and imitated looks. Called
     * when its client disconnects, which includes a channel change.
     */
    public static void forget(int characterId) {
        if (CompanionProbeObserver.isObserving(characterId)) {
            CompanionProbeObserver.setObserving(characterId, false);
        }
        OPEN_WINDOWS.remove(characterId);
        IMITATED.remove(characterId);
    }

    /** Marks a probe window open for this character; returns the window's ticket. */
    public static long windowOpened(int characterId) {
        long ticket = WINDOW_TICKETS.incrementAndGet();
        OPEN_WINDOWS.put(characterId, ticket);
        return ticket;
    }

    static boolean windowOpen(int characterId) {
        return OPEN_WINDOWS.containsKey(characterId);
    }

    /** Whether the probe window {@code ticket} is still open: not answered, replaced or forgotten. */
    static boolean sameWindow(int characterId, long ticket) {
        Long open = OPEN_WINDOWS.get(characterId);
        return open != null && open == ticket;
    }

    static boolean isImitateTemplate(int npcId) {
        return (npcId >= IMITATE_MIN_NPC_ID && npcId <= IMITATE_MAX_NPC_ID) || npcId == NpcId.CUSTOM_DEV;
    }

    // ---- subcommands ---------------------------------------------------------------------

    private static void invite(Character gm, Invite request) {
        String name = request.name();
        // The stock DENY_PARTY_REQUEST handler only looks for the inviter on the invitee's channel.
        Character from = gm.getClient().getChannelServer().getPlayerStorage().getCharacterByName(name);
        if (from == null) {
            Character elsewhere = gm.getWorldServer().getPlayerStorage().getCharacterByName(name);
            gm.dropMessage(ECHO, elsewhere == null ? name + " is not online in this world."
                    : elsewhere.getName() + " is on channel " + elsewhere.getClient().getChannel() + "; change to it "
                    + "first. Declining an invite from another channel leaves it pending for about 3 min.");
            return;
        }
        if (from.getId() == gm.getId()) {
            gm.dropMessage(ECHO, "The invite must come from another character.");
            return;
        }
        boolean human = !(from.getClient() instanceof BotClient);
        if (human && !request.force()) {
            gm.dropMessage(ECHO, from.getName() + " is a human player: declining would send them a 'denied your party "
                    + "invitation' notice for an invite they never sent. Add force to send it anyway.");
            return;
        }
        Party party = from.getParty();
        if (party == null) {
            gm.dropMessage(ECHO, from.getName() + " has no party, so there is no party id to invite you into.");
            return;
        }
        int partyId = party.getId(); // read once: bot parties change on agent threads
        if (!InviteCoordinator.createInvite(InviteType.PARTY, from, partyId, gm.getId())) {
            gm.dropMessage(ECHO, "You already have a pending party invite; answer it or wait for it to expire (about 3 min).");
            return;
        }
        gm.sendPacket(CompanionProbePackets.partyInvite(partyId, from.getName()));
        int size = party.getMembers().size();
        PartyCharacter leader = party.getLeader();
        gm.dropMessage(ECHO, "Sent a party invite from " + from.getName() + " (party " + partyId + ", " + size
                + "/6, leader " + (leader == null ? "?" : leader.getName()) + "). Accept sends PARTY_OPERATION op=3 "
                + "into the stock join; decline sends DENY_PARTY_REQUEST.");
        if (human) {
            gm.dropMessage(ECHO, "Declining will notify " + from.getName() + " that you denied their invitation.");
        }
        Party own = gm.getParty();
        if (own != null) {
            gm.dropMessage(ECHO, "You are in party " + own.getId() + ": probe 1 (does a partied client show it and send op=3?).");
        }
        if (size >= 6) {
            gm.dropMessage(ECHO, "That party is full: probe 12 (does accepting still send op=3?).");
        }
        warnIfNotObserving(gm);
    }

    /**
     * Probe 8: the bot puts the GM on its own list and the world sends the GM the stock request,
     * the same two steps {@code BuddylistModifyHandler} takes when a player adds someone who is
     * online. Accepting sends BUDDYLIST_MODIFY mode 2 into the stock accept.
     */
    private static void buddy(Character gm, String name) {
        Character bot = gm.getClient().getChannelServer().getPlayerStorage().getCharacterByName(name);
        if (bot == null) {
            gm.dropMessage(ECHO, name + " is not on your channel.");
            return;
        }
        if (!(bot.getClient() instanceof BotClient)) {
            gm.dropMessage(ECHO, bot.getName() + " is a human player; ask them to add you instead.");
            return;
        }
        if (gm.getBuddylist().containsVisible(bot.getId())) {
            gm.dropMessage(ECHO, bot.getName() + " is already on your buddy list. Probe 8: click Party and Whisper on "
                    + "that row now, then again after " + bot.getName() + " leaves the world (bench it).");
            return;
        }
        BuddyList botList = bot.getBuddylist();
        if (!botList.contains(gm.getId())) {
            botList.put(new BuddylistEntry(gm.getName(), "Default Group", gm.getId(), -1, true));
        }
        BuddyList.BuddyAddResult result = gm.getWorldServer().requestBuddyAdd(gm.getName(), bot.getClient().getChannel(),
                bot.getId(), bot.getName());
        if (result == BuddyList.BuddyAddResult.BUDDYLIST_FULL) {
            gm.dropMessage(ECHO, "Your buddy list is full; delete someone first.");
            return;
        }
        gm.dropMessage(ECHO, "Sent a buddy request from " + bot.getName() + ". Accept sends BUDDYLIST_MODIFY mode=2. Probe 8: "
                + "with " + bot.getName() + " online, click Party (PARTY_OPERATION op=4?), Whisper and any find button "
                + "(WHISPER request=0x44?); then bench " + bot.getName() + ": does the row go offline, and what do the "
                + "same buttons send now?");
        warnIfNotObserving(gm);
    }

    private static void talk(Character gm, Talk talk) {
        if (!canOpenWindowOn(gm, talk.npcId())) {
            return;
        }
        boolean template = talk.templateId() != NO_TEMPLATE;
        String text = "Probe 5: speaker byte " + talk.speaker() + " (0x" + Integer.toHexString(talk.speaker()).toUpperCase()
                + ")" + (template ? " + template int " + talk.templateId() : "") + ", npc " + talk.npcId() + ", msgType "
                + talk.msgType() + ".\r\nWhose portrait is shown, and on which side?";
        if (talk.msgType() == 4) {
            text += "\r\n#L0#First option#l\r\n#L1#Second option#l";
        }
        String end = talk.msgType() == 0 ? "00 00" : "";
        if (template) {
            gm.dropMessage(ECHO, "Writing template int " + talk.templateId() + " after the speaker byte; a client that "
                    + "does not read one there misparses the text and may disconnect you.");
        } else if ((talk.speaker() & SPEAKER_TEMPLATE_BIT) != 0) {
            gm.dropMessage(ECHO, "Speaker bit 0x04 is set with no +templateId; a client that reads a template int for it "
                    + "(later clients do) misparses the text and may disconnect you.");
        }
        openWindow(gm, template
                ? CompanionProbePackets.npcTalkWithTemplate(talk.npcId(), talk.msgType(), talk.speaker(), talk.templateId(),
                text, end)
                : PacketCreator.getNPCTalk(talk.npcId(), (byte) talk.msgType(), text, end, (byte) talk.speaker()));
        gm.dropMessage(ECHO, "Opened a probe window (speaker " + talk.speaker() + ", npc " + talk.npcId() + ", msgType "
                + talk.msgType() + (template ? ", template " + talk.templateId() : "") + ").");
    }

    private static void refresh(Character gm, int npcId) {
        if (!canOpenWindowOn(gm, npcId)) {
            return;
        }
        long ticket = openWindow(gm, PacketCreator.getNPCTalk(npcId, (byte) 0,
                "Probe 6: FIRST text. Leave this window open; new text follows in 2 seconds.", "00 00", (byte) 0));
        gm.dropMessage(ECHO, "Opened a probe window; the second text follows in 2 s.");
        TimerManager.getInstance().schedule(() -> sendRefresh(gm, npcId, ticket), REFRESH_DELAY_MS);
    }

    /**
     * The refresh's second text, on the timer thread. The first window already closed the GM's
     * conversations, so this only sends a packet, and only into that same window: one answered,
     * replaced by another probe window or forgotten at disconnect gets nothing.
     */
    static void sendRefresh(Character gm, int npcId, long ticket) {
        if (!gm.isLoggedinWorld()) {
            return;
        }
        if (!sameWindow(gm.getId(), ticket)) {
            gm.dropMessage(ECHO, "Probe 6: the first window was answered or replaced within 2 s; run refresh again and wait.");
            return;
        }
        gm.sendPacket(PacketCreator.getNPCTalk(npcId, (byte) 0,
                "Probe 6: SECOND text, sent 2 s later. Same window, a new window, or nothing?", "00 00", (byte) 0));
        gm.dropMessage(ECHO, "Sent the second text into the open window.");
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
        IMITATED.computeIfAbsent(gm.getId(), id -> ConcurrentHashMap.newKeySet()).add(npcId);
        openWindow(gm, PacketCreator.getNPCTalk(npcId, (byte) 0, "Probe 4: npc " + npcId + " imitating " + target.getName()
                + ".\r\nIs this portrait " + target.getName() + "'s own face, hair and outfit?", "00 00", (byte) 0));
        gm.dropMessage(ECHO, "Sent " + target.getName() + "'s look as npc " + npcId + " and opened its window. "
                + "Your client keeps that look on " + npcId + " until you relog; talk and refresh can now use " + npcId + ".");
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
        gm.dropMessage(ECHO, "Hints: 1) partied: invite <a bot in another party>, accept -> op=3?  "
                + "2) item, bind it, press it -> SCRIPTED_ITEM?  4) imitate <companion> " + IMITATE_MAX_NPC_ID + "  "
                + "5) talk 0|1|2|3|8|9 [npc] [msgType]; 4|5 set bit 0x04 and may disconnect you, "
                + "so also try them with +<templateId>  6) refresh");
        gm.dropMessage(ECHO, "Hints: 7) double-click a bot or citizen, Party -> CHAR_INFO_REQUEST then op=4?  "
                + "8) buddy list, offline buddy, Party -> op=4?  13) trade a companion -> PLAYER_INTERACTION mode=0 type=3, mode=2");
        gm.dropMessage(ECHO, "Hint 12) at 6/6: party-window Invite and char-info Party -> op=4?; invite <member of a full party>, "
                + "accept -> op=3?" + (size >= 6 ? "  (you are at 6/6 now)" : ""));
        warnIfNotObserving(gm);
    }

    // ---- helpers -------------------------------------------------------------------------

    /**
     * Closes whatever conversation the GM has open so the probe window's reply has none behind it,
     * then sends the window; returns its ticket. Runs on the GM's IO thread (a chat command).
     */
    private static long openWindow(Character gm, Packet talk) {
        Client client = gm.getClient();
        NPCScriptManager.getInstance().dispose(client);
        QuestScriptManager.getInstance().dispose(client);
        LivingTalk.closeFor(gm);
        long ticket = windowOpened(gm.getId());
        gm.sendPacket(talk);
        return ticket;
    }

    /** Talk and refresh: an existing NPC, and no imitate template the GM's client has no look for. */
    private static boolean canOpenWindowOn(Character gm, int npcId) {
        if (isImitateTemplate(npcId) && !IMITATED.getOrDefault(gm.getId(), Set.of()).contains(npcId)) {
            gm.dropMessage(ECHO, npcId + " is an imitated-NPC template with no look sent to your client this session "
                    + "(a portrait without one is untested and may crash it); imitate <name> " + npcId + " sends one first.");
            return false;
        }
        return npcExists(gm, npcId);
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
