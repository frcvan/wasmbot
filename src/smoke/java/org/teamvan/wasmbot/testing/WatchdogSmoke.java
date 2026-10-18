package org.teamvan.wasmbot.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.teamvan.wasmbot.JsBridge;
import org.teamvan.wasmbot.WasmRuntime;
import org.wpilib.system.Watchdog;

/** Deterministic host-clock watchdog checks; no HAL notifier or wall-clock sleeps. */
final class WatchdogSmoke {
  private WatchdogSmoke() {}

  static void run() {
    AtomicLong clock = new AtomicLong();
    ArrayList<String> events = new ArrayList<>();
    WasmRuntime.setClock(clock::get);
    try (Watchdog first = new Watchdog(0.02, () -> events.add("first"));
        Watchdog second = new Watchdog(0.02, () -> events.add("second"))) {
      first.enable();
      second.enable();
      // Removing one watchdog must not remove a different watchdog with an equal deadline.
      second.disable();
      clock.set(19_999_999);
      Watchdog.poll();
      expect(events);
      clock.set(20_000_000);
      Watchdog.poll();
      Watchdog.poll();
      expect(events, "first");
      if (!first.isExpired() || second.isExpired()) {
        throw new AssertionError("Watchdog expiry must be latched only for the due timer");
      }

      events.clear();
      first.reset();
      second.setTimeout(0.01);
      clock.set(40_000_000);
      Watchdog.poll();
      expect(events, "second", "first");

      events.clear();
      first.reset();
      if (first.isExpired()) {
        throw new AssertionError("Reset must clear watchdog expiry");
      }
      clock.set(60_000_000);
      first.disable();
      Watchdog.poll();
      expect(events, "first");

      events.clear();
      first.enable();
      first.close();
      clock.set(80_000_000);
      Watchdog.poll();
      expect(events);

      try (Watchdog rearm = new Watchdog(0.01, first::reset)) {
        rearm.enable();
        clock.set(90_000_000);
        Watchdog.poll();
        clock.set(109_999_999);
        Watchdog.poll();
        expect(events);
        clock.set(110_000_000);
        Watchdog.poll();
        expect(events, "first");
      }
    } finally {
      WasmRuntime.setClock(() -> (long) JsBridge.nowNanos());
    }
  }

  private static void expect(List<String> events, String... expected) {
    if (!events.equals(List.of(expected))) {
      throw new AssertionError("Expected " + List.of(expected) + ", got " + events);
    }
  }
}
