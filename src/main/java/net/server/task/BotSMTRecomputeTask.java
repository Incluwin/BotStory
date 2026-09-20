/*
    BOT_ECONOMY.md § Server Maturity Tier — daily SMT recompute cron.
*/
package net.server.task;

import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.bot.BotSmtService;

public class BotSMTRecomputeTask implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(BotSMTRecomputeTask.class);

    @Override
    public void run() {
        if (!YamlConfig.config.bot_economy.ENABLED) return;
        try {
            BotSmtService.recompute();
        } catch (Throwable t) {
            log.error("BotSMTRecomputeTask threw", t);
        }
    }
}
