package org.wpilib.driverstation;

import java.util.LinkedHashMap;
import java.util.Map;
import org.teamvan.wasmbot.JsBridge;
import org.teamvan.wasmbot.WasmRuntime;
import org.wpilib.hardware.hal.RobotMode;
import org.wpilib.util.Color;

/**
 * Wasm stand-in. Enable state, selected robot mode, and the selected opmode come from JavaScript,
 * not HAL.
 */
public final class RobotState {
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
   * Adds an operating mode option. Call {@link #publishOpModes()} to make it visible to the browser
   * driver station.
   *
   * @param mode robot mode
   * @param name name of the operating mode
   * @param group group of the operating mode
   * @param description description of the operating mode
   * @param textColor text color, or null for default
   * @param backgroundColor background color, or null for default
   * @return unique ID used to later identify the operating mode
   * @throws IllegalArgumentException if name is empty or the same mode already has this name
   */
  public static synchronized long addOpMode(
      RobotMode mode,
      String name,
      String group,
      String description,
      Color textColor,
      Color backgroundColor) {
    if (mode == null) {
      throw new IllegalArgumentException("OpMode robot mode must not be null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("OpMode name must be non-blank");
    }
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

  /**
   * Removes an operating mode option. Call {@link #publishOpModes()} to hide it in the browser.
   *
   * @return the removed ID, or {@code 0} if it was not registered
   */
  public static synchronized long removeOpMode(RobotMode mode, String name) {
    if (name == null || name.isBlank()) return 0;
    for (OpModeOption option : OP_MODES.values()) {
      if (option.mode() == mode && option.name().equals(name)) {
        OP_MODES.remove(option.id());
        return option.id();
      }
    }
    return 0;
  }

  /** Publishes the registered operating modes to the browser driver station. */
  public static synchronized void publishOpModes() {
    StringBuilder json = new StringBuilder("[");
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
    json.append(']');
    JsBridge.setOpModes(json.toString());
  }

  /** Clears all operating modes and publishes an empty list. */
  public static synchronized void clearOpModes() {
    OP_MODES.clear();
    JsBridge.setOpModes("[]");
  }

  /**
   * @return the opmode selected in the browser, or {@code 0} when none is selected
   */
  public static synchronized long getOpModeId() {
    long id = WasmRuntime.getOpModeId();
    if (id != 0) return id;
    String name = WasmRuntime.getOpModeName();
    if (name.isEmpty()) return 0;
    RobotMode mode = WasmRuntime.getRobotMode();
    for (OpModeOption option : OP_MODES.values()) {
      if (option.mode() == mode && option.name().equals(name)) return option.id();
    }
    return 0;
  }

  /**
   * @return the selected opmode name, or an empty string when none is selected
   */
  public static synchronized String getOpMode() {
    String name = WasmRuntime.getOpModeName();
    if (!name.isEmpty()) return name;
    OpModeOption option = OP_MODES.get(WasmRuntime.getOpModeId());
    return option == null ? "" : option.name();
  }

  private static String modeName(RobotMode mode) {
    return switch (mode) {
      case AUTONOMOUS -> "autonomous";
      case TELEOPERATED -> "teleop";
      case UTILITY -> "utility";
      default -> "unknown";
    };
  }

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
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
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
   * @return whether the ID matches the current selection
   */
  public static boolean isOpMode(long id) {
    return id != 0 && getOpModeId() == id;
  }

  /**
   * @return whether the name matches the current selection
   */
  public static boolean isOpMode(String mode) {
    return mode != null && !mode.isEmpty() && mode.equals(getOpMode());
  }
}
