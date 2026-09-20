/*
    BOT_ECONOMY.md § Telemetry — samples bot_economy_metrics on a tick.
*/
package net.server.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.bot.BotTelemetryService;

public class BotMetricsSampleTask implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(BotMetricsSampleTask.class);

    @Override
    public void run() {
        try {
            BotTelemetryService.sample();
        } catch (Throwable t) {
            log.error("BotMetricsSampleTask threw", t);
        }
    }
}
