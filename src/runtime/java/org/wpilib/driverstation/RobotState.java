package org.wpilib.driverstation;

import org.teamvan.wasmbot.WasmRuntime;
import org.wpilib.hardware.hal.RobotMode;

/** Wasm stand-in. Enable state and selected robot mode come from JavaScript, not HAL. */
public final class RobotState {
  private RobotState() {}

  /**
   * @return true when JavaScript reports the robot enabled
   */
  public static boolean isEnabled() {
    return WasmRuntime.isEnabled();
  }

  /**
   * @return true when JavaScript reports the robot disabled
   */
  public static boolean isDisabled() {
    return WasmRuntime.isDisabled();
  }

  /**
   * @return {@code 0}; Wasm does not select an opmode through the driver station
   */
  public static long getOpModeId() {
    return 0;
  }

  /**
   * @return whether the browser has delivered a Driver Station snapshot
   */
  public static boolean isDSAttached() {
    return WasmRuntime.isDSAttached();
  }

  /**
   * @return false; the browser is not connected to an FMS
   */
  public static boolean isFMSAttached() {
    return false;
  }

  /**
   * @return false; the host snapshot has no emergency-stop input
   */
  public static boolean isEStopped() {
    return false;
  }

  /**
   * @return the selected host mode, independently of whether the robot is enabled
   */
  public static RobotMode getRobotMode() {
    return WasmRuntime.getRobotMode();
  }

  /**
   * @return whether autonomous mode is selected, including while disabled
   */
  public static boolean isAutonomous() {
    return getRobotMode() == RobotMode.AUTONOMOUS;
  }

  /**
   * @return whether autonomous mode is selected and the attached host enables the robot
   */
  public static boolean isAutonomousEnabled() {
    return isAutonomous() && isEnabled() && isDSAttached();
  }

  /**
   * @return whether teleoperated mode is selected, including while disabled
   */
  public static boolean isTeleop() {
    return getRobotMode() == RobotMode.TELEOPERATED;
  }

  /**
   * @return whether teleoperated mode is selected and the attached host enables the robot
   */
  public static boolean isTeleopEnabled() {
    return isTeleop() && isEnabled() && isDSAttached();
  }

  /**
   * @return whether utility mode is selected, including while disabled
   */
  public static boolean isUtility() {
    return getRobotMode() == RobotMode.UTILITY;
  }

  /**
   * @return whether utility mode is selected and the attached host enables the robot
   */
  public static boolean isUtilityEnabled() {
    return isUtility() && isEnabled() && isDSAttached();
  }

  /**
   * @return an empty string; no operating mode is selected
   */
  public static String getOpMode() {
    return "";
  }

  /**
   * @return whether the ID matches the no-selected-mode sentinel
   */
  public static boolean isOpMode(long id) {
    return id == 0;
  }

  /**
   * @return whether the name matches the no-selected-mode sentinel
   */
  public static boolean isOpMode(String mode) {
    return "".equals(mode);
  }
}
