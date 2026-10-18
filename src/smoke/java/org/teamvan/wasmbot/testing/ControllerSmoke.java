package org.teamvan.wasmbot.testing;

import org.graalvm.webimage.api.JS;
import org.wpilib.driverstation.DriverStation;
import org.wpilib.driverstation.Gamepad;
import org.wpilib.driverstation.POVDirection;

/** Assertions against controller snapshots supplied by the real WASM smoke host. */
final class ControllerSmoke {
  private ControllerSmoke() {}

  static void run() {
    expose(() -> check(phase()));
  }

  @JS(
      "globalThis.wasmbot.checkControllers = phase => { globalThis.wasmbot.controllerSmokePhase = phase; check(); };")
  private static native void expose(Runnable check);

  @JS.Coerce
  @JS("return globalThis.wasmbot.controllerSmokePhase;")
  private static native int phase();

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError("Controller snapshot: " + message);
    }
  }

  private static void near(double actual, double expected) {
    require(
        Double.isFinite(actual) && Math.abs(actual - expected) < 1e-10,
        "axis " + actual + " != " + expected);
  }

  private static void check(int phase) {
    var primary = new Gamepad(0);
    var secondary = new Gamepad(1);
    var last = new Gamepad(5);
    var hid = primary.getHID();
    if (phase == 0 || phase == 6) {
      require(hid.isConnected(), "legacy port zero connected");
      require(
          hid.getAxesMaximumIndex() == 3 && hid.getAxesAvailable() == 7,
          "legacy three-axis capabilities");
      near(hid.getRawAxis(0), 0.25);
      near(hid.getRawAxis(1), -0.5);
      near(hid.getRawAxis(2), 0.75);
      near(hid.getRawAxis(3), 0);
      require(
          hid.getButtonsAvailable() == 0 && hid.getPOVsAvailable() == 0,
          "legacy buttons and POV unavailable");
      require(!secondary.isConnected() && !last.isConnected(), "legacy other ports disconnected");
      require(hid.getName().equals("Browser keyboard"), "legacy controller name");
    } else if (phase == 1) {
      require(
          hid.isConnected() && secondary.isConnected() && last.isConnected(),
          "independent ports connected");
      require(
          hid.getAxesMaximumIndex() == 6 && hid.getAxesAvailable() == 63, "six normalized axes");
      require(
          hid.getButtonsMaximumIndex() == 26 && hid.getButtonsAvailable() == 0x3ffffffL,
          "26 zero-based buttons");
      require(
          hid.getName().equals("Primary")
              && secondary.getName().equals("Secondary")
              && last.getName().equals("Last port"),
          "per-port names");
      double[] expected = {0.2, -0.3, 0.4, -0.5, 0.6, 0.7};
      for (var axis : Gamepad.Axis.values()) {
        near(primary.getAxis(axis), expected[axis.value]);
      }
      primary.setRightYDeadband(0);
      primary.setLeftTriggerDeadband(0);
      primary.setRightTriggerDeadband(0);
      near(primary.getRightY(), -0.5);
      near(primary.getLeftTrigger(), 0.6);
      near(primary.getRightTrigger(), 0.7);
      near(secondary.getAxis(Gamepad.Axis.LEFT_X), -0.8);
      near(secondary.getAxis(Gamepad.Axis.RIGHT_TRIGGER), 0.75);
      near(last.getAxis(Gamepad.Axis.LEFT_X), 0.1);
      near(last.getAxis(Gamepad.Axis.LEFT_Y), 0);
      require(last.getHID().getAxesAvailable() == 1, "short axis array capability");
      require(
          primary.getFaceDownButton() && primary.getDpadUpButton() && primary.getMisc6Button(),
          "mapped button states including index 25");
      require(primary.getFaceDownButtonPressed(), "first press edge");
      require(!new Gamepad(0).getFaceDownButtonPressed(), "press consumed across wrappers");
      require(!primary.getFaceDownButtonReleased(), "release independent of press");
      require(secondary.getFaceDownButtonPressed(), "press independent across ports");
      require(last.getMisc6ButtonPressed(), "last port high button press");
      require(
          hid.getPOVsMaximumIndex() == 1 && hid.getPOVsAvailable() == 1, "D-pad exposes one POV");
      require(
          hid.getPOV() == POVDirection.UP_RIGHT && hid.getPOV(1) == POVDirection.CENTER,
          "diagonal and unavailable POV");
      require(!DriverStation.getGenericHID(2).isConnected(), "null port disconnected");
    } else if (phase == 2) {
      require(
          primary.getFaceDownButton() && !primary.getFaceDownButtonPressed(),
          "held buttons do not repeat presses");
      require(!primary.getFaceDownButtonReleased(), "held button has no release");
      require(!secondary.getFaceDownButtonPressed(), "secondary press remains consumed");
    } else if (phase == 3) {
      require(!hid.getRawButton(1), "button released before its press was consumed");
      require(hid.getRawButtonReleased(1), "release latched independently");
      require(hid.getRawButtonPressed(1), "unconsumed press survives later release and ticks");
      require(
          !hid.getRawButtonPressed(1) && !hid.getRawButtonReleased(1), "both edges consumed once");
      require(primary.getFaceDownButton(), "unrelated held button unchanged");
    } else if (phase == 4) {
      for (int port = 0; port < 6; port++) {
        var disconnected = DriverStation.getGenericHID(port);
        require(
            !disconnected.isConnected() && disconnected.getName().isEmpty(),
            "disconnect clears identity");
        require(
            disconnected.getAxesAvailable() == 0
                && disconnected.getAxesMaximumIndex() == 0
                && disconnected.getButtonsAvailable() == 0
                && disconnected.getButtonsMaximumIndex() == 0
                && disconnected.getPOVsAvailable() == 0,
            "disconnect clears capabilities");
        for (int axis = 0; axis < 6; axis++) {
          near(disconnected.getRawAxis(axis), 0);
        }
        require(
            !disconnected.getRawButton(0) && !disconnected.getRawButton(25),
            "disconnect clears buttons");
        require(disconnected.getPOV() == POVDirection.CENTER, "disconnect centers POV");
      }
      require(
          primary.getFaceDownButtonReleased() && !primary.getFaceDownButtonReleased(),
          "disconnect releases primary exactly once");
      require(secondary.getFaceDownButtonReleased(), "disconnect releases secondary");
      require(last.getMisc6ButtonReleased(), "disconnect releases last port high button");
    } else if (phase == 5) {
      require(hid.isConnected() && hid.getName().equals("Short controller"), "reconnect identity");
      require(
          hid.getAxesMaximumIndex() == 2
              && hid.getAxesAvailable() == 3
              && hid.getButtonsMaximumIndex() == 1
              && hid.getButtonsAvailable() == 1
              && hid.getPOVsAvailable() == 0,
          "reconnect uses actual shorter arrays");
      near(hid.getRawAxis(0), -0.2);
      near(hid.getRawAxis(1), 0.4);
      near(hid.getRawAxis(2), 0);
      require(
          primary.getFaceDownButtonPressed() && !primary.getFaceDownButtonPressed(),
          "reconnect creates a new press");
      require(!hid.getRawButton(25), "old high buttons do not survive reconnect");
    } else {
      throw new AssertionError("Unknown controller smoke phase " + phase);
    }
  }
}
