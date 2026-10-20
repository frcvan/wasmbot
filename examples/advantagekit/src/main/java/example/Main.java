package example;

import org.littletonrobotics.junction.AutoLogOutput;
import org.littletonrobotics.junction.LoggedRobot;
import org.littletonrobotics.junction.Logger;
import org.littletonrobotics.junction.networktables.NT4Publisher;
import org.wpilib.framework.RobotBase;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.geometry.Rotation2d;

/** Live AdvantageKit inputs, metadata, and typed outputs without native IO. */
public final class Main {
  private Main() {}

  public static void main(String... args) {
    RobotBase.startRobot(CounterRobot::new);
  }

  public static final class CounterRobot extends LoggedRobot {
    private static final Rotation2d HEADING = new Rotation2d(0.5);
    private final CounterInputsAutoLogged inputs = new CounterInputsAutoLogged();
    private final CounterOutputs outputs = new CounterOutputs();

    @AutoLogOutput(key = "Counter/Cycles")
    private long cycles;

    public CounterRobot() {
      Logger.recordMetadata("ProjectName", "Wasmbot AdvantageKit Example");
      Logger.addDataReceiver(new NT4Publisher());
      Logger.start();
    }

    @Override
    public void robotPeriodic() {
      cycles++;
      inputs.count = cycles;
      outputs.count = cycles;
      inputs.samples[0] = cycles;
      inputs.samples[1] = -cycles;
      Logger.processInputs("Counter", inputs);
      Logger.recordOutput("Counter/Enabled", isEnabled());
      Logger.recordOutput("Counter/Label", "live");
    }

    @AutoLogOutput
    private long getDoubledCount() {
      return cycles * 2;
    }

    @AutoLogOutput(key = "Counter/Pose")
    private Pose2d getPose() {
      return new Pose2d(cycles, 2, HEADING);
    }
  }

  private static final class CounterOutputs {
    private final String name = "Nested";

    @AutoLogOutput(key = "Counter/{name}/Count", unit = "cycles")
    private double count;
  }
}
