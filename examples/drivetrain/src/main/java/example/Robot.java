package example;

import org.wpilib.driverstation.Gamepad;
import org.wpilib.framework.OpModeRobot;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.geometry.Rotation2d;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.networktables.StructPublisher;
import org.wpilib.telemetry.Telemetry;

/** A small arcade-drive simulation that exposes its pose to AdvantageScope. */
public final class Robot extends OpModeRobot {
  private final Gamepad gamepad = new Gamepad(0);
  private final StructPublisher<Pose2d> posePublisher =
      NetworkTableInstance.getDefault().getStructTopic("/Drivetrain/Pose", Pose2d.struct).publish();
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

    posePublisher.set(pose);
    Telemetry.log("Drivetrain/ForwardMetersPerSecond", forwardMetersPerSecond);
    Telemetry.log("Drivetrain/TurnRadiansPerSecond", turnRadiansPerSecond);
  }
}
