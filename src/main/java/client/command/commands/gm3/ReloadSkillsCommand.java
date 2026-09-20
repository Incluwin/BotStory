package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.SkillFactory;
import client.command.Command;

/**
 * Rebuild the SkillFactory skill map from Skill.wz. The existing `skills`
 * reference is volatile and swapped atomically by loadAllSkills(), so
 * readers during reload see either the old or the new fully-built map.
 *
 * Active buffs on players use the SkillStatEffect that was captured at
 * cast time and continue to their stored duration. Next cast picks up
 * the new effect data. Pass 'canceldebuffs' to force-cancel every buff
 * on the GM so they see the new values immediately.
 */
public class ReloadSkillsCommand extends Command {
    {
        setDescription("Reload Skill WZ cache. Syntax: !reloadskills [canceldebuffs]");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        long t0 = System.currentTimeMillis();
        SkillFactory.reloadAllSkills();
        long dt = System.currentTimeMillis() - t0;

        boolean cancel = params.length >= 1 && "canceldebuffs".equalsIgnoreCase(params[0]);
        if (cancel) {
            try {
                player.dispelDebuffs();
                player.cancelAllBuffs(true);
            } catch (Throwable ignored) {
            }
        }

        player.dropMessage(5, String.format(
                "Reloaded skills in %d ms%s. Next cast picks up the new effect data.",
                dt, cancel ? " (cancelled active buffs on you)" : ""));
    }
}
