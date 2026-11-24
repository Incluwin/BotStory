package server.living.population.events;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** An in-memory living_world_event: it outlives a calendar, as the table outlives a restart. */
final class EventFakes {
    private EventFakes() {
    }

    static final class Store implements WorldEvents.Store {
        final List<WorldEvents.Occurrence> rows = new ArrayList<>();
        final Map<Long, Double> samples = new HashMap<>();
        boolean down;

        private WorldEvents.Occurrence replace(WorldEvents.Occurrence old, long endsAt, String state, int channel, String result) {
            WorldEvents.Occurrence next = new WorldEvents.Occurrence(old.id(), old.event(), old.number(), old.startsAtMs(),
                    endsAt, state, channel, old.site(), result);
            rows.set(rows.indexOf(old), next);
            return next;
        }

        WorldEvents.Occurrence row(long id) {
            return rows.stream().filter(row -> row.id() == id).findFirst().orElse(null);
        }

        WorldEvents.Occurrence of(String event) {
            return rows.stream().filter(row -> row.event().equals(event)).findFirst().orElse(null);
        }

        @Override
        public List<WorldEvents.Occurrence> open() {
            return down ? null : rows.stream().filter(row -> row.state().equals("PLANNED") || row.state().equals("RUNNING")).toList();
        }

        @Override
        public List<WorldEvents.Occurrence> between(long fromMs, long toMs) {
            return down ? null : rows.stream().filter(row -> row.startsAtMs() >= fromMs && row.startsAtMs() < toMs).toList();
        }

        @Override
        public Boolean exists(String eventId, int occurrence) {
            return down ? null : (Boolean) rows.stream().anyMatch(row -> row.event().equals(eventId) && row.number() == occurrence);
        }

        @Override
        public boolean insert(String eventId, int occurrence, long startsAtMs, long endsAtMs, int channel, String site) {
            if (down || exists(eventId, occurrence)) {
                return false;
            }
            return rows.add(new WorldEvents.Occurrence(rows.size() + 1L, eventId, occurrence, startsAtMs, endsAtMs, "PLANNED",
                    channel, site, ""));
        }

        @Override
        public boolean claim(long id, long endsAtMs, int channel) {
            WorldEvents.Occurrence row = row(id);
            return row != null && row.state().equals("PLANNED") && replace(row, endsAtMs, "RUNNING", channel, "") != null;
        }

        @Override
        public boolean finish(long id, String state, String result) {
            WorldEvents.Occurrence row = row(id);
            return row != null && (row.state().equals("PLANNED") || row.state().equals("RUNNING"))
                    && replace(row, row.endsAtMs(), state, row.channel(), result) != null;
        }

        @Override
        public Map<Long, Double> samples(int days) {
            return down ? null : samples;
        }
    }
}
