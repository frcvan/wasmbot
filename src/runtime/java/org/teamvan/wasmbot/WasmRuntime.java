package org.teamvan.wasmbot;

import java.util.Optional;
import java.util.function.LongSupplier;
import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSObject;
import org.wpilib.driverstation.Alliance;
import org.wpilib.hardware.hal.RobotMode;

/**
 * Java-side copy of the values JavaScript supplies each loop. WPILib facades read this instead of
 * HAL.
 */
public final class WasmRuntime {
  private static final double BATTERY_VOLTS = 12.0;

  private static volatile boolean enabled;
  private static volatile RobotMode robotMode = RobotMode.UNKNOWN;
  private static volatile boolean dsAttached;
  private static volatile Alliance alliance = Alliance.BLUE;
  private static final int CONTROLLER_PORTS = 6;
  private static final int CONTROLLER_AXES = 6;
  private static final int CONTROLLER_BUTTONS = 26;
  private static final double[][] axes = new double[CONTROLLER_PORTS][CONTROLLER_AXES];
  private static final int[] axisCounts = {3, 0, 0, 0, 0, 0};
  private static final int[] buttonCounts = new int[CONTROLLER_PORTS];
  private static final int[] buttons = new int[CONTROLLER_PORTS];
  private static final int[] pressed = new int[CONTROLLER_PORTS];
  private static final int[] released = new int[CONTROLLER_PORTS];
  private static final boolean[] connected = new boolean[CONTROLLER_PORTS];
  private static final String[] names = {"Browser keyboard", "", "", "", "", ""};
  private static volatile LongSupplier clock = () -> (long) JsBridge.nowNanos();

  private WasmRuntime() {}

  /** Pulls the current DriverStation snapshot from JavaScript. */
  public static void refresh() {
    JSObject ds = JsBridge.driverStation();
    setEnabled(enabled(ds));
    setRobotMode(RobotMode.fromInt(robotMode(ds)));
    setAlliance(redAlliance(ds) ? Alliance.RED : Alliance.BLUE);
    boolean hasControllers = hasControllers(ds);
    for (int port = 0; port < CONTROLLER_PORTS; port++) {
      boolean legacy = !hasControllers && port == 0;
      connected[port] = legacy || controllerConnected(ds, port);
      axisCounts[port] = legacy ? 3 : controllerAxisCount(ds, port);
      buttonCounts[port] = legacy ? 0 : controllerButtonCount(ds, port);
      if (legacy) {
        names[port] = "Browser keyboard";
      } else if (!controllerNameEquals(ds, port, names[port])) {
        names[port] = controllerName(ds, port);
      }
      for (int axis = 0; axis < CONTROLLER_AXES; axis++) {
        axes[port][axis] =
            legacy
                ? switch (axis) {
                  case 0 -> leftX(ds);
                  case 1 -> leftY(ds);
                  case 2 -> rightX(ds);
                  default -> 0.0;
                }
                : controllerAxis(ds, port, axis);
      }
      int next = legacy ? 0 : controllerButtons(ds, port);
      pressed[port] |= ~buttons[port] & next;
      released[port] |= buttons[port] & ~next;
      buttons[port] = next;
    }
    dsAttached = true;
  }

  /**
   * Replaces the monotonic clock. Desktop tests use this because Web Image is not running.
   *
   * @param clock time in nanoseconds
   */
  public static void setClock(LongSupplier clock) {
    WasmRuntime.clock = clock;
  }

  /**
   * @param enabled whether JavaScript reports the robot enabled
   */
  public static void setEnabled(boolean enabled) {
    WasmRuntime.enabled = enabled;
  }

  /** Sets the selected robot mode independently of the enabled flag. */
  public static void setRobotMode(RobotMode robotMode) {
    WasmRuntime.robotMode = robotMode;
  }

  /** Returns the selected mode, including UNKNOWN when the host omits it. */
  public static RobotMode getRobotMode() {
    return robotMode;
  }

  /**
   * @param alliance alliance from the latest snapshot
   */
  public static void setAlliance(Alliance alliance) {
    WasmRuntime.alliance = alliance;
  }

  /**
   * @param leftX left stick X in [-1, 1]
   * @param leftY left stick Y in [-1, 1]
   * @param rightX right stick X in [-1, 1]
   */
  public static void setAxes(double leftX, double leftY, double rightX) {
    axes[0][0] = leftX;
    axes[0][1] = leftY;
    axes[0][2] = rightX;
  }

  /**
   * @return true when JavaScript says the robot is enabled
   */
  public static boolean isEnabled() {
    return enabled;
  }

  /**
   * @return true when JavaScript says the robot is disabled
   */
  public static boolean isDisabled() {
    return !enabled;
  }

  /** Returns whether a driver-station snapshot has been received from the host. */
  public static boolean isDSAttached() {
    return dsAttached;
  }

  /**
   * @return the alliance from the latest snapshot
   */
  public static Optional<Alliance> getAlliance() {
    return Optional.of(alliance);
  }

  /**
   * @return left stick X in [-1, 1]
   */
  public static double getLeftX() {
    return axes[0][0];
  }

  /**
   * @return left stick Y in [-1, 1]
   */
  public static double getLeftY() {
    return axes[0][1];
  }

  /**
   * @return right stick X in [-1, 1]
   */
  public static double getRightX() {
    return axes[0][2];
  }

  /** Returns a normalized controller axis, or zero when unavailable. */
  public static double getControllerAxis(int port, int axis) {
    return axis >= 0 && axis < axisCounts[port] ? axes[port][axis] : 0.0;
  }

  /** Returns the number of contiguous available controller axes. */
  public static int getControllerAxisCount(int port) {
    return axisCounts[port];
  }

  /** Returns the number of contiguous available zero-based controller buttons. */
  public static int getControllerButtonCount(int port) {
    return buttonCounts[port];
  }

  /** Returns the current state of a zero-based controller button. */
  public static boolean getControllerButton(int port, int button) {
    return button >= 0 && button < buttonCounts[port] && (buttons[port] & (1 << button)) != 0;
  }

  /** Consumes a pending press independently of the corresponding release. */
  public static boolean getControllerButtonPressed(int port, int button) {
    if (button < 0 || button >= CONTROLLER_BUTTONS) {
      return false;
    }
    int mask = 1 << button;
    boolean result = (pressed[port] & mask) != 0;
    pressed[port] &= ~mask;
    return result;
  }

  /** Consumes a pending release, including releases caused by disconnecting a controller. */
  public static boolean getControllerButtonReleased(int port, int button) {
    if (button < 0 || button >= CONTROLLER_BUTTONS) {
      return false;
    }
    int mask = 1 << button;
    boolean result = (released[port] & mask) != 0;
    released[port] &= ~mask;
    return result;
  }

  /** Returns whether the host publishes a controller on this port. */
  public static boolean isControllerConnected(int port) {
    return connected[port];
  }

  /** Returns the host-provided name, or an empty name for a disconnected port. */
  public static String getControllerName(int port) {
    return names[port];
  }

  /**
   * @return monotonic nanoseconds
   */
  public static long nowNanos() {
    return clock.getAsLong();
  }

  /**
   * @return the fixed simulated battery voltage
   */
  public static double getBatteryVoltage() {
    return BATTERY_VOLTS;
  }

  @JS.Coerce
  @JS("return ds.enabled === true;")
  private static native boolean enabled(JSObject ds);

  @JS.Coerce
  @JS(
      "switch (ds.mode) { case 'autonomous': return 1; case 'teleop': return 2; case 'utility': return 3; default: return 0; }")
  private static native int robotMode(JSObject ds);

  @JS.Coerce
  @JS("return typeof ds.alliance === 'string' && ds.alliance.toLowerCase() === 'red';")
  private static native boolean redAlliance(JSObject ds);

  @JS.Coerce
  @JS("return typeof ds.leftX === 'number' ? ds.leftX : 0;")
  private static native double leftX(JSObject ds);

  @JS.Coerce
  @JS("return typeof ds.leftY === 'number' ? ds.leftY : 0;")
  private static native double leftY(JSObject ds);

  @JS.Coerce
  @JS("return typeof ds.rightX === 'number' ? ds.rightX : 0;")
  private static native double rightX(JSObject ds);

  @JS.Coerce
  @JS("return Array.isArray(ds.controllers);")
  private static native boolean hasControllers(JSObject ds);

  @JS.Coerce
  @JS("return Array.isArray(ds.controllers) && ds.controllers[port] != null;")
  private static native boolean controllerConnected(JSObject ds, int port);

  @JS.Coerce
  @JS(
      "const c = ds.controllers?.[port]; return Array.isArray(c?.axes) ? Math.min(6, c.axes.length) : 0;")
  private static native int controllerAxisCount(JSObject ds, int port);

  @JS.Coerce
  @JS(
      "const c = ds.controllers?.[port]; return Array.isArray(c?.buttons) ? Math.min(26, c.buttons.length) : 0;")
  private static native int controllerButtonCount(JSObject ds, int port);

  @JS.Coerce
  @JS(
      "const value = ds.controllers?.[port]?.axes?.[axis]; return typeof value === 'number' && Number.isFinite(value) ? value : 0;")
  private static native double controllerAxis(JSObject ds, int port, int axis);

  @JS.Coerce
  @JS(
      "const b = ds.controllers?.[port]?.buttons; let value = 0; if (Array.isArray(b)) { for (let i = 0; i < Math.min(26, b.length); i++) { if (b[i] === true) value |= 1 << i; } } return value;")
  private static native int controllerButtons(JSObject ds, int port);

  @JS.Coerce
  @JS("const name = ds.controllers?.[port]?.name; return typeof name === 'string' ? name : '';")
  private static native String controllerName(JSObject ds, int port);

  @JS.Coerce
  @JS(
      "const name = ds.controllers?.[port]?.name; return (typeof name === 'string' ? name : '') === previous;")
  private static native boolean controllerNameEquals(JSObject ds, int port, String previous);
}
