package example;

import org.wpilib.driverstation.Gamepad;
import org.wpilib.framework.OpModeRobot;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.geometry.Rotation2d;
import org.wpilib.telemetry.Telemetry;

/** A small arcade-drive simulation that exposes its pose to AdvantageScope. */
public final class Robot extends OpModeRobot {
  private final Gamepad gamepad = new Gamepad(0);
  private Pose2d pose = new Pose2d();

  @Override
  public void robotPeriodic() {
    double forwardMetersPerSecond = -gamepad.getLeftY();
    double turnRadiansPerSecond = gamepad.getRightX();
    double dt = getPeriod();

    pose =
        new Pose2d(
            pose.getX() + forwardMetersPerSecond * dt * pose.getRotation().getCos(),
            pose.getY() + forwardMetersPerSecond * dt * pose.getRotation().getSin(),
            new Rotation2d(pose.getRotation().getRadians() + turnRadiansPerSecond * dt));

    Telemetry.log("Drivetrain/PoseX", pose.getX());
    Telemetry.log("Drivetrain/PoseY", pose.getY());
    Telemetry.log("Drivetrain/ForwardMetersPerSecond", forwardMetersPerSecond);
    Telemetry.log("Drivetrain/TurnRadiansPerSecond", turnRadiansPerSecond);
  }
}
