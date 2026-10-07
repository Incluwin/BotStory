package testutil;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mockito;

/**
 * Drops every inline mock after each top-level test class.
 *
 * <p>Mockito's inline mock maker keeps each mock's handler, with every call the mock recorded,
 * in one static weak-keyed map. Mocks of finished classes stayed in that map, so the full suite
 * (one surefire JVM) piled up about 20,000 mocks and over a million recorded calls.
 * Registered for every test class by autodetection (src/test/resources/junit-platform.properties).
 */
public final class ClearInlineMocksExtension implements AfterAllCallback {
    @Override
    public void afterAll(ExtensionContext context) {
        if (!context.getRequiredTestClass().isAnnotationPresent(Nested.class)) {
            Mockito.framework().clearInlineMocks();
        }
    }
}
