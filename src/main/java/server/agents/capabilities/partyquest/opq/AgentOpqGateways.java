package server.agents.capabilities.partyquest.opq;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.function.Supplier;

/**
 * The OPQ coordinator's gateways, resolved per call. Each gateway is one adapter singleton, so a call through
 * {@link #perCall} reaches the same object a captured field did; a test can now stand in for a gateway without the
 * coordinator, loaded once per JVM, keeping the first test's mock (or the real adapter) for every later test. The
 * coordinator keeps its field names (ACTIONS, OPQ), which AgentOpqBossCombatTest reads in its source.
 */
final class AgentOpqGateways {
    private AgentOpqGateways() {
    }

    /** A {@code type} whose every call goes to what {@code source} returns at that moment. */
    static <T> T perCall(Class<T> type, Supplier<? extends T> source) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> self == args[0];
                    case "hashCode" -> System.identityHashCode(self);
                    default -> "AgentOpqGateways.perCall(" + type.getSimpleName() + ")";
                };
            }
            try {
                return method.invoke(source.get(), args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        });
        return type.cast(proxy);
    }
}
