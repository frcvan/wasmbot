package example;

import org.wpilib.framework.RobotBase;

/** Standard WPILib entry point for the browser example. */
public final class Main {
  private Main() {}

  public static void main(String... args) {
    RobotBase.startRobot(Robot::new);
  }
}
