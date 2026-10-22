package org.teamvan.wasmbot;

import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSObject;
import org.graalvm.webimage.api.JSString;
import org.graalvm.webimage.api.JSValue;

/**
 * JavaScript functions the Wasm image reads instead of DriverStation and NetworkTables JNI.
 *
 * <p>The host installs these on {@code globalThis.wasmbot} before calling {@code wasmbot.start}:
 *
 * <ul>
 *   <li>{@code nowNanos(): number} monotonic time in nanoseconds
 *   <li>{@code driverStation(): {enabled, mode, alliance, opMode, opModeName, controllers}}
 *       alliance is {@code "red"} or {@code "blue"}
 *   <li>{@code setOpModes(json)} optional simulator hook receiving the published opmode list
 *   <li>{@code ntGet(path): any} current NetworkTables value, or {@code undefined}
 *   <li>{@code ntPut(path, value)} publish a telemetry value
 * </ul>
 */
public final class JsBridge {
  private JsBridge() {}

  /**
   * @return monotonic time in nanoseconds from {@code wasmbot.nowNanos}
   */
  @JS.Coerce
  @JS("return globalThis.wasmbot.nowNanos();")
  public static native double nowNanos();

  /**
   * @return the current DriverStation snapshot from {@code wasmbot.driverStation}
   */
  @JS("return globalThis.wasmbot.driverStation();")
  public static native JSObject driverStation();

  /**
   * @param path NetworkTables path
   * @return the current value, or a JS {@code undefined}
   */
  public static JSValue ntGet(String path) {
    return ntGet(JSString.of(path));
  }

  @JS("return globalThis.wasmbot.ntGet(path);")
  private static native JSValue ntGet(JSString path);

  /**
   * Publishes the registered opmode list to the browser driver station. Hosts without a simulator
   * panel simply omit {@code wasmbot.setOpModes}.
   *
   * @param json JSON array of {@code {id, mode, name, group, description, textColor,
   *     backgroundColor}} objects
   */
  @JS.Coerce
  @JS("globalThis.wasmbot.setOpModes?.(json);")
  public static native void setOpModes(String json);

  /**
   * Forwards controller rumble to the host. Hosts without haptic output simply omit {@code
   * wasmbot.setRumble}.
   *
   * @param port Driver Station port
   * @param leftRumble left handle, from 0 through 65535
   * @param rightRumble right handle, from 0 through 65535
   * @param leftTriggerRumble left trigger, from 0 through 65535
   * @param rightTriggerRumble right trigger, from 0 through 65535
   */
  @JS.Coerce
  @JS(
      "globalThis.wasmbot.setRumble?.(port, leftRumble, rightRumble, leftTriggerRumble, rightTriggerRumble);")
  public static native void setRumble(
      int port, int leftRumble, int rightRumble, int leftTriggerRumble, int rightTriggerRumble);

  /**
   * Publishes one numeric telemetry value.
   *
   * @param path NetworkTables path
   * @param value numeric value
   */
  @JS.Coerce
  @JS("globalThis.wasmbot.ntPut(path, value);")
  public static native void ntPut(String path, double value);

  /** Publishes a number without re-encoding a cached JavaScript topic string. */
  @JS.Coerce
  @JS("globalThis.wasmbot.ntPut(path, value);")
  static native void ntPut(JSString path, double value);

  /** Publishes one boolean telemetry value. */
  @JS.Coerce
  @JS("globalThis.wasmbot.ntPut(path, value);")
  static native void ntPut(JSString path, boolean value);

  /** Publishes one string telemetry value. */
  @JS.Coerce
  @JS("globalThis.wasmbot.ntPut(path, value);")
  static native void ntPut(JSString path, String value);

  /**
   * Publishes one telemetry value. The value must already be a JavaScript value.
   *
   * @param path NetworkTables path
   * @param value JavaScript primitive, array, or plain object
   */
  public static void ntPut(String path, JSValue value) {
    ntPut(JSString.of(path), value);
  }

  @JS("globalThis.wasmbot.ntPut(path, value);")
  static native void ntPut(JSString path, JSValue value);
}
