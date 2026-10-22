package org.wpilib.hardware.hal;

/**
 * Controller output natives for the real SDK {@code GenericHID}.
 *
 * <p>Only the LED and rumble outputs reach this boundary; input and robot state go through {@link
 * org.wpilib.driverstation.internal.DriverStationBackend}. The browser bridge has no controller
 * outputs, so both calls fail explicitly instead of silently doing nothing.
 */
public final class DriverStationJNI {
  private DriverStationJNI() {}

  /**
   * @throws UnsupportedOperationException always; the web host has no controller LEDs
   */
  public static int setJoystickLeds(byte port, int rgb) {
    throw new UnsupportedOperationException("Controller LED output is unavailable in wasmbot");
  }

  /**
   * @throws UnsupportedOperationException always; the web host has no controller rumble
   */
  public static int setJoystickRumble(
      byte port, int leftRumble, int rightRumble, int leftTriggerRumble, int rightTriggerRumble) {
    throw new UnsupportedOperationException("Controller rumble output is unavailable in wasmbot");
  }
}
