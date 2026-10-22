package org.wpilib.driverstation.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.teamvan.wasmbot.JsBridge;
import org.teamvan.wasmbot.WasmRuntime;
import org.wpilib.datalog.DataLog;
import org.wpilib.driverstation.Alliance;
import org.wpilib.driverstation.MatchType;
import org.wpilib.driverstation.POVDirection;
import org.wpilib.driverstation.TouchpadFinger;
import org.wpilib.hardware.hal.AllianceStationID;
import org.wpilib.hardware.hal.ControlWord;
import org.wpilib.hardware.hal.RobotMode;
import org.wpilib.util.Color;

/**
 * Wasm replacement for the SDK's Driver Station cache.
 *
 * <p>The desktop backend refreshes HAL caches on a background thread. Here every read comes from
 * the snapshot {@link WasmRuntime} samples during the host tick, so the SDK's {@code
 * DriverStation}, {@code Gamepad}, {@code GenericHID}, {@code RobotState}, and {@code MatchState}
 * run unchanged. The opmode registry is kept here because the browser, not the FMS, consumes it;
 * publishing hands the list to the host through {@link JsBridge#setOpModes(String)}.
 */
public final class DriverStationBackend {
  /** Match WPILib's six Driver Station ports. */
  public static final int JOYSTICK_PORTS = 6;

  private static final int POV_UP_BUTTON = 11;
  private static final int POV_DOWN_BUTTON = 12;
  private static final int POV_LEFT_BUTTON = 13;
  private static final int POV_RIGHT_BUTTON = 14;
  private static final int POV_BUTTONS = 15;
  private static final int MAX_BUTTONS = 26;
  private static final int RUMBLE_OUTPUT = 0x8;
  private static final TouchpadFinger NEUTRAL_FINGER = new TouchpadFinger(false, 0.0f, 0.0f);

  private record OpModeOption(
      long id,
      RobotMode mode,
      String name,
      String group,
      String description,
      String textColor,
      String backgroundColor) {}

  private static final Map<Long, OpModeOption> OP_MODES = new LinkedHashMap<>();
  private static long nextOpModeId = 1;
  private static boolean joystickAlertsSilenced;

  private DriverStationBackend() {}

  private static boolean validPort(int port) {
    return port >= 0 && port < JOYSTICK_PORTS;
  }

  private static boolean validButton(int button) {
    return button >= 0 && button < MAX_BUTTONS;
  }

  // Reporting goes to the host console; there is no native HAL error stream.

  public static void reportError(String error, boolean printTrace) {
    System.err.println(error);
  }

  public static void reportError(String error, StackTraceElement[] stackTrace) {
    System.err.println(error);
    printTrace(stackTrace);
  }

  public static void reportWarning(String warning, boolean printTrace) {
    System.err.println(warning);
  }

  public static void reportWarning(String warning, StackTraceElement[] stackTrace) {
    System.err.println(warning);
    printTrace(stackTrace);
  }

  public static void reportCrash(String error, StackTraceElement[] stackTrace) {
    System.err.println(error);
    printTrace(stackTrace);
  }

  private static void printTrace(StackTraceElement[] stackTrace) {
    if (stackTrace == null) return;
    for (StackTraceElement element : stackTrace) {
      System.err.println("\tat " + element);
    }
  }

  public static boolean getStickButton(int port, int button) {
    return getStickButtonIfAvailable(port, button).orElse(false);
  }

  public static Optional<Boolean> getStickButtonIfAvailable(int port, int button) {
    if (!validPort(port) || !validButton(button) || button >= getStickButtonsMaximumIndex(port)) {
      return Optional.empty();
    }
    return Optional.of(WasmRuntime.getControllerButton(port, button));
  }

  public static boolean getStickButtonPressed(int port, int button) {
    return validPort(port) && WasmRuntime.getControllerButtonPressed(port, button);
  }

  public static boolean getStickButtonReleased(int port, int button) {
    return validPort(port) && WasmRuntime.getControllerButtonReleased(port, button);
  }

  public static double getStickAxis(int port, int axis) {
    return getStickAxisIfAvailable(port, axis).orElse(0.0);
  }

  public static OptionalDouble getStickAxisIfAvailable(int port, int axis) {
    if (!validPort(port) || axis < 0 || axis >= getStickAxesMaximumIndex(port)) {
      return OptionalDouble.empty();
    }
    return OptionalDouble.of(WasmRuntime.getControllerAxis(port, axis));
  }

  public static TouchpadFinger getStickTouchpadFinger(int port, int touchpad, int finger) {
    return NEUTRAL_FINGER;
  }

  public static boolean getStickTouchpadFingerAvailable(int port, int touchpad, int finger) {
    return false;
  }

  /** Derives the single virtual POV from the host's normalized D-pad buttons. */
  public static POVDirection getStickPOV(int port, int pov) {
    if (pov != 0 || getStickPOVsAvailable(port) == 0) return POVDirection.CENTER;
    int horizontal =
        (WasmRuntime.getControllerButton(port, POV_RIGHT_BUTTON) ? 1 : 0)
            - (WasmRuntime.getControllerButton(port, POV_LEFT_BUTTON) ? 1 : 0);
    int vertical =
        (WasmRuntime.getControllerButton(port, POV_DOWN_BUTTON) ? 1 : 0)
            - (WasmRuntime.getControllerButton(port, POV_UP_BUTTON) ? 1 : 0);
    if (vertical < 0) {
      return horizontal < 0
          ? POVDirection.UP_LEFT
          : horizontal > 0 ? POVDirection.UP_RIGHT : POVDirection.UP;
    }
    if (vertical > 0) {
      return horizontal < 0
          ? POVDirection.DOWN_LEFT
          : horizontal > 0 ? POVDirection.DOWN_RIGHT : POVDirection.DOWN;
    }
    return horizontal < 0
        ? POVDirection.LEFT
        : horizontal > 0 ? POVDirection.RIGHT : POVDirection.CENTER;
  }

  public static long getStickButtons(int port) {
    if (!validPort(port)) return 0;
    long buttons = 0;
    for (int button = 0; button < getStickButtonsMaximumIndex(port); button++) {
      if (WasmRuntime.getControllerButton(port, button)) buttons |= 1L << button;
    }
    return buttons;
  }

  public static int getStickAxesMaximumIndex(int port) {
    return validPort(port) ? WasmRuntime.getControllerAxisCount(port) : 0;
  }

  public static int getStickAxesAvailable(int port) {
    return (1 << getStickAxesMaximumIndex(port)) - 1;
  }

  public static int getStickPOVsMaximumIndex(int port) {
    return getStickPOVsAvailable(port);
  }

  public static int getStickPOVsAvailable(int port) {
    return isJoystickConnected(port) && getStickButtonsMaximumIndex(port) >= POV_BUTTONS ? 1 : 0;
  }

  public static int getStickButtonsMaximumIndex(int port) {
    return validPort(port) ? WasmRuntime.getControllerButtonCount(port) : 0;
  }

  public static long getStickButtonsAvailable(int port) {
    return (1L << getStickButtonsMaximumIndex(port)) - 1;
  }

  public static boolean getJoystickIsGamepad(int port) {
    return false;
  }

  public static int getJoystickGamepadType(int port) {
    return 0;
  }

  public static int getJoystickSupportedOutputs(int port) {
    // The browser exposes left/right haptic handles but no separate trigger or LED channels.
    return isJoystickConnected(port) ? RUMBLE_OUTPUT : 0;
  }

  public static String getJoystickName(int port) {
    return validPort(port) ? WasmRuntime.getControllerName(port) : "";
  }

  public static boolean isJoystickConnected(int port) {
    return validPort(port) && WasmRuntime.isControllerConnected(port);
  }

  public static boolean isEnabled() {
    return WasmRuntime.isEnabled();
  }

  public static boolean isDisabled() {
    return WasmRuntime.isDisabled();
  }

  public static boolean isEStopped() {
    return false;
  }

  public static RobotMode getRobotMode() {
    return WasmRuntime.getRobotMode();
  }

  public static boolean isAutonomous() {
    return getRobotMode() == RobotMode.AUTONOMOUS;
  }

  public static boolean isAutonomousEnabled() {
    return isAutonomous() && isEnabled() && isDSAttached();
  }

  public static boolean isTeleop() {
    return getRobotMode() == RobotMode.TELEOPERATED;
  }

  public static boolean isTeleopEnabled() {
    return isTeleop() && isEnabled() && isDSAttached();
  }

  public static boolean isUtility() {
    return getRobotMode() == RobotMode.UTILITY;
  }

  public static boolean isUtilityEnabled() {
    return isUtility() && isEnabled() && isDSAttached();
  }

  private static String modeName(RobotMode mode) {
    return switch (mode) {
      case AUTONOMOUS -> "autonomous";
      case TELEOPERATED -> "teleop";
      case UTILITY -> "utility";
      default -> "unknown";
    };
  }

  public static long addOpMode(
      RobotMode mode,
      String name,
      String group,
      String description,
      Color textColor,
      Color backgroundColor) {
    if (mode == null) throw new IllegalArgumentException("OpMode robot mode must not be null");
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("OpMode name must be non-blank");
    }
    synchronized (OP_MODES) {
      for (OpModeOption existing : OP_MODES.values()) {
        if (existing.mode() == mode && existing.name().equals(name)) {
          throw new IllegalArgumentException("OpMode " + name + " already exists for mode " + mode);
        }
      }
      long id = nextOpModeId++;
      OP_MODES.put(
          id,
          new OpModeOption(
              id,
              mode,
              name,
              group == null ? "" : group,
              description == null ? "" : description,
              textColor == null ? "" : textColor.toHexString(),
              backgroundColor == null ? "" : backgroundColor.toHexString()));
      return id;
    }
  }

  public static long addOpMode(RobotMode mode, String name, String group, String description) {
    return addOpMode(mode, name, group, description, null, null);
  }

  public static long addOpMode(RobotMode mode, String name, String group) {
    return addOpMode(mode, name, group, "", null, null);
  }

  public static long addOpMode(RobotMode mode, String name) {
    return addOpMode(mode, name, "", "", null, null);
  }

  public static long removeOpMode(RobotMode mode, String name) {
    if (name == null || name.isBlank()) return 0;
    synchronized (OP_MODES) {
      for (OpModeOption option : OP_MODES.values()) {
        if (option.mode() == mode && option.name().equals(name)) {
          OP_MODES.remove(option.id());
          return option.id();
        }
      }
      return 0;
    }
  }

  /** Publishes the registered opmodes to the browser driver station. */
  public static void publishOpModes() {
    StringBuilder json = new StringBuilder("[");
    synchronized (OP_MODES) {
      boolean first = true;
      for (OpModeOption option : OP_MODES.values()) {
        if (!first) json.append(',');
        first = false;
        json.append("{\"id\":")
            .append(option.id())
            .append(",\"mode\":\"")
            .append(modeName(option.mode()))
            .append("\",\"name\":\"")
            .append(escapeJson(option.name()))
            .append("\",\"group\":\"")
            .append(escapeJson(option.group()))
            .append("\",\"description\":\"")
            .append(escapeJson(option.description()))
            .append("\",\"textColor\":\"")
            .append(escapeJson(option.textColor()))
            .append("\",\"backgroundColor\":\"")
            .append(escapeJson(option.backgroundColor()))
            .append("\"}");
      }
    }
    json.append(']');
    JsBridge.setOpModes(json.toString());
  }

  public static void clearOpModes() {
    synchronized (OP_MODES) {
      OP_MODES.clear();
    }
    JsBridge.setOpModes("[]");
  }

  public static void observeUserProgramStarting() {}

  public static long getOpModeId() {
    long id = WasmRuntime.getOpModeId();
    if (id != 0) return id;
    String name = WasmRuntime.getOpModeName();
    if (name.isEmpty()) return 0;
    RobotMode mode = WasmRuntime.getRobotMode();
    synchronized (OP_MODES) {
      for (OpModeOption option : OP_MODES.values()) {
        if (option.mode() == mode && option.name().equals(name)) return option.id();
      }
    }
    return 0;
  }

  public static String getOpMode() {
    String name = WasmRuntime.getOpModeName();
    if (!name.isEmpty()) return name;
    OpModeOption option;
    synchronized (OP_MODES) {
      option = OP_MODES.get(WasmRuntime.getOpModeId());
    }
    return option == null ? "" : option.name();
  }

  public static boolean isOpMode(long id) {
    return id != 0 && getOpModeId() == id;
  }

  public static boolean isOpMode(String mode) {
    return mode != null && !mode.isEmpty() && mode.equals(getOpMode());
  }

  public static boolean isDSAttached() {
    return WasmRuntime.isDSAttached();
  }

  public static boolean isFMSAttached() {
    return false;
  }

  public static Optional<String> getGameData() {
    return Optional.empty();
  }

  public static String getEventName() {
    return "";
  }

  public static MatchType getMatchType() {
    return MatchType.NONE;
  }

  public static int getMatchNumber() {
    return 0;
  }

  public static int getReplayNumber() {
    return 0;
  }

  public static Optional<Alliance> getAlliance() {
    return WasmRuntime.getAlliance();
  }

  public static OptionalInt getLocation() {
    return OptionalInt.empty();
  }

  public static AllianceStationID getRawAllianceStation() {
    return getAlliance().orElse(Alliance.BLUE) == Alliance.RED
        ? AllianceStationID.RED_1
        : AllianceStationID.BLUE_1;
  }

  /** The browser supplies no FMS or practice clock; -1 is the SDK unavailable sentinel. */
  public static double getMatchTime() {
    return -1.0;
  }

  public static void silenceJoystickConnectionAlert(boolean silence) {
    joystickAlertsSilenced = silence;
  }

  public static boolean isJoystickConnectionAlertSilenced() {
    return joystickAlertsSilenced;
  }

  /** Reads already come from the host snapshot, so the HAL cache needs no refresh. */
  public static void refreshControlWordFromCache(ControlWord controlWord) {}

  /** The host tick samples {@link WasmRuntime}; there is no native data to refresh. */
  public static void refreshData() {}

  public static void provideRefreshedDataEventHandle(int handle) {}

  public static void removeRefreshedDataEventHandle(int handle) {}

  public static void startDataLog(DataLog log, boolean logJoystickData) {}

  public static void startDataLog(DataLog log) {}

  private static String escapeJson(String value) {
    StringBuilder out = new StringBuilder(value.length() + 8);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
          else out.append(c);
        }
      }
    }
    return out.toString();
  }
}
