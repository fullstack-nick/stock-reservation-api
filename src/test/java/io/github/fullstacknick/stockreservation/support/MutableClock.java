package io.github.fullstacknick.stockreservation.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

public class MutableClock extends Clock {
    public static final Instant INITIAL = Instant.parse("2026-11-01T09:00:00Z");
    private final AtomicReference<Instant> now;
    private final ZoneId zone;
    public MutableClock() { this(new AtomicReference<>(INITIAL), ZoneId.of("Europe/Berlin")); }
    private MutableClock(AtomicReference<Instant> now, ZoneId zone) { this.now = now; this.zone = zone; }
    public void set(Instant value) { now.set(value); }
    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId zone) { return new MutableClock(now, zone); }
    @Override public Instant instant() { return now.get(); }
}
