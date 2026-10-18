// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package org.wpilib.system;

import static org.wpilib.units.Units.Seconds;

import java.io.Closeable;
import java.util.ArrayList;
import org.wpilib.units.measure.Time;

/**
 * Host-clock watchdog with no HAL notifier or background thread.
 *
 * <p>Timeouts are delivered at host tick boundaries or when expiry is queried/disabled. They cannot
 * interrupt a blocked JavaScript thread. Like the host-driven robot, this class is single-threaded.
 */
public class Watchdog implements Closeable, Comparable<Watchdog> {
  private static final ArrayList<Watchdog> active = new ArrayList<>();

  private final Runnable callback;
  private final Tracer tracer = new Tracer();
  private double timeout;
  private double startTime;
  private double expirationTime;
  private double lastTimeoutPrint;
  private boolean enabled;
  private boolean expired;
  private boolean suppressTimeoutMessage;

  public Watchdog(double timeout, Runnable callback) {
    this.timeout = timeout;
    this.callback = callback;
  }

  public Watchdog(Time timeout, Runnable callback) {
    this(timeout.in(Seconds), callback);
  }

  @Override
  public void close() {
    disable();
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof Watchdog watchdog
        && Double.compare(expirationTime, watchdog.expirationTime) == 0;
  }

  @Override
  public int hashCode() {
    return Double.hashCode(expirationTime);
  }

  @Override
  public int compareTo(Watchdog rhs) {
    return Double.compare(expirationTime, rhs.expirationTime);
  }

  public double getTime() {
    return Timer.getMonotonicTimestamp() - startTime;
  }

  public void setTimeout(double timeout) {
    this.timeout = timeout;
    enable();
  }

  public double getTimeout() {
    return timeout;
  }

  public boolean isExpired() {
    checkExpired();
    return expired;
  }

  public void addEpoch(String epochName) {
    tracer.addEpoch(epochName);
  }

  public void printEpochs() {
    tracer.printEpochs(System.err::print);
  }

  public void reset() {
    enable();
  }

  public void enable() {
    startTime = Timer.getMonotonicTimestamp();
    expirationTime = startTime + timeout;
    tracer.clearEpochs();
    expired = false;
    if (!enabled) {
      active.add(this);
      enabled = true;
    }
  }

  public void disable() {
    checkExpired();
    remove();
  }

  public void suppressTimeoutMessage(boolean suppress) {
    suppressTimeoutMessage = suppress;
  }

  private void remove() {
    if (!enabled) {
      return;
    }
    // SDK equality compares deadlines; distinct watchdogs can have the same deadline.
    for (int i = 0; i < active.size(); i++) {
      if (active.get(i) == this) {
        active.set(i, active.get(active.size() - 1));
        active.remove(active.size() - 1);
        break;
      }
    }
    enabled = false;
  }

  private void checkExpired() {
    if (!enabled) {
      return;
    }
    double now = Timer.getMonotonicTimestamp();
    if (now < expirationTime) {
      return;
    }
    remove();
    expired = true;
    if (now - lastTimeoutPrint > 1.0) {
      lastTimeoutPrint = now;
      if (!suppressTimeoutMessage) {
        System.err.printf("Watchdog not fed within %.6fs%n", timeout);
      }
    }
    callback.run();
  }

  /** Delivers due timeouts in deadline order from the serialized host tick. */
  public static void poll() {
    while (!active.isEmpty()) {
      Watchdog next = null;
      double now = Timer.getMonotonicTimestamp();
      for (int i = 0; i < active.size(); i++) {
        Watchdog watchdog = active.get(i);
        if (watchdog.expirationTime <= now
            && (next == null || watchdog.expirationTime < next.expirationTime)) {
          next = watchdog;
        }
      }
      if (next == null) {
        return;
      }
      next.checkExpired();
    }
  }
}
