package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.life.LifeFactory;
import server.life.MonsterInformationProvider;
import server.maps.MapObject;
import server.maps.MapObjectType;

import java.util.Collections;
import java.util.List;

/**
 * Flush LifeFactory and MonsterInformationProvider caches so new mob spawns
 * read fresh Mob.wz / String.wz values and a fresh drop_data row.
 *
 * Mobs already on the GM's map carry stats that were copied at spawn time
 * and are unaffected by the reload. We kill them by default so the next
 * visit (or !spawn) reflects the new data. Pass 'keep' to skip the kill.
 */
public class ReloadMobsCommand extends Command {
    {
        setDescription("Reload Mob WZ + drop caches. Syntax: !reloadmobs [keep]");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        long t0 = System.currentTimeMillis();
        LifeFactory.clearCaches();
        MonsterInformationProvider.getInstance().clearMobCaches();
        long dt = System.currentTimeMillis() - t0;

        boolean keep = params.length >= 1 && "keep".equalsIgnoreCase(params[0]);
        int killed = 0;
        if (!keep && player.getMap() != null) {
            try {
                List<MapObject> mobs = player.getMap().getMapObjectsInRange(
                        player.getPosition(), Double.POSITIVE_INFINITY,
                        Collections.singletonList(MapObjectType.MONSTER));
                for (MapObject mo : mobs) {
                    if (mo instanceof server.life.Monster) {
                        server.life.Monster m = (server.life.Monster) mo;
                        player.getMap().killMonster(m, player, false, (short) 0);
                        killed++;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        player.dropMessage(5, String.format(
                "Reloaded mobs in %d ms — killed %d existing on this map%s. "
                + "Next !spawn / new drops use the fresh WZ data.",
                dt, killed, keep ? " (skipped)" : ""));
    }
}
