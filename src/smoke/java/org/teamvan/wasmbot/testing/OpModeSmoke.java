package org.teamvan.wasmbot.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.teamvan.wasmbot.JsBridge;
import org.teamvan.wasmbot.WasmRuntime;
import org.wpilib.framework.OpModeRobot;
import org.wpilib.hardware.hal.RobotMode;
import org.wpilib.opmode.OpMode;

/** Registered opmodes must follow the browser selection through the real OpModeRobot lifecycle. */
final class OpModeSmoke {
  private OpModeSmoke() {}

  static void run() {
    AtomicLong clock = new AtomicLong();
    WasmRuntime.setClock(clock::get);
    List<String> events = new ArrayList<>();
    ProbeRobot robot = new ProbeRobot(events);
    try {
      robot.addOpMode(
          RobotMode.TELEOPERATED,
          "Drive",
          "Example",
          "Teleop drive",
          () -> new ProbeOpMode("drive", events));
      robot.addOpMode(RobotMode.AUTONOMOUS, "Auto", () -> new ProbeOpMode("auto", events));
      // Duplicate names for the same mode must be rejected before they reach the browser.
      try {
        robot.addOpMode(RobotMode.AUTONOMOUS, "Auto", () -> new ProbeOpMode("duplicate", events));
        throw new AssertionError("duplicate opmode registration must fail");
      } catch (IllegalArgumentException expected) {
        // Expected.
      }
      robot.publishOpModes();

      WasmRuntime.setEnabled(false);
      WasmRuntime.setRobotMode(RobotMode.TELEOPERATED);
      WasmRuntime.setOpMode(0, "Drive");
      robot.startCompetition();
      step(robot, clock, events);
      expect(events, "drive:created", "drive:disabled", "robot");

      WasmRuntime.setEnabled(true);
      step(robot, clock, events);
      expect(events, "drive:start", "drive:periodic", "robot");

      WasmRuntime.setEnabled(false);
      step(robot, clock, events);
      expect(events, "drive:end", "drive:close", "robot");

      // A disabled opmode is reconstructed on the next loop, matching the SDK's DS behavior.
      step(robot, clock, events);
      expect(events, "drive:created", "drive:disabled", "robot");

      WasmRuntime.setRobotMode(RobotMode.AUTONOMOUS);
      WasmRuntime.setOpMode(0, "Auto");
      step(robot, clock, events);
      expect(events, "drive:close", "auto:created", "auto:disabled", "robot");

      WasmRuntime.setEnabled(true);
      step(robot, clock, events);
      expect(events, "auto:start", "auto:periodic", "robot");

      WasmRuntime.setRobotMode(RobotMode.UNKNOWN);
      step(robot, clock, events);
      expect(events, "auto:end", "auto:close", "none", "robot");
    } finally {
      robot.close();
      WasmRuntime.setClock(() -> (long) JsBridge.nowNanos());
      WasmRuntime.setEnabled(false);
      WasmRuntime.setRobotMode(RobotMode.UNKNOWN);
      WasmRuntime.setOpMode(0, "");
      WasmRuntime.refresh();
    }
  }

  private static void step(ProbeRobot robot, AtomicLong clock, List<String> events) {
    clock.addAndGet(20_000_000);
    robot.wasmTick();
  }

  private static void expect(List<String> events, String... expected) {
    if (!events.equals(List.of(expected))) {
      throw new AssertionError("opmode lifecycle: " + events + " != " + List.of(expected));
    }
    events.clear();
  }

  private static final class ProbeRobot extends OpModeRobot {
    private final List<String> events;

    ProbeRobot(List<String> events) {
      this.events = events;
    }

    @Override
    public void robotPeriodic() {
      events.add("robot");
    }

    @Override
    public void nonePeriodic() {
      events.add("none");
    }
  }

  private static final class ProbeOpMode implements OpMode {
    private final String name;
    private final List<String> events;

    ProbeOpMode(String name, List<String> events) {
      this.name = name;
      this.events = events;
      events.add(name + ":created");
    }

    @Override
    public void disabledPeriodic() {
      events.add(name + ":disabled");
    }

    @Override
    public void start() {
      events.add(name + ":start");
    }

    @Override
    public void periodic() {
      events.add(name + ":periodic");
    }

    @Override
    public void end() {
      events.add(name + ":end");
    }

    @Override
    public void close() {
      events.add(name + ":close");
    }
  }
}
