package server.living.population.needs;

import client.Character;
import client.Client;
import server.living.population.Citizen;
import server.living.population.CitizenSession;
import server.living.population.Market;
import server.living.population.PersonMemory;
import server.living.population.Population;
import server.living.resident.LivingTalk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The needs board's windows (stock NPC talk through LivingTalk, the Bulletin Board's portrait): the board's menu, the
 * requests sorted for the player, one request's details with Claim and "Hand it over here", the player's held requests,
 * and a citizen's own window when a player who carries what it asked for double-clicks it. A hand-over runs on
 * companion-ops (economy and database work, never the packet thread); its answer is a window within a second of the
 * click, else a pink line, as the hire window does.
 */
public final class NeedScreens {
    /** Requests listed per board window ("More..." pages past LivingTalk's own page size). */
    private static final int LISTED_MAX = 30;
    /** An outcome later than this after its click answers in pink text: no window pops up mid-fight. */
    private static final long LATE_OUTCOME_MS = 1_000L;
    private static final long MINUTE_MS = 60_000L;

    private final Population population;
    private final NeedLedger ledger;

    NeedScreens(Population population, NeedLedger ledger) {
        this.population = population;
        this.ledger = ledger;
    }

    // ---- the board ----------------------------------------------------------------------------

    /** The board's first window: requests near you, the ones you hold (and room for the rival crew's records, F7). */
    public void board(Character player, int boardMapId) {
        long nowMs = System.currentTimeMillis();
        List<LivingTalk.Option> options = new ArrayList<>();
        options.add(new LivingTalk.Option("#bRequests#k (" + listed(player, boardMapId, nowMs).size() + ")",
                () -> browse(player, boardMapId)));
        options.add(new LivingTalk.Option("#bYour requests#k (" + ledger.claimedBy(player.getId(), nowMs).size() + " held)",
                () -> mine(player, boardMapId)));
        LivingTalk.open(player, NeedBoards.BOARD_NPC)
                .menu("Townsfolk pin what they need here. Bring it to them, or leave it here if they are away; they pay"
                        + " on the spot.", options)
                .show();
    }

    private void browse(Character player, int boardMapId) {
        long nowMs = System.currentTimeMillis();
        List<Need> needs = listed(player, boardMapId, nowMs);
        if (needs.isEmpty()) {
            LivingTalk.open(player, NeedBoards.BOARD_NPC).back(() -> board(player, boardMapId))
                    .ok("Nothing is pinned up right now.").show();
            return;
        }
        List<LivingTalk.Option> options = new ArrayList<>();
        for (Need need : needs.subList(0, Math.min(LISTED_MAX, needs.size()))) {
            options.add(new LivingTalk.Option(label(player, need, nowMs), () -> details(player, need.id(), boardMapId)));
        }
        LivingTalk.open(player, NeedBoards.BOARD_NPC).back(() -> board(player, boardMapId))
                .menu("Nearest first, people who know you before strangers.", options).show();
    }

    private void mine(Character player, int boardMapId) {
        long nowMs = System.currentTimeMillis();
        List<LivingTalk.Option> options = new ArrayList<>();
        for (Need need : ledger.claimedBy(player.getId(), nowMs)) {
            options.add(new LivingTalk.Option(label(player, need, nowMs), () -> details(player, need.id(), boardMapId)));
        }
        if (options.isEmpty()) {
            LivingTalk.open(player, NeedBoards.BOARD_NPC).back(() -> board(player, boardMapId))
                    .ok("You hold no requests. Claim one so nobody else brings it first (you can hold "
                            + population.config().NEEDS_CLAIMS_PER_PLAYER + ").").show();
            return;
        }
        LivingTalk.open(player, NeedBoards.BOARD_NPC).back(() -> board(player, boardMapId))
                .menu("The requests you hold:", options).show();
    }

    /** One request: who, what for, what it pays, where they are, with what the player can do about it now. */
    private void details(Character player, int needId, int boardMapId) {
        long nowMs = System.currentTimeMillis();
        Need need = ledger.need(needId);
        Runnable back = () -> browse(player, boardMapId);
        if (need == null || !need.isOpen()) {
            LivingTalk.open(player, NeedBoards.BOARD_NPC).back(back).ok("That one was taken down.").show();
            return;
        }
        List<LivingTalk.Option> options = new ArrayList<>();
        int holder = need.holder(nowMs);
        int carried = need.kind().item() ? ledger.carried(player, need) : 0;
        if (need.kind().item() && holder == 0) {
            options.add(new LivingTalk.Option("Claim it (hold it " + population.config().NEEDS_CLAIM_MINUTES + " min)",
                    () -> claim(player, needId, boardMapId)));
        }
        if (carried > 0 && need.takesFrom(player.getId(), nowMs)) {
            options.add(new LivingTalk.Option("Hand it over here", () -> give(player, needId, carried, true)));
        }
        if (holder == player.getId()) {
            options.add(new LivingTalk.Option("Let it go", () -> {
                ledger.drop(player.getId(), needId, System.currentTimeMillis());
                details(player, needId, boardMapId);
            }));
        }
        String text = describe(player, need, nowMs) + (holder != 0 && holder != player.getId()
                ? "\r\n\r\n#rSomeone else is bringing this.#k" : "");
        if (options.isEmpty()) {
            LivingTalk.open(player, NeedBoards.BOARD_NPC).back(back).ok(text).show();
        } else {
            LivingTalk.open(player, NeedBoards.BOARD_NPC).back(back).menu(text, options).show();
        }
    }

    private void claim(Character player, int needId, int boardMapId) {
        NeedLedger.Claim claim = ledger.claim(player.getId(), needId, System.currentTimeMillis());
        String text = switch (claim) {
            case OK -> "It's yours for " + population.config().NEEDS_CLAIM_MINUTES + " minutes. Bring it to them, or back"
                    + " here if they are away.";
            case HELD -> "Someone else is already bringing that.";
            case LIMIT -> "You already hold " + population.config().NEEDS_CLAIMS_PER_PLAYER + " requests.";
            case GONE -> "That one was taken down.";
        };
        LivingTalk.open(player, NeedBoards.BOARD_NPC).back(() -> details(player, needId, boardMapId)).ok(text).show();
    }

    // ---- in person ----------------------------------------------------------------------------

    /**
     * A player double-clicked a citizen: when it has a request the player can help with now (carrying the item, nobody
     * else holding it), its window offers the hand-over, and true; false keeps the stock character info.
     */
    public boolean inPerson(Character player, Character citizen) {
        if (!ledger.on()) {
            return false;
        }
        long nowMs = System.currentTimeMillis();
        List<LivingTalk.Option> options = new ArrayList<>();
        Need only = null;
        for (Need need : ledger.of(citizen.getId())) {
            if (!need.takesFrom(player.getId(), nowMs)) {
                continue;
            }
            int carried = ledger.carried(player, need);
            if (carried > 0) {
                only = need;
                options.add(new LivingTalk.Option("I have your #t" + need.itemId() + "#",
                        () -> offer(player, citizen.getName(), need.id(), carried)));
            }
        }
        if (options.isEmpty()) {
            return false;
        }
        if (options.size() == 1) {
            offer(player, citizen.getName(), only.id(), ledger.carried(player, only));
        } else {
            LivingTalk.open(player, NeedBoards.BOARD_NPC).menu(citizen.getName() + " is looking for a few things.", options)
                    .show();
        }
        return true;
    }

    private void offer(Character player, String name, int needId, int carried) {
        long nowMs = System.currentTimeMillis();
        Need need = ledger.need(needId);
        if (need == null || !need.takesFrom(player.getId(), nowMs)) {
            LivingTalk.open(player, NeedBoards.BOARD_NPC).ok(name + " doesn't need that any more.").show();
            return;
        }
        int most = Math.min(carried, need.remaining());
        String text = name + " needs " + amount(need) + " for " + ledger.forWords(need) + ", paying #b"
                + Market.formatPrice(need.offer()) + "#k each. You have " + carried + ".";
        if (most > 1) {
            LivingTalk.open(player, NeedBoards.BOARD_NPC)
                    .number(text + "\r\nHow many do you give?", most, 1, most, count -> give(player, needId, count, false))
                    .show();
        } else {
            LivingTalk.open(player, NeedBoards.BOARD_NPC)
                    .acceptDecline(text + "\r\nHand it over for #b" + Market.formatPrice(need.pay(1)) + "#k?",
                            () -> give(player, needId, 1, false), null)
                    .show();
        }
    }

    // ---- the hand-over ---------------------------------------------------------------------------

    /** A board hand-in of a SUPPLY request asks how many first; the rest go at once. */
    private void give(Character player, int needId, int count, boolean board) {
        Need need = ledger.need(needId);
        if (board && need != null && need.kind() == NeedKind.SUPPLY && Math.min(count, need.remaining()) > 1) {
            int most = Math.min(count, need.remaining());
            LivingTalk.open(player, NeedBoards.BOARD_NPC)
                    .number("How many do you leave? (" + Market.formatPrice(need.offer()) + " each)", most, 1, most,
                            chosen -> handOver(player, needId, chosen, true))
                    .show();
            return;
        }
        handOver(player, needId, count, board);
    }

    private void handOver(Character player, int needId, int count, boolean board) {
        long clickedAtMs = System.currentTimeMillis();
        Need before = ledger.need(needId);
        population.world().executor().submit("needs.handover " + needId, () -> {
            String refused = ledger.handOver(player, needId, count, board, System.currentTimeMillis());
            String text = refused != null ? refused : done(before, count, board);
            Client client = player.getClient();
            if (client == null) {
                return; // logged off meanwhile
            }
            client.runOnIoThread(() -> {
                if (System.currentTimeMillis() - clickedAtMs <= LATE_OUTCOME_MS && LivingTalk.mayShow(player)) {
                    LivingTalk.answering(player, () -> LivingTalk.open(player, NeedBoards.BOARD_NPC).ok(text).show());
                } else {
                    player.dropMessage(5, text);
                }
            });
        });
    }

    private String done(Need need, int count, boolean board) {
        String name = need == null ? "They" : citizenName(need.citizenId());
        long pay = need == null ? 0L : need.pay(Math.min(count, need.remaining()));
        CitizenSession session = need == null ? null : population.presence().session(need.citizenId());
        boolean away = session == null || session.virtual || session.agent() == null;
        return (board && away ? "You leave it pinned for " + name + ", who will pick it up next time they're around. "
                : name + " takes it. ") + "You receive " + Market.formatPrice(pay) + " meso.";
    }

    // ---- words -----------------------------------------------------------------------------------

    /** Open requests the board shows this player: nearest to the board first, then the warmer, then the newest. */
    List<Need> listed(Character player, int boardMapId, long nowMs) {
        List<Need> out = new ArrayList<>();
        for (Need need : ledger.open()) {
            if (need.kind().item() ? need.remaining() > 0 : need.quantity() > 0) {
                out.add(need);
            }
        }
        out.sort(Comparator.comparingInt((Need need) -> distance(boardMapId, where(need)))
                .thenComparing(Comparator.comparingInt((Need need) -> rung(warmth(need, player))).reversed())
                .thenComparing(Comparator.comparingLong(Need::createdAtMs).reversed()));
        return out;
    }

    /** 0 the same map, 1 the same area (map id's first three digits: Henesys and its fields), 2 elsewhere. */
    static int distance(int fromMapId, int toMapId) {
        if (fromMapId == toMapId) {
            return 0;
        }
        return fromMapId / 1_000_000 == toMapId / 1_000_000 ? 1 : 2;
    }

    /** The citizen standing ladder's citizen column (C1): 0 stranger, 1 regular (warmth 15), 2 friend (40). */
    static int rung(int warmth) {
        return warmth >= PersonMemory.FOND ? 2 : warmth >= PersonMemory.WARM ? 1 : 0;
    }

    private int warmth(Need need, Character player) {
        return population.memories() == null ? 0 : population.memories().warmth(need.citizenId(), player.getId());
    }

    /** Where the citizen is now: its map when it is about, else where it asked. */
    private int where(Need need) {
        CitizenSession session = population.presence().session(need.citizenId());
        return session != null && !session.virtual && session.agent() != null ? session.mapId : need.mapId();
    }

    private String label(Character player, Need need, long nowMs) {
        String name = citizenName(need.citizenId());
        int rung = rung(warmth(need, player));
        String knows = rung == 2 ? " #d(friend)#k" : rung == 1 ? " #d(knows you)#k" : "";
        String held = need.holder(nowMs) == player.getId() ? " #g(yours)#k" : need.holder(nowMs) != 0 ? " #r(held)#k" : "";
        if (need.kind() == NeedKind.PARTY) {
            return name + knows + ": party at " + mapName(where(need)) + " wants " + ledger.what(need);
        }
        return name + knows + ": " + amount(need) + " @ " + Market.formatPrice(need.offer()) + held;
    }

    private String describe(Character player, Need need, long nowMs) {
        Citizen citizen = population.citizen(need.citizenId());
        CitizenSession session = population.presence().session(need.citizenId());
        boolean about = session != null && !session.virtual && session.agent() != null;
        String who = (citizen == null ? "Someone" : "#e" + citizen.name() + "#n")
                + (about ? ", Lv" + session.agent().getLevel() + " " + job(session) : "");
        if (need.kind() == NeedKind.PARTY) {
            return who + " is forming a party at #b" + mapName(where(need)) + "#k and wants " + ledger.what(need)
                    + ". Go there; the leader invites people whose level fits.";
        }
        String left = need.deadlineMs() <= 0L ? "" : "\r\nUp for " + duration(need.deadlineMs() - nowMs) + " more.";
        return who + " needs #i" + need.itemId() + "# " + amount(need) + " for " + ledger.forWords(need) + ".\r\n"
                + "Pays #b" + Market.formatPrice(need.offer()) + "#k each (#b" + Market.formatPrice(need.pay(need.remaining()))
                + "#k in all)" + (need.delivered() > 0 ? ", " + need.delivered() + " already brought" : "") + "." + left
                + "\r\n" + (about ? "Now at #b" + mapName(session.mapId) + "#k: hand it over in person (double-click them)."
                : "Away right now: leave it here and they pick it up when they're back.")
                + (need.kind() == NeedKind.GEAR ? "\r\nA plain one is fine; better is welcome." : "");
    }

    private static String amount(Need need) {
        return (need.remaining() > 1 ? need.remaining() + " " : "") + "#t" + need.itemId() + "#";
    }

    private String citizenName(int citizenId) {
        Citizen citizen = population.citizen(citizenId);
        return citizen == null ? "Someone" : citizen.name();
    }

    private String mapName(int mapId) {
        return mapId <= 0 ? "somewhere" : population.fields().name(mapId);
    }

    private static String job(CitizenSession session) {
        return session.agent().getJob().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    static String duration(long ms) {
        long minutes = Math.max(1L, ms / MINUTE_MS);
        if (minutes < 120) {
            return minutes + " min";
        }
        return (minutes / 60) + " hours";
    }

    // ---- chat (@requests) --------------------------------------------------------------------------

    /** {@code @requests}: the nearest requests and the player's holds, a line each, with the numbers to claim by. */
    public List<String> chatList(Character player, long nowMs) {
        List<String> out = new ArrayList<>();
        List<Need> mine = ledger.claimedBy(player.getId(), nowMs);
        for (Need need : mine) {
            out.add("[yours] #" + need.id() + " " + citizenName(need.citizenId()) + ": " + plain(need) + " for "
                    + ledger.forWords(need) + ", " + Market.formatPrice(need.offer()) + " ea, "
                    + duration(need.holder(nowMs) == 0 ? 0 : need.claimedUntilMs() - nowMs) + " left");
        }
        List<Need> near = listed(player, player.getMapId(), nowMs);
        for (Need need : near.subList(0, Math.min(8, near.size()))) {
            if (need.holder(nowMs) == player.getId()) {
                continue;
            }
            out.add("#" + need.id() + " " + citizenName(need.citizenId()) + ": " + plain(need) + (need.kind().item()
                    ? " for " + ledger.forWords(need) + ", " + Market.formatPrice(need.offer()) + " ea" : "")
                    + " (" + mapName(where(need)) + ")" + (need.holder(nowMs) != 0 ? " [held]" : ""));
        }
        return out;
    }

    private String plain(Need need) {
        return ledger.what(need);
    }
}
