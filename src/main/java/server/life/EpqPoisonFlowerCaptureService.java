package server.life;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.ItemId;
import constants.id.MobId;
import server.agents.capabilities.partyquest.epq.AgentEpqDefinition;
import server.agents.capabilities.partyquest.epq.AgentEpqMemberState;
import server.agents.capabilities.partyquest.epq.AgentEpqSession;
import server.agents.capabilities.partyquest.epq.AgentEpqSessionRegistry;
import server.maps.Reactor;
import tools.PacketCreator;

/** Authoritative EPQ Poison Flower catch transaction shared by humans and Agents. */
public final class EpqPoisonFlowerCaptureService {
    private EpqPoisonFlowerCaptureService() { }

    public static boolean ready(Monster monster) {
        return monster != null && monster.isAlive() && monster.getId() == MobId.POISON_FLOWER
                && monster.getHp() < (monster.getMaxHp() / 10L) * 4L;
    }

    /** Agents must weaken these authored catch targets, never kill them with ordinary combat. */
    public static boolean preserveForAgentCapture(Character attacker, Monster monster) {
        if (attacker == null || monster == null || monster.getId() != MobId.POISON_FLOWER
                || attacker.getMapId() != AgentEpqDefinition.STAGE_FOUR_MAP) return false;
        AgentEpqSession session = AgentEpqSessionRegistry.forMember(attacker.getId());
        return session != null && session.eventInstance() != null
                && attacker.getEventInstance() == session.eventInstance()
                && (!session.companionAdopted() || agentMember(session, attacker.getId()));
    }

    /** Preserve lure targets until they reach the purification tree's reactor area. */
    public static boolean preserveForAgentLure(Character attacker, Monster monster) {
        if (attacker == null || monster == null
                || monster.getId() != AgentEpqDefinition.STAGE_TWO_MOB
                || attacker.getMapId() != AgentEpqDefinition.STAGE_TWO_MAP
                || monster.getMap() == null || monster.getPosition() == null) return false;
        AgentEpqSession session = AgentEpqSessionRegistry.forMember(attacker.getId());
        if (session == null || session.eventInstance() == null
                || attacker.getEventInstance() != session.eventInstance()
                || (session.companionAdopted() && !agentMember(session, attacker.getId()))) return false;
        Reactor pond = monster.getMap().getReactorById(AgentEpqDefinition.POND_REACTOR);
        return pond != null && pond.getArea() != null
                && !pond.getArea().contains(monster.getPosition());
    }

    /**
     * A companion run plays EPQ stock: only its companions hold back, and its human leader hits Poison Flowers and Stone
     * Bugs as any player would (a flower he kills revives, Mob.wz 9300175).
     */
    private static boolean agentMember(AgentEpqSession session, int characterId) {
        AgentEpqMemberState member = session.member(characterId);
        return member != null && member.memberType() == AgentEpqMemberState.MemberType.AGENT;
    }

    public static Result capture(Character character, Monster monster) {
        if (character == null || monster == null || character.getMap() == null
                || character.getMap().getMonsterByOid(monster.getObjectId()) != monster
                || monster.getId() != MobId.POISON_FLOWER) return Result.INVALID_TARGET;
        if (!ready(monster)) return Result.NOT_READY;
        if (character.getInventory(InventoryType.USE)
                .countById(ItemId.EPQ_PURIFICATION_MARBLE) < 1) return Result.NO_MARBLE;
        if (!character.canHold(ItemId.EPQ_MONSTER_MARBLE, 1)) return Result.NO_INVENTORY_SPACE;
        character.getMap().broadcastMessage(PacketCreator.catchMonster(
                monster.getObjectId(), ItemId.EPQ_PURIFICATION_MARBLE, (byte) 1));
        character.getMap().killMonster(monster, null, false, (short) 0);
        InventoryManipulator.removeById(character.getClient(), InventoryType.USE,
                ItemId.EPQ_PURIFICATION_MARBLE, 1, true, true);
        InventoryManipulator.addById(character.getClient(), ItemId.EPQ_MONSTER_MARBLE,
                (short) 1, "", -1);
        return Result.CAPTURED;
    }

    public enum Result { CAPTURED, NOT_READY, NO_MARBLE, NO_INVENTORY_SPACE, INVALID_TARGET }
}
