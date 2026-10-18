package org.teamvan.wasmbot.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.teamvan.wasmbot.JsBridge;
import org.teamvan.wasmbot.WasmRuntime;
import org.wpilib.driverstation.RobotState;
import org.wpilib.framework.TimedRobot;
import org.wpilib.hardware.hal.RobotMode;

/** Enabled state and mode selection must independently drive the real iterative lifecycle. */
final class ModeSmoke {
  private ModeSmoke() {}

  static void run() {
    AtomicLong clock = new AtomicLong();
    WasmRuntime.setClock(clock::get);
    Probe robot = new Probe();
    try {
      WasmRuntime.setEnabled(false);
      WasmRuntime.setRobotMode(RobotMode.AUTONOMOUS);
      require(RobotState.isAutonomous() && !RobotState.isAutonomousEnabled(), "disabled selection");
      robot.startCompetition();
      step(robot, clock);
      expect(robot, "disabledInit", "disabledPeriodic", "robot", "simulation");
      WasmRuntime.setEnabled(true);
      step(robot, clock);
      require(RobotState.isAutonomousEnabled() && !RobotState.isTeleop(), "autonomous enabled");
      expect(robot, "disabledExit", "autoInit", "autoPeriodic", "robot", "simulation");
      step(robot, clock);
      expect(robot, "autoPeriodic", "robot", "simulation");
      WasmRuntime.setRobotMode(RobotMode.TELEOPERATED);
      step(robot, clock);
      require(RobotState.isTeleopEnabled() && !RobotState.isAutonomous(), "teleop enabled");
      expect(robot, "autoExit", "teleopInit", "teleopPeriodic", "robot", "simulation");
      WasmRuntime.setRobotMode(RobotMode.UTILITY);
      step(robot, clock);
      require(RobotState.isUtilityEnabled(), "utility enabled");
      expect(robot, "teleopExit", "utilityInit", "utilityPeriodic", "robot", "simulation");
      WasmRuntime.setEnabled(false);
      step(robot, clock);
      require(RobotState.isUtility() && !RobotState.isUtilityEnabled(), "disabled utility");
      expect(robot, "utilityExit", "disabledInit", "disabledPeriodic", "robot", "simulation");
      WasmRuntime.setRobotMode(RobotMode.UNKNOWN);
      WasmRuntime.setEnabled(true);
      step(robot, clock);
      require(!RobotState.isTeleop() && !RobotState.isAutonomous(), "unknown is not teleop");
      expect(robot, "disabledPeriodic", "robot", "simulation");
    } finally {
      robot.close();
      WasmRuntime.setClock(() -> (long) JsBridge.nowNanos());
      WasmRuntime.refresh();
    }
  }

  private static void step(Probe robot, AtomicLong clock) {
    clock.addAndGet(20_000_000);
    robot.wasmTick();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static void expect(Probe robot, String... expected) {
    require(robot.events.equals(List.of(expected)), "mode lifecycle: " + robot.events);
    robot.events.clear();
  }

  private static final class Probe extends TimedRobot {
    final List<String> events = new ArrayList<>();
    @Override public void disabledInit() { events.add("disabledInit"); }
    @Override public void disabledExit() { events.add("disabledExit"); }
    @Override public void disabledPeriodic() { events.add("disabledPeriodic"); }
    @Override public void autonomousInit() { events.add("autoInit"); }
    @Override public void autonomousExit() { events.add("autoExit"); }
    @Override public void autonomousPeriodic() { events.add("autoPeriodic"); }
    @Override public void teleopInit() { events.add("teleopInit"); }
    @Override public void teleopExit() { events.add("teleopExit"); }
    @Override public void teleopPeriodic() { events.add("teleopPeriodic"); }
    @Override public void utilityInit() { events.add("utilityInit"); }
    @Override public void utilityExit() { events.add("utilityExit"); }
    @Override public void utilityPeriodic() { events.add("utilityPeriodic"); }
    @Override public void robotPeriodic() { events.add("robot"); }
    @Override public void simulationPeriodic() { events.add("simulation"); }
  }
}
