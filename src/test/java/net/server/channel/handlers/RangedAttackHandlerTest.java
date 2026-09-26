package net.server.channel.handlers;

import client.BotClient;
import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import constants.id.ItemId;
import constants.skills.Corsair;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.combat.AgentAttackExecutionProvider;
import server.agents.capabilities.combat.AgentAttackRoute;
import server.agents.capabilities.combat.AgentSkillAttackPlanner;

import java.awt.Point;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RangedAttackHandlerTest {
    @Test
    void shouldConsumeProjectileFromBotCharacterWhenClientPlayerIsUnset() {
        Character bot = mock(Character.class);
        Inventory useInventory = new Inventory(bot, InventoryType.USE, (byte) 8);
        Item stars = new Item(ItemId.SUBI_THROWING_STARS, (short) 1, (short) 10);
        useInventory.addItem(stars);
        when(bot.getInventory(InventoryType.USE)).thenReturn(useInventory);

        BotClient client = new BotClient(0, 1);

        RangedAttackHandler.consumeProjectile(bot, client, (short) 1, (short) 1);

        assertEquals(9, useInventory.getItem((short) 1).getQuantity());
    }

    @Test
    void agentRapidFireBroadcastsTheShotActionWithOnlyTheFacingBitAbove() {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(4242);
        // An Agent at x=100 shooting a monster at x=40 faces left.
        AgentSkillAttackPlanner.SkillAttackPacketFields fields =
                AgentSkillAttackPlanner.resolveSkillAttackPacketFields(AgentAttackRoute.RANGED, WeaponType.GUN,
                        new Point(100, 0), new Point(40, 0), "shot", "handgun");
        AbstractDealDamageHandler.AttackInfo attack = new AbstractDealDamageHandler.AttackInfo();
        attack.skill = Corsair.RAPID_FIRE;
        attack.skilllevel = 30;
        attack.numAttackedAndDamage = 0x11;
        attack.display = fields.display();
        attack.direction = fields.direction();
        attack.rangedirection = fields.rangedDirection();
        attack.stance = fields.stance();
        attack.speed = 4;
        attack.targets = new HashMap<>();

        byte[] packet = RangedAttackHandler.createAttackPacket(attack, bot, 2330005).getBytes();

        // opcode(2) id(4) counts(1) level(1) skillLevel(1) skillId(4) display(1), then the action as a
        // little-endian short: action id in bits 0-14, facing left in bit 15. Rapid Fire is a keydown
        // shot, so the high byte is the Agent's rangedDirection, not its stance.
        int action = (packet[14] & 0xFF) | ((packet[15] & 0xFF) << 8);
        assertEquals(AgentAttackExecutionProvider.bodyActionId("shot", null, WeaponType.GUN), action & 0x7FFF);
        assertEquals(0x8000, action & 0x8000);
    }
}
