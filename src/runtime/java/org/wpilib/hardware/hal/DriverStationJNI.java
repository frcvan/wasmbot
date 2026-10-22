package org.wpilib.hardware.hal;

import org.teamvan.wasmbot.JsBridge;

/**
 * Controller output natives for the real SDK {@code GenericHID}.
 *
 * <p>Only the LED and rumble outputs reach this boundary; input and robot state go through {@link
 * org.wpilib.driverstation.internal.DriverStationBackend}. Rumble forwards to the host's haptic
 * output, while LEDs have no browser equivalent and fail explicitly.
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
   * Sends the four handle values, each from 0 through 65535, to the host.
   *
   * @return zero, the HAL success status
   */
  public static int setJoystickRumble(
      byte port, int leftRumble, int rightRumble, int leftTriggerRumble, int rightTriggerRumble) {
    JsBridge.setRumble(
        Byte.toUnsignedInt(port), leftRumble, rightRumble, leftTriggerRumble, rightTriggerRumble);
    return 0;
  }
}
