package server.agents.capabilities.partyquest.epq;

import client.Character;
import server.life.EpqPoisonFlowerCaptureService;
import server.life.Monster;

import java.util.function.BiFunction;

/** Private EPQ implementation of the authored purification-marble catch action. */
final class AgentEpqCaptureRuntime {
    private AgentEpqCaptureRuntime() { }

    static boolean ready(Monster monster) {
        return EpqPoisonFlowerCaptureService.ready(monster);
    }

    static boolean capture(Character agent, Monster monster) {
        return capture(agent, monster, EpqPoisonFlowerCaptureService::capture);
    }

    /**
     * One Agent's catch of a flower through {@code transaction} (the stock catch). EPQ base fix 8 sends helpers to any
     * owner's flower, so an owner and a helper on two tick threads can throw at one flower at once: only the Agent
     * that claims the flower in the session throws, and the claim ends with the throw (the stock catch kills the
     * flower, so a later throw finds it gone and spends nothing).
     */
    static boolean capture(Character agent, Monster monster,
                           BiFunction<Character, Monster, EpqPoisonFlowerCaptureService.Result> transaction) {
        AgentEpqSession session = agent == null ? null : AgentEpqSessionRegistry.forMember(agent.getId());
        if (session == null || agent.getMapId() != AgentEpqDefinition.STAGE_FOUR_MAP
                || agent.getEventInstance() != session.eventInstance()
                || monster == null || agent.getMap().getMonsterByOid(monster.getObjectId()) != monster) {
            return false;
        }
        int flower = monster.getObjectId();
        if (!session.beginCapture(flower)) return false;
        try {
            if (transaction.apply(agent, monster) != EpqPoisonFlowerCaptureService.Result.CAPTURED) return false;
        } finally {
            session.endCapture(flower);
        }
        session.markProgress(System.currentTimeMillis());
        return true;
    }
}
