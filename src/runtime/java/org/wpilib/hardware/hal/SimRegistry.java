package org.wpilib.hardware.hal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory HAL simulation devices and values.
 *
 * <p>Web Image has no HAL, so the native sim device JNI is replaced by this registry. The same
 * registry backs {@link SimDeviceJNI} (used by {@code SimDevice} and the vendor sim classes through
 * {@code SimDeviceSim}) and the vendor low-level shims, which is what lets a simulated vendor
 * device exchange data with its Java sim class. Handles start at 1 and {@code 0} means "no
 * device/value", matching HAL.
 *
 * <p>Handles are sequential, so devices and values live in flat arrays indexed by handle. Sim
 * classes read and write values every 20 ms tick ({@code DCMotorSim}, vendor sims), and the browser
 * event loop is single-threaded, so no monitor is taken on those paths.
 */
public final class SimRegistry {
  /** One simulated value: a named int, long, double, boolean, or enum entry. */
  public static final class Value {
    public final int handle;
    public final int device;
    public final String name;
    public final int direction;
    public final int type;
    public final String[] options;
    public final double[] optionValues;
    public long longValue;
    public double doubleValue;

    private Value(
        int handle,
        int device,
        String name,
        int direction,
        int type,
        long longValue,
        double doubleValue,
        String[] options,
        double[] optionValues) {
      this.handle = handle;
      this.device = device;
      this.name = name;
      this.direction = direction;
      this.type = type;
      this.longValue = longValue;
      this.doubleValue = doubleValue;
      this.options = options;
      this.optionValues = optionValues;
    }
  }

  /** One simulated device and its named values. */
  public static final class Device {
    public final int handle;
    public final String name;
    private final Map<String, Value> values = new HashMap<>();

    private Device(int handle, String name) {
      this.handle = handle;
      this.name = name;
    }
  }

  private static final int INITIAL_HANDLES = 32;
  private static final Map<String, Device> DEVICES_BY_NAME = new HashMap<>();
  private static final Map<String, Boolean> ENABLED = new HashMap<>();
  private static Device[] devices = new Device[INITIAL_HANDLES];
  private static Value[] values = new Value[INITIAL_HANDLES];

  /** Devices and values share one handle space; index order is creation order. */
  private static int nextHandle = 1;

  private SimRegistry() {}

  private static void grow(int handle) {
    if (handle < devices.length) return;
    int capacity = devices.length;
    while (capacity <= handle) capacity <<= 1;
    devices = Arrays.copyOf(devices, capacity);
    values = Arrays.copyOf(values, capacity);
  }

  private static Device deviceAt(int handle) {
    Device[] all = devices;
    return handle > 0 && handle < all.length ? all[handle] : null;
  }

  private static Value valueAt(int handle) {
    Value[] all = values;
    return handle > 0 && handle < all.length ? all[handle] : null;
  }

  /**
   * @param name device name
   * @return the new device handle, or {@code 0} when the name is already in use
   */
  public static int createDevice(String name) {
    if (name == null || DEVICES_BY_NAME.containsKey(name)) {
      return 0;
    }
    Device device = new Device(nextHandle++, name);
    grow(device.handle);
    DEVICES_BY_NAME.put(name, device);
    devices[device.handle] = device;
    return device.handle;
  }

  /** Frees a device and every value on it. */
  public static void freeDevice(int handle) {
    Device device = deviceAt(handle);
    if (device == null) {
      return;
    }
    devices[handle] = null;
    DEVICES_BY_NAME.remove(device.name);
    for (Value value : device.values.values()) values[value.handle] = null;
    device.values.clear();
  }

  /**
   * @param handle device handle
   * @return the device, or {@code null}
   */
  public static Device device(int handle) {
    return deviceAt(handle);
  }

  /**
   * @param name device name
   * @return the device, or {@code null}
   */
  public static Device device(String name) {
    return DEVICES_BY_NAME.get(name);
  }

  /**
   * @return a snapshot of every device, in creation order
   */
  public static List<Device> devices() {
    List<Device> result = new ArrayList<>();
    for (Device device : devices) {
      if (device != null) result.add(device);
    }
    return result;
  }

  /**
   * @param handle value handle
   * @return the value, or {@code null}
   */
  public static Value value(int handle) {
    return valueAt(handle);
  }

  /**
   * @param device device handle
   * @param name value name
   * @return the value, or {@code null}
   */
  public static Value value(int device, String name) {
    Device owner = deviceAt(device);
    return owner == null ? null : owner.values.get(name);
  }

  /**
   * Creates a value, or returns the existing value with that name on the device.
   *
   * @return the value handle, or {@code 0} when the device is unknown
   */
  public static int createValue(
      int device,
      String name,
      int direction,
      int type,
      long longValue,
      double doubleValue,
      String[] options,
      double[] optionValues) {
    Device owner = deviceAt(device);
    if (owner == null || name == null) {
      return 0;
    }
    Value existing = owner.values.get(name);
    if (existing != null) {
      return existing.handle;
    }
    Value value =
        new Value(
            nextHandle++,
            device,
            name,
            direction,
            type,
            longValue,
            doubleValue,
            options,
            optionValues);
    grow(value.handle);
    owner.values.put(name, value);
    values[value.handle] = value;
    return value.handle;
  }

  /**
   * Copies a value's payload, keeping its type.
   *
   * @param handle value handle
   * @param type HAL value type
   * @param longValue long-valued payload
   * @param doubleValue double-valued payload
   */
  public static void write(int handle, int type, long longValue, double doubleValue) {
    Value value = valueAt(handle);
    if (value == null) {
      return;
    }
    value.longValue = longValue;
    value.doubleValue = doubleValue;
    // The value type is fixed at creation, matching HAL.
  }

  /** Resets a numeric value to zero, matching HAL reset semantics. */
  public static void reset(int handle) {
    Value value = valueAt(handle);
    if (value != null) {
      value.longValue = 0;
      value.doubleValue = 0;
    }
  }

  /** Enables or disables every device whose name starts with the prefix. */
  public static void setEnabled(String prefix, boolean enabled) {
    ENABLED.put(prefix == null ? "" : prefix, enabled);
  }

  /**
   * @param name device name
   * @return whether the name is enabled; names are enabled unless a prefix disables them
   */
  public static boolean isEnabled(String name) {
    String candidate = name == null ? "" : name;
    if (ENABLED.isEmpty()) return true;
    boolean enabled = true;
    for (Map.Entry<String, Boolean> entry : ENABLED.entrySet()) {
      if (candidate.startsWith(entry.getKey()) && !entry.getValue()) {
        enabled = false;
      }
    }
    return enabled;
  }

  /**
   * @return every live value, in creation order
   */
  public static List<Value> values() {
    List<Value> result = new ArrayList<>();
    for (Value value : values) {
      if (value != null) result.add(value);
    }
    return result;
  }
}
