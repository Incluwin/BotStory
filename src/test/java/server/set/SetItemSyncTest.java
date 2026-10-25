package server.set;

import io.netty.buffer.Unpooled;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SetItemSyncTest {

    @AfterEach
    void reset() {
        SetItemManager.installConfig(new SetItemsConfig());
    }

    @Test
    void packetListsMembersWornFlagsAndOnlyTheAppliedTierStats() {
        SetItemDef def = set("timeless_warrior", "Timeless Warrior", List.of("WARRIOR"),
                List.of(1002776, 1052155, 1082234), tier(2, 15, 5, 5, 0, 0), tier(3, 10, 0, 0, 100, 0));
        def.bonuses.get(1).wdef = 50; // the server never applies set WDEF, so the tooltip must not promise it

        InPacket in = decode(SetItemSync.packet(List.of(def), Arrays.asList(1052155, 1002776, 1302000)).getBytes());

        assertEquals(SendOpcode.SET_ITEM_SYNC.getValue(), in.readShort());
        assertEquals(SetItemSync.PROTOCOL, in.readByte());
        assertEquals(1, in.readShort());
        assertEquals("Timeless Warrior", in.readString());
        assertEquals(3, in.readShort(), "total = the highest threshold");
        assertEquals(2, in.readShort(), "pieces worn");
        assertEquals(3, in.readShort());
        assertMember(in, 1002776, true);
        assertMember(in, 1052155, true);
        assertMember(in, 1082234, false);
        assertEquals(2, in.readShort());
        assertEquals(2, in.readShort());
        assertEquals(3, in.readShort());
        assertStat(in, SetItemSync.STAT_ALL_STATS, 15);
        assertStat(in, SetItemSync.STAT_WATK, 5);
        assertStat(in, SetItemSync.STAT_MATK, 5);
        assertEquals(3, in.readShort());
        assertEquals(2, in.readShort());
        assertStat(in, SetItemSync.STAT_ALL_STATS, 10);
        assertStat(in, SetItemSync.STAT_HP, 100);
        assertEquals(0, in.available());
    }

    @Test
    void piecesCountTheWayTheBonusDoes() {
        SetItemDef def = set("s", "S", List.of(), List.of(1, 2, 3), tier(2, 1, 0, 0, 0, 0));
        SetItemManager.installConfig(config(def));
        List<Integer> worn = List.of(1, 3, 99);

        InPacket in = decode(SetItemSync.packet(List.of(def), worn).getBytes());
        in.readShort();
        in.readByte();
        in.readShort();
        in.readString();
        in.readShort();

        assertEquals(SetItemManager.computeFromItemIds(worn).activeSets.get(0).pieces, in.readShort());
    }

    @Test
    void setsForKeepsYamlOrderAndTheJobFilter() {
        SetItemDef warrior = set("w", "W", List.of("WARRIOR"), List.of(1), tier(1, 1, 0, 0, 0, 0));
        SetItemDef any = set("a", "A", List.of(), List.of(2), tier(1, 1, 0, 0, 0, 0));
        SetItemDef mage = set("m", "M", List.of("MAGICIAN"), List.of(3), tier(1, 1, 0, 0, 0, 0));
        SetItemManager.installConfig(config(warrior, any, mage));

        assertEquals(List.of(warrior, any), SetItemManager.setsFor("WARRIOR"));
        assertEquals(List.of(warrior, any, mage), SetItemManager.setsFor(null), "Beginners see every set");
    }

    @Test
    void anEmptySetListIsJustTheHeader() {
        InPacket in = decode(SetItemSync.packet(List.of(), List.of(1)).getBytes());
        in.readShort();
        assertEquals(SetItemSync.PROTOCOL, in.readByte());
        assertEquals(0, in.readShort());
        assertEquals(0, in.available());
    }

    private static void assertMember(InPacket in, int itemId, boolean worn) {
        assertEquals(itemId, in.readInt());
        assertEquals(worn ? 1 : 0, in.readByte());
    }

    private static void assertStat(InPacket in, int stat, int value) {
        assertEquals(stat, in.readByte());
        assertEquals(value, in.readInt());
    }

    private static InPacket decode(byte[] bytes) {
        return new ByteBufInPacket(Unpooled.wrappedBuffer(bytes));
    }

    private static SetItemsConfig config(SetItemDef... defs) {
        SetItemsConfig config = new SetItemsConfig();
        config.sets.addAll(Arrays.asList(defs));
        return config;
    }

    private static SetItemDef set(String id, String name, List<String> jobs, List<Integer> members,
                                  SetBonusDef... tiers) {
        SetItemDef def = new SetItemDef();
        def.id = id;
        def.name = name;
        def.jobs.addAll(jobs);
        def.members.addAll(members);
        def.bonuses.addAll(Arrays.asList(tiers));
        return def;
    }

    private static SetBonusDef tier(int pieces, int allStats, int watk, int matk, int hp, int mp) {
        SetBonusDef tier = new SetBonusDef();
        tier.pieces = pieces;
        tier.allStats = allStats;
        tier.watk = watk;
        tier.matk = matk;
        tier.hp = hp;
        tier.mp = mp;
        return tier;
    }
}
