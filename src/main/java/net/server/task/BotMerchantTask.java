/*
    BOT_ECONOMY.md § Component 3 — cron shim for BotMerchantManager.tick().
*/
package net.server.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.bot.BotMerchantManager;

/**
 * Cron shim: drives {@link BotMerchantManager#tick()} on the configured
 * cadence. Registered from {@code Server.initializeTimelyTasks}.
 */
public class BotMerchantTask implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(BotMerchantTask.class);

    @Override
    public void run() {
        try {
            BotMerchantManager.tick();
        } catch (Throwable t) {
            log.error("BotMerchantTask tick threw", t);
        }
    }
}
