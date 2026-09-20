/*
    BOT_ECONOMY.md § Data integrity and reconciliation — nightly cron shim.
*/
package net.server.task;

import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.bot.BotReconciliationTask;

public class BotReconciliationCronTask implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(BotReconciliationCronTask.class);

    @Override
    public void run() {
        if (!YamlConfig.config.bot_economy.ENABLED) return;
        try {
            new BotReconciliationTask().run();
        } catch (Throwable t) {
            log.error("BotReconciliationCronTask threw", t);
        }
    }
}
