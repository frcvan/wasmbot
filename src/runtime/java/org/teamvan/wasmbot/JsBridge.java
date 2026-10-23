package org.teamvan.wasmbot;

import org.graalvm.webimage.api.JS;

/**
 * JavaScript hooks the Wasm image still reads through Java.
 *
 * <p>Driver Station sampling and NetworkTables traffic go straight to {@code
 * globalThis.wasmbot.driverStation()} and {@code globalThis.wasmbot.nt} from {@link WasmRuntime}
 * and {@code NetworkTablesJNI}, so this class only carries the three host callbacks that have no
 * dedicated owner.
 *
 * <ul>
 *   <li>{@code nowNanos(): number} monotonic time in nanoseconds
 *   <li>{@code setOpModes(json)} optional simulator hook receiving the published opmode list
 *   <li>{@code setRumble(port, ...)} optional simulator hook receiving controller rumble
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
}
