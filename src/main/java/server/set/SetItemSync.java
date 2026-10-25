package server.set;

import client.Character;
import client.Client;
import client.inventory.InventoryType;
import client.inventory.Item;
import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import net.packet.Packet;
import server.mods.ServerMods;
import server.mods.features.FeatureHandshake;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SET_ITEM_SYNC (0x186, docs/CUSTOM_OPCODES.md): the sets the kaentake client's set_items module draws in the equip
 * tooltip. Always sent whole, never as a change: when the client's features apply ({@link FeatureHandshake#receive})
 * and after every equip change ({@code Character.equipChanged}), to a client with set_items active while the
 * set_items mod is on. Bots never report features, so they are never sent it.
 *
 * <p>Little-endian; a string is the Maple string. {@code byte protocol} (1), {@code short sets}, then each set:
 * {@code string name}, {@code short total} (its highest threshold: how many slots can count), {@code short pieces}
 * (members worn), {@code short members} each {@code int itemId} + {@code byte worn}, {@code short tiers} each
 * {@code short pieces} + {@code short stats} each {@code byte stat} + {@code int value}. Only the stats the server
 * applies (the {@code STAT_} codes) are sent; the rest of a tier stays in set-items.yml.
 */
public final class SetItemSync {
    public static final int PROTOCOL = 1;

    public static final int STAT_ALL_STATS = 1;
    public static final int STAT_WATK = 2;
    public static final int STAT_MATK = 3;
    public static final int STAT_HP = 4;
    public static final int STAT_MP = 5;

    private SetItemSync() {
    }

    /** Sends the character's sets to its own client, if the mod is on and the client has set_items active. */
    public static void send(Character chr) {
        Client client = chr == null ? null : chr.getClient();
        if (client == null || !ServerMods.get().isOn(ServerMods.SET_ITEMS)
                || !FeatureHandshake.get().isActive(client, ServerMods.SET_ITEMS.id())) {
            return;
        }
        List<Integer> worn = new ArrayList<>();
        for (Item item : chr.getInventory(InventoryType.EQUIPPED)) {
            worn.add(item.getItemId());
        }
        client.sendPacket(packet(SetItemManager.setsFor(SetItemManager.jobNicheName(chr.getJob())), worn));
    }

    public static Packet packet(List<SetItemDef> sets, Collection<Integer> worn) {
        Set<Integer> wornIds = new HashSet<>(worn);
        OutPacket out = OutPacket.create(SendOpcode.SET_ITEM_SYNC);
        out.writeByte(PROTOCOL);
        out.writeShort(sets.size());
        for (SetItemDef def : sets) {
            List<Integer> members = def.members == null ? List.of() : def.members;
            List<SetBonusDef> tiers = def.bonuses == null ? List.of() : def.bonuses;
            int pieces = 0;
            int total = 0;
            for (Integer itemId : worn) {
                if (itemId != null && members.contains(itemId)) {
                    pieces++; // as SetItemManager.computeFromItemIds counts them
                }
            }
            for (SetBonusDef tier : tiers) {
                if (tier != null) {
                    total = Math.max(total, tier.pieces);
                }
            }
            out.writeString(def.name == null ? def.id : def.name);
            out.writeShort(total);
            out.writeShort(pieces);
            out.writeShort(members.size());
            for (Integer itemId : members) {
                out.writeInt(itemId == null ? 0 : itemId);
                out.writeByte(itemId != null && wornIds.contains(itemId) ? 1 : 0);
            }
            out.writeShort((int) tiers.stream().filter(tier -> tier != null).count());
            for (SetBonusDef tier : tiers) {
                if (tier == null) {
                    continue;
                }
                List<int[]> stats = appliedStats(tier);
                out.writeShort(tier.pieces);
                out.writeShort(stats.size());
                for (int[] stat : stats) {
                    out.writeByte(stat[0]);
                    out.writeInt(stat[1]);
                }
            }
        }
        return out;
    }

    /** The tier's nonzero stats that Character.recalcEquipStats applies, as {code, value}. */
    private static List<int[]> appliedStats(SetBonusDef tier) {
        List<int[]> stats = new ArrayList<>(5);
        int[][] all = {{STAT_ALL_STATS, tier.allStats}, {STAT_WATK, tier.watk}, {STAT_MATK, tier.matk},
                {STAT_HP, tier.hp}, {STAT_MP, tier.mp}};
        for (int[] stat : all) {
            if (stat[1] != 0) {
                stats.add(stat);
            }
        }
        return stats;
    }
}
