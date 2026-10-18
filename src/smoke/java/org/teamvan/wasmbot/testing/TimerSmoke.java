package org.teamvan.wasmbot.testing;

import java.util.concurrent.atomic.AtomicLong;
import org.teamvan.wasmbot.WasmRuntime;
import org.wpilib.system.RobotController;
import org.wpilib.system.Timer;

/** The SDK stopwatch must remain usable against the host-backed robot clock. */
final class TimerSmoke {
  private TimerSmoke() {}

  static void run() {
    AtomicLong clock = new AtomicLong(5_000_000_000L);
    RobotController.setTimeSource(clock::get);
    try {
      Timer timer = new Timer();
      require(!timer.isRunning() && timer.get() == 0, "new stopwatch is stopped");
      timer.start();
      clock.addAndGet(20_000_000);
      timer.start();
      near(timer.get(), 0.02);
      require(timer.hasElapsed(0.02) && !timer.hasElapsed(0.021), "elapsed boundary");
      timer.stop();
      clock.addAndGet(30_000_000);
      near(timer.get(), 0.02);
      timer.start();
      clock.addAndGet(10_000_000);
      near(timer.get(), 0.03);
      timer.restart();
      near(timer.get(), 0);
      clock.addAndGet(30_000_000);
      require(timer.advanceIfElapsed(0.02), "advance elapsed interval");
      near(timer.get(), 0.01);
      require(!timer.advanceIfElapsed(0.02), "advance retains remainder");
      Timer started = Timer.createStarted();
      clock.addAndGet(5_000_000);
      near(started.get(), 0.005);
      timer.reset();
      require(timer.isRunning(), "reset preserves running state");
      near(timer.get(), 0);
    } finally {
      RobotController.setTimeSource(WasmRuntime::nowNanos);
    }
  }

  private static void near(double actual, double expected) {
    require(Math.abs(actual - expected) < 1e-12, actual + " != " + expected);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
