package server.agents.capabilities.shop;

import client.Character;
import server.agents.runtime.AgentRuntimeEntry;

import java.awt.Point;

public final class AgentShopVisitTickService {
    private AgentShopVisitTickService() {
    }

    public record Result(boolean consumedTick, Point targetPos) {
    }

    public record Hooks(ShopVisitTick shopVisitTick,
                        MovementCore movementCore,
                        HoldPosition holdPosition) {
    }

    @FunctionalInterface
    public interface ShopVisitTick {
        boolean tick(AgentRuntimeEntry entry, Character agent);
    }

    @FunctionalInterface
    public interface MovementCore {
        void step(AgentRuntimeEntry entry, Point targetPosition, boolean runAiTick);
    }

    /** Physics-only tick (land, idle stance) with no walking toward any target. */
    @FunctionalInterface
    public interface HoldPosition {
        void tick(AgentRuntimeEntry entry, Character agent);
    }

    public static Result tickShopVisitIfPending(AgentRuntimeEntry entry,
                                                Character agent,
                                                boolean runAiTick,
                                                Hooks hooks) {
        if (!AgentShopStateRuntime.shopVisitPending(entry)) {
            return new Result(false, null);
        }

        boolean consumed = hooks.shopVisitTick().tick(entry, agent);
        Point targetPos = AgentShopStateRuntime.activeShopTargetPosition(entry);
        if (!consumed && AgentShopStateRuntime.shopApproachDelayMs(entry) > 0) {
            return new Result(true, targetPos);
        }
        if (AgentShopStateRuntime.shopSequenceActive(entry)) {
            // The transaction is open: stay put. Walking on toward the exact approach point
            // (often on the shopkeeper's counter) carried Agents out of range mid-purchase.
            hooks.holdPosition().tick(entry, agent);
            return new Result(true, targetPos);
        }
        if (targetPos != null) {
            hooks.movementCore().step(entry, targetPos, runAiTick);
        }
        return new Result(true, targetPos);
    }
}
