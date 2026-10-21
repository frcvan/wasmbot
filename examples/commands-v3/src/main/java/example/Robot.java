package example;

import org.wpilib.command3.Scheduler;
import org.wpilib.driverstation.RobotState;
import org.wpilib.framework.OpModeRobot;
import org.wpilib.hardware.hal.RobotMode;
import org.wpilib.networktables.DoublePublisher;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.opmode.OpMode;
import org.wpilib.telemetry.Telemetry;

/** The same hardware-free robot and scheduler lifecycle runs on desktop and in WASM. */
public final class Robot extends OpModeRobot {
  private final Counter counter = new Counter();
  private final DoublePublisher directCounter =
      NetworkTableInstance.getDefault().getDoubleTopic("/Example/DirectCounter").publish();
  private boolean opModeActive;

  /** Registers the opmodes the browser driver station can select. */
  public Robot() {
    addOpMode(
        RobotMode.TELEOPERATED,
        "Scheduler Counter",
        "Examples",
        "Runs the normal counter loop under an explicitly selected opmode.",
        () -> new CounterOpMode(this));
    addOpMode(
        RobotMode.AUTONOMOUS,
        "Auto Counter",
        "Examples",
        "Demonstrates an autonomous opmode in the browser selector.",
        () -> new CounterOpMode(this));
    publishOpModes();
  }

  @Override
  public void robotPeriodic() {
    Scheduler.getDefault().run();

    Telemetry.log("Example/Counter", counter.ticks());
    directCounter.set(counter.ticks());
    Telemetry.log("Example/Enabled", RobotState.isEnabled());
    Telemetry.log("Example/Wasmbot", Boolean.getBoolean("wasmbot"));
    Telemetry.log("Example/OpMode", RobotState.getOpMode());
    Telemetry.log("Example/OpModeActive", opModeActive);
  }

  private static final class CounterOpMode implements OpMode {
    private final Robot robot;

    CounterOpMode(Robot robot) {
      this.robot = robot;
    }

    @Override
    public void start() {
      robot.opModeActive = true;
    }

    @Override
    public void end() {
      robot.opModeActive = false;
    }

    @Override
    public void close() {
      robot.opModeActive = false;
    }
  }
}
