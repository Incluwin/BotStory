package config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerConfigAddressOverridesTest {

    @Test
    void environmentAddressesReplaceTheConfiguredOnes() {
        ServerConfig config = configured();

        config.applyAddressOverrides(Map.of(
                "SERVER_HOST", " 203.0.113.7 ",
                "SERVER_LANHOST", "192.168.1.20",
                "SERVER_TAILSCALEHOST", "100.64.0.9"));

        assertEquals("203.0.113.7", config.HOST);
        assertEquals("192.168.1.20", config.LANHOST);
        assertEquals("100.64.0.9", config.TAILSCALEHOST);
        assertEquals("127.0.0.1", config.LOCALHOST, "loopback players always get LOCALHOST");
    }

    @Test
    void missingOrBlankVariablesKeepConfigYaml() {
        ServerConfig config = configured();

        config.applyAddressOverrides(Map.of("SERVER_HOST", "  ", "SERVER_LANHOST", ""));

        assertEquals("127.0.0.1", config.HOST);
        assertEquals("127.0.0.1", config.LANHOST);
        assertEquals("127.0.0.1", config.TAILSCALEHOST);
    }

    @Test
    void aSecondServerMovesItsPortsAndDatabase() {
        ServerConfig config = configured();

        config.applySecondServerOverrides(Map.of(
                "SERVER_LOGIN_PORT", "18484",
                "SERVER_CHANNEL_BASE_PORT", " 17575 ",
                "ADMIN_CONTROL_PORT_BIND", "18480",
                "DB_URL_FORMAT", "jdbc:mysql://%s:3306/cosmic_check"));

        assertEquals(18484, config.LOGIN_PORT);
        assertEquals(17575, config.CHANNEL_BASE_PORT);
        assertEquals(18480, config.ADMIN_CONTROL_PORT_BIND);
        assertEquals("jdbc:mysql://%s:3306/cosmic_check", config.DB_URL_FORMAT);
    }

    @Test
    void theLiveServerKeepsItsPortsWithoutTheVariables() {
        ServerConfig config = configured();

        config.applySecondServerOverrides(Map.of("SERVER_LOGIN_PORT", "", "SERVER_CHANNEL_BASE_PORT", "seven",
                "ADMIN_CONTROL_PORT_BIND", "70000"));

        assertEquals(8484, config.LOGIN_PORT);
        assertEquals(7575, config.CHANNEL_BASE_PORT);
        assertEquals(8480, config.ADMIN_CONTROL_PORT_BIND);
        assertEquals("jdbc:mysql://%s:3306/cosmic", config.DB_URL_FORMAT);
    }

    private static ServerConfig configured() {
        ServerConfig config = new ServerConfig();
        config.HOST = "127.0.0.1";
        config.LANHOST = "127.0.0.1";
        config.LOCALHOST = "127.0.0.1";
        config.TAILSCALEHOST = "127.0.0.1";
        config.ADMIN_CONTROL_PORT_BIND = 8480;
        config.DB_URL_FORMAT = "jdbc:mysql://%s:3306/cosmic";
        return config;
    }
}
