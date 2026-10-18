package example;

import org.wpilib.driverstation.Gamepad;
import org.wpilib.driverstation.RobotState;
import org.wpilib.framework.OpModeRobot;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.geometry.Twist2d;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.networktables.StructPublisher;
import org.wpilib.telemetry.Telemetry;

/** A small arcade-drive simulation that exposes its pose to AdvantageScope. */
public final class Robot extends OpModeRobot {
  private static final double MAX_SPEED = 3.0;
  private static final double MAX_TURN_RATE = Math.PI;
  private static final double MAX_ACCELERATION = 3.0;
  private static final double MAX_TURN_ACCELERATION = 2.0 * Math.PI;

  private final Gamepad gamepad = new Gamepad(0);
  private final Twist2d delta = new Twist2d();
  private final StructPublisher<Pose2d> posePublisher =
      NetworkTableInstance.getDefault().getStructTopic("/Drivetrain/Pose", Pose2d.struct).publish();
  private Pose2d pose = new Pose2d();
  private double forwardMetersPerSecond;
  private double turnRadiansPerSecond;

  @Override
  public void robotPeriodic() {
    double dt = getPeriod();
    if (RobotState.isEnabled()) {
      double previousForward = forwardMetersPerSecond;
      double previousTurn = turnRadiansPerSecond;
      forwardMetersPerSecond +=
          Math.clamp(
              -gamepad.getLeftY() * MAX_SPEED - forwardMetersPerSecond,
              -MAX_ACCELERATION * dt,
              MAX_ACCELERATION * dt);
      // Stick right is clockwise; Pose2d positive rotation is counterclockwise.
      turnRadiansPerSecond +=
          Math.clamp(
              -gamepad.getRightX() * MAX_TURN_RATE - turnRadiansPerSecond,
              -MAX_TURN_ACCELERATION * dt,
              MAX_TURN_ACCELERATION * dt);
      delta.dx = (previousForward + forwardMetersPerSecond) * 0.5 * dt;
      delta.dtheta = (previousTurn + turnRadiansPerSecond) * 0.5 * dt;
      if (delta.dx != 0.0 || delta.dtheta != 0.0) {
        pose = pose.plus(delta.exp());
      }
    } else {
      forwardMetersPerSecond = 0.0;
      turnRadiansPerSecond = 0.0;
    }

    posePublisher.set(pose);
    Telemetry.log("Drivetrain/ForwardMetersPerSecond", forwardMetersPerSecond);
    Telemetry.log("Drivetrain/TurnRadiansPerSecond", turnRadiansPerSecond);
  }
}
