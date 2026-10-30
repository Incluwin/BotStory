package client.command.commands.gm6;

import client.Character;
import client.Client;
import client.command.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.life.Monster;
import server.life.autonomy.balrog.EasyBalrogBodyBehavior;

/**
 * Test tooling for the Easy Balrog body cast crash repro (docs/COMPANION_BOSSING_P7A.md, "Body cast crash").
 * {@code hp <percent> [mobId]} lowers each live, non-fake mob on your map (or only mobId) to
 * max(1, floor(maxHp x percent / 100)); it never kills a mob and never raises HP.
 * {@code telegraph action|none} sets whether the body's skill casts send the skill1 action
 * ({@link EasyBalrogBodyBehavior#setTelegraphSkillActions}); the switch is not saved, so a restart restores action.
 */
public final class BalrogProbeCommand extends Command {
    static final String USAGE = "Syntax: !balrogprobe hp <percent> [mobId] | !balrogprobe telegraph [action|none]";
    private static final Logger log = LoggerFactory.getLogger(BalrogProbeCommand.class);

    {
        setDescription("Easy Balrog crash repro: lower mob HP on your map, or switch the body's cast action.");
    }

    @Override
    public void execute(Client client, String[] params) {
        Character gm = client.getPlayer();
        if (params.length >= 1 && params[0].equalsIgnoreCase("hp")) {
            lowerHp(gm, params);
        } else if (params.length >= 1 && params[0].equalsIgnoreCase("telegraph")) {
            telegraph(gm, params);
        } else {
            gm.yellowMessage(USAGE);
        }
    }

    private static void lowerHp(Character gm, String[] params) {
        int percent;
        Integer mobId = null;
        try {
            percent = Integer.parseInt(params.length > 1 ? params[1] : "");
            if (params.length > 2) {
                mobId = Integer.parseInt(params[2]);
            }
        } catch (NumberFormatException e) {
            gm.yellowMessage(USAGE);
            return;
        }
        if (percent < 0 || percent > 100) {
            gm.yellowMessage(USAGE);
            return;
        }
        int lowered = 0;
        int unchanged = 0;
        for (Monster mob : gm.getMap().getAllMonsters()) {
            if ((mobId != null && mob.getId() != mobId) || !mob.isAlive() || mob.isFake()) {
                continue;
            }
            long target = Math.max(1L, mob.getMaxHp() * percent / 100);
            long before = mob.getHp();
            if (before <= target) {
                unchanged++;
                continue;
            }
            mob.applyAndGetHpDamage(before - target, true);
            lowered++;
            gm.yellowMessage("Mob " + mob.getId() + " oid " + mob.getObjectId() + ": HP " + before + " -> "
                    + mob.getHp() + " of " + mob.getMaxHp() + ".");
        }
        gm.yellowMessage("balrogprobe hp " + percent + "%: lowered " + lowered + " mob(s), " + unchanged
                + " already at or below it.");
        log.info("gm balrogprobe hp: gm={} map={} percent={} mob={} lowered={} unchanged={}", gm.getName(),
                gm.getMapId(), percent, mobId == null ? "all" : mobId, lowered, unchanged);
    }

    private static void telegraph(Character gm, String[] params) {
        boolean before = EasyBalrogBodyBehavior.telegraphSkillActions();
        if (params.length > 1) {
            if (params[1].equalsIgnoreCase("action")) {
                EasyBalrogBodyBehavior.setTelegraphSkillActions(true);
            } else if (params[1].equalsIgnoreCase("none")) {
                EasyBalrogBodyBehavior.setTelegraphSkillActions(false);
            } else {
                gm.yellowMessage(USAGE);
                return;
            }
        }
        boolean now = EasyBalrogBodyBehavior.telegraphSkillActions();
        gm.yellowMessage(now
                ? "balrogprobe telegraph = action: the Easy Balrog body's skill casts play skill1 and its sound."
                + " This is the default."
                : "balrogprobe telegraph = none: the Easy Balrog body's skill casts send no action"
                + " (no skill1, no sound). A restart resets it to action.");
        log.info("gm balrogprobe telegraph: gm={} map={} telegraph={} was={}", gm.getName(), gm.getMapId(),
                name(now), name(before));
    }

    private static String name(boolean telegraph) {
        return telegraph ? "action" : "none";
    }
}
