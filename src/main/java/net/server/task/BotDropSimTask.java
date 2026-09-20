/*
    BOT_ECONOMY.md § Component 1 — cron shim for DropSimulationTask.
*/
package net.server.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.bot.DropSimulationTask;

public class BotDropSimTask implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(BotDropSimTask.class);
    private final DropSimulationTask impl = new DropSimulationTask();

    @Override
    public void run() {
        try {
            impl.run();
        } catch (Throwable t) {
            log.error("BotDropSimTask threw", t);
        }
    }
}
