package example;

import org.wpilib.command3.Scheduler;
import org.wpilib.driverstation.RobotState;
import org.wpilib.framework.OpModeRobot;
import org.wpilib.networktables.DoublePublisher;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.telemetry.Telemetry;

/** The same hardware-free robot and scheduler lifecycle runs on desktop and in WASM. */
public final class Robot extends OpModeRobot {
  private final Counter counter = new Counter();
  private final DoublePublisher directCounter =
      NetworkTableInstance.getDefault().getDoubleTopic("/Example/DirectCounter").publish();

  @Override
  public void robotPeriodic() {
    Scheduler.getDefault().run();

    Telemetry.log("Example/Counter", counter.ticks());
    directCounter.set(counter.ticks());
    Telemetry.log("Example/Enabled", RobotState.isEnabled());
    Telemetry.log("Example/Wasmbot", Boolean.getBoolean("wasmbot"));
  }
}
