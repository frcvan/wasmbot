package org.wpilib.hardware.hal.simulation;

import java.util.ArrayList;
import java.util.List;
import org.wpilib.hardware.hal.HALValue;
import org.wpilib.hardware.hal.SimRegistry;

/**
 * In-memory replacement for the sim device data JNI.
 *
 * <p>Name lookups and enumeration read {@link SimRegistry}, so {@code SimDeviceSim} works against
 * simulated vendor devices and values without the native HAL. Value and device callbacks are not
 * supported; registration returns {@code 0} and every cancel is a no-op.
 */
public class SimDeviceDataJNI {
  /**
   * @param prefix device name prefix
   * @param enabled whether matching devices are enabled
   */
  public static void setSimDeviceEnabled(String prefix, boolean enabled) {
    SimRegistry.setEnabled(prefix, enabled);
  }

  /**
   * @param name device name
   * @return whether the device is enabled
   */
  public static boolean isSimDeviceEnabled(String name) {
    return SimRegistry.isEnabled(name);
  }

  /**
   * @param prefix device name prefix
   * @param callback callback
   * @param initialNotify whether to notify immediately
   * @return callback UID, or {@code 0} because callbacks are unsupported
   */
  public static int registerSimDeviceCreatedCallback(
      String prefix, SimDeviceCallback callback, boolean initialNotify) {
    return 0;
  }

  /**
   * @param uid callback UID
   */
  public static void cancelSimDeviceCreatedCallback(int uid) {}

  /**
   * @param prefix device name prefix
   * @param callback callback
   * @param initialNotify whether to notify immediately
   * @return callback UID, or {@code 0} because callbacks are unsupported
   */
  public static int registerSimDeviceFreedCallback(
      String prefix, SimDeviceCallback callback, boolean initialNotify) {
    return 0;
  }

  /**
   * @param uid callback UID
   */
  public static void cancelSimDeviceFreedCallback(int uid) {}

  /**
   * @param name device name
   * @return device handle, or {@code 0} when no simulated device has that name
   */
  public static int getSimDeviceHandle(String name) {
    SimRegistry.Device device = SimRegistry.device(name);
    return device == null ? 0 : device.handle;
  }

  /**
   * @param handle device handle
   * @return device name, or {@code null} for an unknown handle
   */
  public static String getSimDeviceName(int handle) {
    SimRegistry.Device device = SimRegistry.device(handle);
    return device == null ? null : device.name;
  }

  /**
   * @param handle value handle
   * @return the owning device handle, or {@code 0}
   */
  public static int getSimValueDeviceHandle(int handle) {
    SimRegistry.Value value = SimRegistry.value(handle);
    return value == null ? 0 : value.device;
  }

  /** Simulated device name and handle. */
  public static class SimDeviceInfo {
    public String name;
    public int handle;

    /**
     * @param name device name
     * @param handle device handle
     */
    public SimDeviceInfo(String name, int handle) {
      this.name = name;
      this.handle = handle;
    }
  }

  /**
   * @param prefix device name prefix
   * @return matching simulated devices
   */
  public static SimDeviceInfo[] enumerateSimDevices(String prefix) {
    String filter = prefix == null ? "" : prefix;
    List<SimDeviceInfo> devices = new ArrayList<>();
    for (SimRegistry.Device device : SimRegistry.devices()) {
      if (device.name.startsWith(filter)) {
        devices.add(new SimDeviceInfo(device.name, device.handle));
      }
    }
    return devices.toArray(new SimDeviceInfo[0]);
  }

  /**
   * @param device device handle
   * @param callback callback
   * @param initialNotify whether to notify immediately
   * @return callback UID, or {@code 0} because callbacks are unsupported
   */
  public static int registerSimValueCreatedCallback(
      int device, SimValueCallback callback, boolean initialNotify) {
    return 0;
  }

  /**
   * @param uid callback UID
   */
  public static void cancelSimValueCreatedCallback(int uid) {}

  /**
   * @param handle value handle
   * @param callback callback
   * @param initialNotify whether to notify immediately
   * @return callback UID, or {@code 0} because callbacks are unsupported
   */
  public static int registerSimValueChangedCallback(
      int handle, SimValueCallback callback, boolean initialNotify) {
    return 0;
  }

  /**
   * @param uid callback UID
   */
  public static void cancelSimValueChangedCallback(int uid) {}

  /**
   * @param handle value handle
   * @param callback callback
   * @param initialNotify whether to notify immediately
   * @return callback UID, or {@code 0} because callbacks are unsupported
   */
  public static int registerSimValueResetCallback(
      int handle, SimValueCallback callback, boolean initialNotify) {
    return 0;
  }

  /**
   * @param uid callback UID
   */
  public static void cancelSimValueResetCallback(int uid) {}

  /**
   * @param device device handle
   * @param name value name
   * @return value handle, or {@code 0} when the device has no such value
   */
  public static int getSimValueHandle(int device, String name) {
    SimRegistry.Value value = SimRegistry.value(device, name);
    return value == null ? 0 : value.handle;
  }

  /** Simulated value metadata. */
  public static class SimValueInfo {
    public String name;
    public int handle;
    public int direction;
    public HALValue value;

    /**
     * @param name value name
     * @param handle value handle
     * @param direction input/output/bidir
     * @param type HAL value type
     * @param value1 long payload
     * @param value2 double payload
     */
    public SimValueInfo(
        String name, int handle, int direction, int type, long value1, double value2) {
      this.name = name;
      this.handle = handle;
      this.direction = direction;
      this.value = HALValue.fromNative(type, value1, value2);
    }
  }

  /**
   * @param device device handle
   * @return the values on that device
   */
  public static SimValueInfo[] enumerateSimValues(int device) {
    List<SimValueInfo> values = new ArrayList<>();
    for (SimRegistry.Value value : SimRegistry.values()) {
      if (value.device == device) {
        values.add(
            new SimValueInfo(
                value.name,
                value.handle,
                value.direction,
                value.type,
                value.longValue,
                value.doubleValue));
      }
    }
    return values.toArray(new SimValueInfo[0]);
  }

  /**
   * @param handle value handle
   * @return enum option descriptions, or {@code null}
   */
  public static String[] getSimValueEnumOptions(int handle) {
    SimRegistry.Value value = SimRegistry.value(handle);
    return value == null ? null : value.options;
  }

  /**
   * @param handle value handle
   * @return enum option values, or {@code null}
   */
  public static double[] getSimValueEnumDoubleValues(int handle) {
    SimRegistry.Value value = SimRegistry.value(handle);
    return value == null ? null : value.optionValues;
  }

  /** Removes every simulated device and value. */
  public static void resetSimDeviceData() {
    for (SimRegistry.Device device : SimRegistry.devices()) {
      SimRegistry.freeDevice(device.handle);
    }
  }

  /** Utility class. */
  private SimDeviceDataJNI() {}
}
