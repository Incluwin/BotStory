package server.living.population.casino;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.Shop;
import server.agents.capabilities.inventory.demand.AgentEtcRoomPlan;
import server.agents.capabilities.inventory.demand.AgentEtcSaleCandidate;
import server.economy.ledger.MesoAccount;
import server.economy.ledger.MesoFlows;
import server.life.LifeFactory;
import server.life.NPC;
import server.maps.Foothold;
import server.maps.MapObject;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ObjIntConsumer;

/**
 * The casino's cashier (SoloMapling's CasinoChipConfig and its chip shop): stock NPC Aramia (9000055) spawned in Henesys
 * Game Park on every population channel, opening a shop built here that sells the four chips at their value and buys
 * them back at the same price. Only this shop pays a chip's value: SoloMapling rewrote the stamps' price in the server's
 * Item.wz XML instead, which every NPC shop read. The client still shows a stamp's stock sell price in the shop window
 * until a client Item.wz patch ships; what the player receives is the chip value. Citizens buy and cash out the same
 * way, at the counter, booked as the NPC sale and purchase they are. Clicks are taken by object id, so the Aramia of
 * Maple Hill (970010000) keeps her own talk.
 */
public final class ChipCashier {
    public static final int NPC_ID = 9000055;
    private static final int SHOP_ID = 9_999_001;
    private static final int GROUND_REACH_PX = 200;
    private static final Logger log = LoggerFactory.getLogger(ChipCashier.class);
    /** A citizen's junk sale at the counter: stock prices only (no chip is a monster drop). */
    private static final Shop COUNTER = Shop.custom(SHOP_ID, NPC_ID);

    private final CasinoHost host;
    private final CasinoContent content;
    private final Shop shop = Shop.custom(SHOP_ID, NPC_ID);
    /** "channel" -> object id of the cashier standing on Game Park there. */
    private final Map<Integer, Integer> spawned = new ConcurrentHashMap<>();

    ChipCashier(CasinoHost host, CasinoContent content) {
        this.host = host;
        this.content = content;
        for (int chip : Chips.ids()) {
            shop.stock(chip, Chips.value(chip));
            shop.buysBackAt(chip, Chips.value(chip));
        }
    }

    boolean enabled() {
        return host.population().config().CASINO_CHIPS_ENABLED;
    }

    /** Where the cashier stands; citizens walk here to buy and cash out. */
    Point spot() {
        return content.cashier();
    }

    /** Every 20 s: the cashier stands on Game Park on every population channel while the switch is on. */
    void ensure() {
        if (!enabled()) {
            removeAll();
            return;
        }
        for (int channel : host.population().channels()) {
            MapleMap map = host.population().map(channel, content.gameParkMap());
            if (map == null) {
                continue;
            }
            Integer objectId = spawned.get(channel);
            if (objectId != null && map.getMapObject(objectId) instanceof NPC npc && npc.getId() == NPC_ID) {
                continue;
            }
            spawn(channel, map);
        }
    }

    private void spawn(int channel, MapleMap map) {
        NPC npc = LifeFactory.getNPC(NPC_ID);
        Point at = content.cashier();
        Point ground = map.getGroundBelow(at);
        Foothold foothold = ground == null ? null : map.getFootholds().findBelow(at);
        if (npc == null || ground == null || foothold == null || ground.y - at.y > GROUND_REACH_PX) {
            return;
        }
        npc.setPosition(ground);
        npc.setCy(ground.y);
        npc.setRx0(ground.x + 50);
        npc.setRx1(ground.x - 50);
        npc.setFh(foothold.getId());
        map.addMapObject(npc);
        map.broadcastMessage(PacketCreator.spawnNPC(npc));
        spawned.put(channel, npc.getObjectId());
    }

    /** Takes the cashier down everywhere (switch off, population stopping). */
    void removeAll() {
        for (Map.Entry<Integer, Integer> entry : spawned.entrySet()) {
            MapleMap map = host.population().map(entry.getKey(), content.gameParkMap());
            MapObject object = map == null ? null : map.getMapObject(entry.getValue());
            if (object instanceof NPC npc && npc.getId() == NPC_ID) {
                map.removeMapObject(object);
                map.broadcastMessage(PacketCreator.removeNPCController(npc.getObjectId()));
                map.broadcastMessage(PacketCreator.removeNPC(npc.getObjectId()));
            }
        }
        spawned.clear();
    }

    /** A player clicked an NPC: our cashier opens the chip shop (true); anything else is not ours. */
    boolean clicked(Character player, int objectId) {
        if (!enabled() || player == null || player.getMapId() != content.gameParkMap()) {
            return false;
        }
        Integer ours = spawned.get(player.getClient().getChannel());
        if (ours == null || ours != objectId) {
            return false;
        }
        shop.sendShop(player.getClient());
        return true;
    }

    /** The cashier's shop, for tests. */
    Shop shop() {
        return shop;
    }

    /**
     * A citizen buys chips worth {@code mesos} (rounded down to the smallest chip) at the counter: false, nothing
     * bought, when its purse or its bag cannot take it. A full Etc tab first sells junk there, as at any shop visit.
     */
    static boolean buy(Character agent, long mesos) {
        return buy(agent, Chips.change(mesos), ChipCashier::makeRoom);
    }

    /**
     * {@link #buy(Character, long)} in chips no bigger than {@code largest}: a seated player bets a slice of its stack,
     * so it buys chips it can bet with (live 2026-11-08: a 300k stack as one 250k and one 50k chip never bet ~30k).
     */
    static boolean buy(Character agent, long mesos, long largest) {
        return buy(agent, Chips.change(mesos, largest), ChipCashier::makeRoom);
    }

    static boolean buy(Character agent, long mesos, ObjIntConsumer<Character> makeRoom) {
        return buy(agent, Chips.change(mesos), makeRoom);
    }

    private static boolean buy(Character agent, Map<Integer, Integer> chips, ObjIntConsumer<Character> makeRoom) {
        long cost = Chips.value(chips);
        if (chips.isEmpty() || agent.getMeso() < cost || agent.getClient() == null) {
            return false;
        }
        if (!roomFor(agent, chips)) {
            makeRoom.accept(agent, chips.size());
            if (!roomFor(agent, chips)) {
                return false;
            }
        }
        try (var ignored = MesoFlows.as(MesoAccount.CASINO)) {
            agent.gainMeso((int) -cost, false);
        }
        for (Map.Entry<Integer, Integer> chip : chips.entrySet()) {
            InventoryManipulator.addById(agent.getClient(), chip.getKey(), (short) (int) chip.getValue());
        }
        return true;
    }

    private static boolean roomFor(Character agent, Map<Integer, Integer> chips) {
        for (Map.Entry<Integer, Integer> chip : chips.entrySet()) {
            if (!InventoryManipulator.checkSpace(agent.getClient(), chip.getKey(), chip.getValue(), "")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Live 2026-11-08: most citizens carry a full Etc tab, so a citizen sent to sit at blackjack could not take its
     * chips and both tables waited with nobody seated. The counter is a shop, so it sells whole stacks of monster drops
     * here as at any visit (AgentEtcRoomPlan, up to the room its host has it keep), through the shop's own sale.
     */
    private static void makeRoom(Character agent, int chipKinds) {
        int wanted = Math.max(chipKinds, AgentEtcRoomPlan.keepsFree(agent.getId()));
        int sold = 0;
        for (AgentEtcSaleCandidate sale : AgentEtcRoomPlan.plan(agent, wanted)) {
            COUNTER.sell(agent.getClient(), InventoryType.ETC, sale.item().getPosition(), sale.quantity());
            sold++;
        }
        if (sold > 0) {
            log.info("citizen {} sold {} etc stacks at the casino counter to make room for chips", agent.getName(),
                    sold);
        }
    }

    /** A citizen sells every chip it holds back at the counter; returns the mesos it got. */
    static long cashOut(Character agent) {
        if (agent.getClient() == null) {
            return 0;
        }
        long total = 0;
        for (int chip : Chips.ids()) {
            int held = agent.getItemQuantity(chip, false);
            if (held <= 0) {
                continue;
            }
            long value = (long) held * Chips.value(chip);
            if (!agent.canHoldMeso((int) Math.min(Integer.MAX_VALUE, value))) {
                continue;
            }
            InventoryManipulator.removeById(agent.getClient(), InventoryType.ETC, chip, held, false, false);
            try (var ignored = MesoFlows.as(MesoAccount.CASINO)) {
                agent.gainMeso((int) value, false);
            }
            total += value;
        }
        return total;
    }

    /** The chips a citizen holds, in mesos. */
    static long holding(Character agent) {
        long total = 0;
        for (int chip : Chips.ids()) {
            total += (long) agent.getItemQuantity(chip, false) * Chips.value(chip);
        }
        return total;
    }
}
