package soloMapling.server;

import net.server.Server;
import net.server.channel.Channel;

public class SoloMaplingConstants {

    public static final Channel mainChannel = Server.getInstance().getChannel(0, 1);

    public static class GameConstants {
        public static final int WORLD_SCANIA = 0;
        public static final int CHANNEL_1 = 1;
        // Synthetic id base for ephemeral social bots. Must sit ABOVE any real
        // character auto-increment id (and our economy-bot rows) so bot ids never
        // collide with real players in PlayerStorage. 1e9 leaves ~1.1B headroom
        // under Integer.MAX_VALUE — far more than the ~650 bots we spawn.
        public static final int BOT_BASE_ID = 1_000_000_000;
    }

}
