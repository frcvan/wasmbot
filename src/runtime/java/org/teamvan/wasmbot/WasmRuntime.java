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
  private static volatile long opModeId;
  private static volatile String opModeName = "";
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
  private static JSObject driverStationState;
  private static boolean refreshRequired = true;
  private static volatile LongSupplier clock = () -> (long) JsBridge.nowNanos();

  private WasmRuntime() {}

  /** Samples the host once and copies only changed normalized values into Java. */
  public static void refresh() {
    if (driverStationState == null) driverStationState = createDriverStationState();
    int changes = sampleDriverStation(driverStationState, refreshRequired);
    if (refreshRequired) changes = 0xff;
    if (changes == 0) return;
    JSObject ds = driverStationState;
    if ((changes & 0x40) != 0) {
      setEnabled(enabled(ds));
      setRobotMode(RobotMode.fromInt(robotMode(ds)));
      setAlliance(redAlliance(ds) ? Alliance.RED : Alliance.BLUE);
    }
    if ((changes & 0x80) != 0) {
      setOpMode(opMode(ds), opModeName(ds));
    }
    for (int port = 0; port < CONTROLLER_PORTS; port++) {
      if ((changes & (1 << port)) == 0) continue;
      connected[port] = controllerConnected(ds, port);
      axisCounts[port] = controllerAxisCount(ds, port);
      buttonCounts[port] = controllerButtonCount(ds, port);
      if (!controllerNameEquals(ds, port, names[port])) names[port] = controllerName(ds, port);
      for (int axis = 0; axis < CONTROLLER_AXES; axis++) {
        axes[port][axis] = controllerAxis(ds, port, axis);
      }
      int next = controllerButtons(ds, port);
      pressed[port] |= ~buttons[port] & next;
      released[port] |= buttons[port] & ~next;
      buttons[port] = next;
    }
    dsAttached = true;
    refreshRequired = false;
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
    refreshRequired = true;
  }

  /** Sets the selected robot mode independently of the enabled flag. */
  public static void setRobotMode(RobotMode robotMode) {
    WasmRuntime.robotMode = robotMode;
    refreshRequired = true;
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
    refreshRequired = true;
  }

  /**
   * Selects an opmode without going through the browser snapshot. Desktop and native smoke tests
   * use this; the browser updates it through {@code wasmbot.driverStation()}.
   *
   * @param id opmode ID, or {@code 0} for no selection
   * @param name opmode name, or an empty string for no selection
   */
  public static void setOpMode(long id, String name) {
    opModeId = id;
    opModeName = name == null ? "" : name;
    refreshRequired = true;
  }

  /**
   * @return the opmode selected in the browser, or {@code 0} when none is selected
   */
  public static long getOpModeId() {
    return opModeId;
  }

  /**
   * @return the selected opmode name, or an empty string when none is selected
   */
  public static String getOpModeName() {
    return opModeName;
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
    refreshRequired = true;
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

  @JS(
      """
      return {
        enabled: undefined, mode: 0, red: false, opMode: 0, opModeName: '', revision: -1,
        controllers: Array.from({length: 6}, () => ({
          connected: false, name: '', axisCount: 0, buttonCount: 0,
          axes: [0, 0, 0, 0, 0, 0], buttons: 0
        }))
      };
      """)
  private static native JSObject createDriverStationState();

  @JS.Coerce
  @JS(
      """
      const ds = globalThis.wasmbot.driverStation();
      // The simulator bumps revision only when an input or Driver Station selection changed, so an
      // idle tick returns without touching six ports of mirrored state.
      if (!force && typeof ds.revision === 'number' && ds.revision === state.revision) return 0;
      state.revision = ds.revision;
      const enabled = ds.enabled === true;
      const mode = ds.mode === 'autonomous' ? 1 : ds.mode === 'teleop' ? 2 : ds.mode === 'utility' ? 3 : 0;
      const red = typeof ds.alliance === 'string' && ds.alliance.toLowerCase() === 'red';
      const opMode = Number.isSafeInteger(ds.opMode) && ds.opMode >= 0 ? ds.opMode : 0;
      const opModeName = typeof ds.opModeName === 'string' ? ds.opModeName : '';
      let changes = state.enabled !== enabled || state.mode !== mode || state.red !== red ? 64 : 0;
      if (state.opMode !== opMode || state.opModeName !== opModeName) changes |= 128;
      state.enabled = enabled;
      state.mode = mode;
      state.red = red;
      state.opMode = opMode;
      state.opModeName = opModeName;
      const hasControllers = Array.isArray(ds.controllers);
      for (let port = 0; port < 6; port++) {
        const previous = state.controllers[port];
        const legacy = !hasControllers && port === 0;
        const controller = hasControllers ? ds.controllers[port] : null;
        const connected = legacy || controller != null;
        const name = legacy ? 'Browser keyboard' : typeof controller?.name === 'string' ? controller.name : '';
        const axes = controller?.axes;
        const buttons = controller?.buttons;
        const axisCount = legacy ? 3 : Array.isArray(axes) ? Math.min(6, axes.length) : 0;
        const buttonCount = legacy ? 0 : Array.isArray(buttons) ? Math.min(26, buttons.length) : 0;
        let bits = 0;
        for (let i = 0; i < buttonCount; i++) if (buttons[i] === true) bits |= 1 << i;
        let changed = previous.connected !== connected || previous.name !== name
          || previous.axisCount !== axisCount || previous.buttonCount !== buttonCount || previous.buttons !== bits;
        // Two disconnected ports carry no axis state to mirror.
        if (connected || previous.connected) {
          for (let axis = 0; axis < 6; axis++) {
            let value = 0;
            if (legacy) {
              const raw = axis === 0 ? ds.leftX : axis === 1 ? ds.leftY : axis === 2 ? ds.rightX : 0;
              if (typeof raw === 'number') value = raw;
            } else if (axis < axisCount && typeof axes[axis] === 'number' && Number.isFinite(axes[axis])) {
              value = axes[axis];
            }
            if (!Object.is(previous.axes[axis], value)) changed = true;
            previous.axes[axis] = value;
          }
        }
        if (changed) changes |= 1 << port;
        previous.connected = connected;
        previous.name = name;
        previous.axisCount = axisCount;
        previous.buttonCount = buttonCount;
        previous.buttons = bits;
      }
      return changes;
      """)
  private static native int sampleDriverStation(JSObject state, boolean force);

  @JS.Coerce
  @JS("return ds.enabled;")
  private static native boolean enabled(JSObject ds);

  @JS.Coerce
  @JS("return ds.mode;")
  private static native int robotMode(JSObject ds);

  @JS.Coerce
  @JS("return ds.red;")
  private static native boolean redAlliance(JSObject ds);

  @JS.Coerce
  @JS("return ds.opMode;")
  private static native long opMode(JSObject ds);

  @JS.Coerce
  @JS("return ds.opModeName;")
  private static native String opModeName(JSObject ds);

  @JS.Coerce
  @JS("return ds.controllers[port].connected;")
  private static native boolean controllerConnected(JSObject ds, int port);

  @JS.Coerce
  @JS("return ds.controllers[port].axisCount;")
  private static native int controllerAxisCount(JSObject ds, int port);

  @JS.Coerce
  @JS("return ds.controllers[port].buttonCount;")
  private static native int controllerButtonCount(JSObject ds, int port);

  @JS.Coerce
  @JS("return ds.controllers[port].axes[axis];")
  private static native double controllerAxis(JSObject ds, int port, int axis);

  @JS.Coerce
  @JS("return ds.controllers[port].buttons;")
  private static native int controllerButtons(JSObject ds, int port);

  @JS.Coerce
  @JS("return ds.controllers[port].name;")
  private static native String controllerName(JSObject ds, int port);

  @JS.Coerce
  @JS("return ds.controllers[port].name === previous;")
  private static native boolean controllerNameEquals(JSObject ds, int port, String previous);
}
