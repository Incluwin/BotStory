package server.economy.ledger;

import client.Character;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import server.integration.AgentPresence;

/**
 * Every meso created, destroyed or moved, summed by account and holder (docs/ECONOMY_PLAN.md E0).
 *
 * <p>The purse hook in {@link Character#gainMeso} and {@link Character#tryPayMeso} books each change of a character's
 * meso to the account of the innermost {@link #as} scope open on the thread, or {@link MesoAccount#UNATTRIBUTED}. A
 * change outside a purse (a bench haul, a DB-only purse write) is booked with {@link #record}. {@link EconomyLedger}
 * drains the sums into {@code economy_flow_daily} once a minute. Counting is lock-free and cheap enough for every kill.</p>
 */
public final class MesoFlows {
    /** One account's sums for one holder kind: meso in, meso out (a positive number), and how many changes. */
    public record Sum(long in, long out, long events) {
        public long net() {
            return in - out;
        }
    }

    private record Key(MesoAccount account, MesoHolder holder) {
    }

    private static final class Counter {
        final AtomicLong in = new AtomicLong();
        final AtomicLong out = new AtomicLong();
        final AtomicLong events = new AtomicLong();
    }

    private static final ThreadLocal<MesoAccount> CURRENT = new ThreadLocal<>();
    private static final Map<Key, Counter> COUNTERS = new ConcurrentHashMap<>();
    private static volatile Function<Character, MesoHolder> classifier = MesoFlows::defaultHolder;
    private static volatile boolean enabled = true;

    private MesoFlows() {
    }

    /** The account a scope books to; closing it restores the scope it was opened inside. */
    public static final class Scope implements AutoCloseable {
        private final MesoAccount previous;

        private Scope(MesoAccount previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** Books every purse change on this thread to {@code account} until the scope closes. */
    public static Scope as(MesoAccount account) {
        MesoAccount previous = CURRENT.get();
        CURRENT.set(account);
        return new Scope(previous);
    }

    /** The purse hook: {@code chr}'s meso changed by {@code delta} (after the overflow clamp). */
    public static void purse(Character chr, long delta) {
        if (delta == 0 || !enabled || chr == null) {
            return;
        }
        MesoAccount account = CURRENT.get();
        record(account == null ? MesoAccount.UNATTRIBUTED : account, holder(chr), delta);
    }

    /** Books a change that did not go through a purse. */
    public static void record(MesoAccount account, MesoHolder holder, long delta) {
        if (delta == 0 || !enabled) {
            return;
        }
        Counter counter = COUNTERS.computeIfAbsent(new Key(account, holder), key -> new Counter());
        if (delta > 0) {
            counter.in.addAndGet(delta);
        } else {
            counter.out.addAndGet(-delta);
        }
        counter.events.incrementAndGet();
    }

    /**
     * Moves {@code amount} already booked to {@code from} over to {@code to} without counting a second change: a sale's
     * tax, which the purse hook saw as part of the seller's (smaller) proceeds.
     */
    public static void reclassify(MesoHolder holder, MesoAccount from, MesoAccount to, long amount) {
        if (amount <= 0 || !enabled) {
            return;
        }
        COUNTERS.computeIfAbsent(new Key(from, holder), key -> new Counter()).in.addAndGet(amount);
        COUNTERS.computeIfAbsent(new Key(to, holder), key -> new Counter()).out.addAndGet(amount);
    }

    public static MesoHolder holder(Character chr) {
        try {
            MesoHolder holder = classifier.apply(chr);
            return holder == null ? MesoHolder.HUMAN : holder;
        } catch (RuntimeException failure) {
            return MesoHolder.HUMAN;
        }
    }

    /** The living world installs the citizen-aware classifier at start; null puts the default back. */
    public static void installClassifier(Function<Character, MesoHolder> installed) {
        classifier = installed == null ? MesoFlows::defaultHolder : installed;
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** Takes the sums counted since the last drain and starts counting from zero. */
    public static Map<MesoAccount, Map<MesoHolder, Sum>> drain() {
        Map<MesoAccount, Map<MesoHolder, Sum>> out = new EnumMap<>(MesoAccount.class);
        for (Map.Entry<Key, Counter> entry : COUNTERS.entrySet()) {
            Counter counter = entry.getValue();
            Sum sum = new Sum(counter.in.getAndSet(0), counter.out.getAndSet(0), counter.events.getAndSet(0));
            if (sum.in() != 0 || sum.out() != 0 || sum.events() != 0) {
                out.computeIfAbsent(entry.getKey().account(), account -> new EnumMap<>(MesoHolder.class))
                        .put(entry.getKey().holder(), sum);
            }
        }
        return out;
    }

    /** Without the living world's classifier: any Agent is a bot, else a GM or a player. */
    public static MesoHolder defaultHolder(Character chr) {
        if (AgentPresence.isAgent(chr)) {
            return MesoHolder.BOT;
        }
        return chr.isGM() ? MesoHolder.GM : MesoHolder.HUMAN;
    }
}
