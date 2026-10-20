package org.wpilib.networktables;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSObject;
import org.graalvm.webimage.api.JSString;
import org.teamvan.wasmbot.JsBytes;

/**
 * In-process NT JNI boundary. SDK tables, typed/generic publishers, entries, and struct
 * serialization remain in use. Host samples use microseconds; the public Alpha 7 API uses
 * nanoseconds. Network transports, native listeners, and subscriber queues are not implemented
 * here.
 */
public final class NetworkTablesJNI {
  private static final Map<String, Integer> topicHandles = new HashMap<>();
  private static final Map<Integer, String> topicNames = new HashMap<>();
  private static final Map<Integer, JSString> topics = new HashMap<>();
  private static final Map<Integer, Publication> publications = new HashMap<>();
  private static int nextHandle = 2;

  private static final class Publication {
    final int topic;
    final int type;
    final JSString typeString;
    final JSObject owner = newOwner();
    final boolean writable;
    boolean published;
    private JsBytes rawBytes;

    Publication(int topic, int type, String typeString, boolean writable) {
      this.topic = topic;
      this.type = type;
      this.typeString = JSString.of(typeString);
      this.writable = writable;
    }

    JsBytes rawBytes() {
      if (rawBytes == null) rawBytes = new JsBytes();
      return rawBytes;
    }
  }

  private NetworkTablesJNI() {}

  public static int getDefaultInstance() {
    return 1;
  }

  public static int getTopic(int inst, String name) {
    if (inst != 1)
      throw new UnsupportedOperationException("Only the default NT instance is supported");
    return topicHandles.computeIfAbsent(
        name,
        key -> {
          int handle = nextHandle++;
          topicNames.put(handle, key);
          topics.put(handle, JSString.of(key));
          return handle;
        });
  }

  private static int topicHandle(int handle) {
    Publication publication = publications.get(handle);
    return publication == null ? handle : publication.topic;
  }

  private static JSString path(int handle) {
    JSString path = topics.get(topicHandle(handle));
    if (path == null) throw new IllegalArgumentException("Unknown NT handle: " + handle);
    return path;
  }

  public static String getTopicName(int topic) {
    return topicNames.get(topicHandle(topic));
  }

  public static String getTopicTypeString(int topic) {
    return typeString(path(topic));
  }

  public static int getTopicType(int topic) {
    return NetworkTableType.getFromString(getTopicTypeString(topic)).getValue();
  }

  public static int getType(int handle) {
    return getTopicType(handle);
  }

  public static boolean getTopicExists(int topic) {
    return topicExists(path(topic));
  }

  public static long getEntryLastChange(int entry) {
    return (long) (lastChange(path(entry)) * 1000);
  }

  public static void setTopicProperty(int topic, String name, String value) {
    setProperty(path(topic), JSString.of(name), JSString.of(value));
  }

  public static void deleteTopicProperty(int topic, String name) {
    setTopicProperty(topic, name, "null");
  }

  public static String getTopicProperty(int topic, String name) {
    return getProperty(path(topic), JSString.of(name));
  }

  public static String getTopicProperties(int topic) {
    return getProperties(path(topic));
  }

  public static void setTopicProperties(int topic, String properties) {
    setProperties(path(topic), JSString.of(properties));
  }

  public static void setTopicRetained(int topic, boolean value) {
    setTopicProperty(topic, "retained", Boolean.toString(value));
  }

  public static boolean getTopicRetained(int topic) {
    return "true".equals(getTopicProperty(topic, "retained"));
  }

  public static void setTopicPersistent(int topic, boolean value) {
    setTopicProperty(topic, "persistent", Boolean.toString(value));
  }

  public static boolean getTopicPersistent(int topic) {
    return "true".equals(getTopicProperty(topic, "persistent"));
  }

  private static void checkOptions(PubSubOption[] options) {
    for (PubSubOption option : options) {
      // The host immediately forwards every sample (including duplicates). NT4 clients perform
      // their own periodic batching, so SEND_ALL and KEEP_DUPLICATES need no native queue.
      if (!(option instanceof PubSubOption.SendAll)
          && !(option instanceof PubSubOption.KeepDuplicates)
          && !(option instanceof PubSubOption.Periodic)) {
        throw new UnsupportedOperationException("Unsupported NT option: " + option);
      }
    }
  }

  private static int allocate(int topic, int type, String typeString, boolean writable) {
    path(topic);
    if (NetworkTableType.getFromString(typeString).getValue() != type)
      throw new IllegalArgumentException("NT type does not match declared type: " + typeString);
    int handle = nextHandle++;
    publications.put(handle, new Publication(topic, type, typeString, writable));
    return handle;
  }

  public static int publish(int topic, int type, String typeString, PubSubOption... options) {
    return publishEx(topic, type, typeString, "{}", options);
  }

  public static int publishEx(
      int topic, int type, String typeString, String properties, PubSubOption... options) {
    checkOptions(options);
    int handle = allocate(topic, type, typeString, true);
    Publication publication = publications.get(handle);
    publishValue(publication.owner, path(topic), publication.typeString, JSString.of(properties));
    publication.published = true;
    return handle;
  }

  public static int getEntry(int topic, int type, String typeString, PubSubOption... options) {
    checkOptions(options);
    return allocate(topic, type, typeString, true);
  }

  public static int subscribe(int topic, int type, String typeString, PubSubOption... options) {
    checkOptions(options);
    return allocate(topic, type, typeString, false);
  }

  private static Publication writable(int entry, NetworkTableType type) {
    Publication publication = publications.get(entry);
    if (publication == null || !publication.writable || publication.type != type.getValue())
      return null;
    if (!publication.published) {
      publishValue(publication.owner, path(entry), publication.typeString, JSString.of("{}"));
      publication.published = true;
    }
    return publication;
  }

  private static double timestamp(long time) {
    return (double) time / 1000;
  }

  private static boolean canSetDefault(int entry) {
    return publications.containsKey(entry) && !hasValue(path(entry));
  }

  public static boolean setBoolean(int entry, long time, boolean value) {
    Publication publication = writable(entry, NetworkTableType.BOOLEAN);
    return publication != null
        && putBoolean(publication.owner, path(entry), timestamp(time), value);
  }

  public static boolean setInteger(int entry, long time, long value) {
    Publication publication = writable(entry, NetworkTableType.INTEGER);
    return publication != null && putNumber(publication.owner, path(entry), timestamp(time), value);
  }

  public static boolean setFloat(int entry, long time, float value) {
    Publication publication = writable(entry, NetworkTableType.FLOAT);
    return publication != null && putNumber(publication.owner, path(entry), timestamp(time), value);
  }

  public static boolean setDouble(int entry, long time, double value) {
    Publication publication = writable(entry, NetworkTableType.DOUBLE);
    return publication != null && putNumber(publication.owner, path(entry), timestamp(time), value);
  }

  public static double getDouble(int entry, double defaultValue) {
    return readDouble(path(entry), defaultValue);
  }

  public static TimestampedDouble getAtomicDouble(int entry, double defaultValue) {
    return new TimestampedDouble(getEntryLastChange(entry), 0, getDouble(entry, defaultValue));
  }

  public static boolean setString(int entry, long time, String value) {
    Publication publication = writable(entry, NetworkTableType.STRING);
    return publication != null
        && putString(publication.owner, path(entry), timestamp(time), JSString.of(value));
  }

  public static boolean setRaw(int entry, long time, byte[] value) {
    return setRaw(entry, time, value, 0, value.length);
  }

  public static boolean setRaw(int entry, long time, byte[] value, int start, int len) {
    Publication publication = writable(entry, NetworkTableType.RAW);
    if (publication == null) return false;
    return publication
        .rawBytes()
        .put(publication.owner, path(entry), timestamp(time), value, start, len);
  }

  /** Publishes the SDK StructBuffer's absolute packed range without changing its state. */
  public static boolean setRaw(int entry, long time, ByteBuffer value, int start, int len) {
    Publication publication = writable(entry, NetworkTableType.RAW);
    if (publication == null) return false;
    return publication
        .rawBytes()
        .put(publication.owner, path(entry), timestamp(time), value, start, len);
  }

  public static boolean setBooleanArray(int entry, long time, boolean[] value) {
    Publication publication = writable(entry, NetworkTableType.BOOLEAN_ARRAY);
    if (publication == null) return false;
    JSObject array = newArray(value.length);
    for (int i = 0; i < value.length; i++) arrayBoolean(array, i, value[i]);
    return putArray(publication.owner, path(entry), timestamp(time), array);
  }

  public static boolean setIntegerArray(int entry, long time, long[] value) {
    Publication publication = writable(entry, NetworkTableType.INTEGER_ARRAY);
    if (publication == null) return false;
    JSObject array = newArray(value.length);
    for (int i = 0; i < value.length; i++) arrayNumber(array, i, value[i]);
    return putArray(publication.owner, path(entry), timestamp(time), array);
  }

  public static boolean setFloatArray(int entry, long time, float[] value) {
    Publication publication = writable(entry, NetworkTableType.FLOAT_ARRAY);
    if (publication == null) return false;
    JSObject array = newArray(value.length);
    for (int i = 0; i < value.length; i++) arrayNumber(array, i, value[i]);
    return putArray(publication.owner, path(entry), timestamp(time), array);
  }

  public static boolean setDoubleArray(int entry, long time, double[] value) {
    Publication publication = writable(entry, NetworkTableType.DOUBLE_ARRAY);
    if (publication == null) return false;
    JSObject array = newArray(value.length);
    for (int i = 0; i < value.length; i++) arrayNumber(array, i, value[i]);
    return putArray(publication.owner, path(entry), timestamp(time), array);
  }

  public static boolean setStringArray(int entry, long time, String[] value) {
    Publication publication = writable(entry, NetworkTableType.STRING_ARRAY);
    if (publication == null) return false;
    JSObject array = newArray(value.length);
    for (int i = 0; i < value.length; i++) arrayString(array, i, JSString.of(value[i]));
    return putArray(publication.owner, path(entry), timestamp(time), array);
  }

  public static boolean setDefaultBoolean(int entry, long time, boolean value) {
    return canSetDefault(entry) && setBoolean(entry, time, value);
  }

  public static boolean setDefaultInteger(int entry, long time, long value) {
    return canSetDefault(entry) && setInteger(entry, time, value);
  }

  public static boolean setDefaultFloat(int entry, long time, float value) {
    return canSetDefault(entry) && setFloat(entry, time, value);
  }

  public static boolean setDefaultDouble(int entry, long time, double value) {
    return canSetDefault(entry) && setDouble(entry, time, value);
  }

  public static boolean setDefaultString(int entry, long time, String value) {
    return canSetDefault(entry) && setString(entry, time, value);
  }

  public static boolean setDefaultRaw(int entry, long time, byte[] value) {
    return canSetDefault(entry) && setRaw(entry, time, value);
  }

  public static boolean setDefaultRaw(int entry, long time, byte[] value, int start, int len) {
    return canSetDefault(entry) && setRaw(entry, time, value, start, len);
  }

  public static boolean setDefaultRaw(int entry, long time, ByteBuffer value, int start, int len) {
    return canSetDefault(entry) && setRaw(entry, time, value, start, len);
  }

  public static boolean setDefaultBooleanArray(int entry, long time, boolean[] value) {
    return canSetDefault(entry) && setBooleanArray(entry, time, value);
  }

  public static boolean setDefaultIntegerArray(int entry, long time, long[] value) {
    return canSetDefault(entry) && setIntegerArray(entry, time, value);
  }

  public static boolean setDefaultFloatArray(int entry, long time, float[] value) {
    return canSetDefault(entry) && setFloatArray(entry, time, value);
  }

  public static boolean setDefaultDoubleArray(int entry, long time, double[] value) {
    return canSetDefault(entry) && setDoubleArray(entry, time, value);
  }

  public static boolean setDefaultStringArray(int entry, long time, String[] value) {
    return canSetDefault(entry) && setStringArray(entry, time, value);
  }

  static void addStructSchema(String type, String schema) {
    publishSchema(JSString.of("/.schema/" + type), JSString.of(schema));
  }

  static void addSchema(String name, String type, byte[] schema) {
    JSString path = JSString.of("/.schema/" + name);
    JSObject owner = newOwner();
    publishValue(owner, path, JSString.of(type), JSString.of("{\"retained\":true}"));
    new JsBytes().putSchema(owner, path, schema);
  }

  public static void unpublish(int pubentry) {
    Publication publication = publications.get(pubentry);
    if (publication != null && publication.published) {
      unpublishValue(publication.owner, path(pubentry));
      publication.published = false;
    }
  }

  public static void release(int pubsubentry) {
    unpublish(pubsubentry);
    publications.remove(pubsubentry);
  }

  @JS("return {};")
  private static native JSObject newOwner();

  @JS("globalThis.wasmbot.nt.publish(owner, path, type, JSON.parse(properties));")
  private static native void publishValue(
      JSObject owner, JSString path, JSString type, JSString properties);

  @JS.Coerce
  @JS(
      "return globalThis.wasmbot.nt.put(owner, path,"
          + " timestamp === 0 ? globalThis.wasmbot.nowNanos() / 1000 : timestamp, value);")
  private static native boolean putNumber(
      JSObject owner, JSString path, double timestamp, double value);

  @JS.Coerce
  @JS(
      "return globalThis.wasmbot.nt.put(owner, path,"
          + " timestamp === 0 ? globalThis.wasmbot.nowNanos() / 1000 : timestamp, value);")
  private static native boolean putBoolean(
      JSObject owner, JSString path, double timestamp, boolean value);

  @JS.Coerce
  @JS(
      "return globalThis.wasmbot.nt.put(owner, path,"
          + " timestamp === 0 ? globalThis.wasmbot.nowNanos() / 1000 : timestamp, value);")
  private static native boolean putString(
      JSObject owner, JSString path, double timestamp, JSString value);

  @JS.Coerce
  @JS(
      "return globalThis.wasmbot.nt.put(owner, path,"
          + " timestamp === 0 ? globalThis.wasmbot.nowNanos() / 1000 : timestamp, value);")
  private static native boolean putArray(
      JSObject owner, JSString path, double timestamp, JSObject value);

  @JS.Coerce
  @JS("return new Array(length);")
  private static native JSObject newArray(int length);

  @JS.Coerce
  @JS("array[index] = value;")
  private static native void arrayBoolean(JSObject array, int index, boolean value);

  @JS.Coerce
  @JS("array[index] = value;")
  private static native void arrayNumber(JSObject array, int index, double value);

  @JS.Coerce
  @JS("array[index] = value;")
  private static native void arrayString(JSObject array, int index, JSString value);

  @JS(
      "const owner = {}; globalThis.wasmbot.nt.publish(owner, path, 'structschema', {retained: true});"
          + " globalThis.wasmbot.nt.put(owner, path, 0, new TextEncoder().encode(value));")
  private static native void publishSchema(JSString path, JSString value);

  @JS.Coerce
  @JS("return globalThis.wasmbot.ntGet(path) !== undefined;")
  private static native boolean hasValue(JSString path);

  @JS.Coerce
  @JS(
      "const topic = globalThis.wasmbot.nt.getTopic(path);"
          + " return topic?.type === 'double' && typeof topic.value === 'number' ? topic.value : fallback;")
  private static native double readDouble(JSString path, double fallback);

  @JS.Coerce
  @JS("return globalThis.wasmbot.nt.getTopic(path)?.type ?? '';")
  private static native String typeString(JSString path);

  @JS.Coerce
  @JS("return globalThis.wasmbot.nt.getTopic(path) !== undefined;")
  private static native boolean topicExists(JSString path);

  @JS.Coerce
  @JS("return globalThis.wasmbot.nt.getTopic(path)?.timestamp ?? 0;")
  private static native double lastChange(JSString path);

  @JS("globalThis.wasmbot.nt.setProperties(path, {[name]: JSON.parse(value)});")
  private static native void setProperty(JSString path, JSString name, JSString value);

  @JS.Coerce
  @JS("return JSON.stringify(globalThis.wasmbot.nt.getTopic(path)?.properties[name] ?? null);")
  private static native String getProperty(JSString path, JSString name);

  @JS.Coerce
  @JS("return JSON.stringify(globalThis.wasmbot.nt.getTopic(path)?.properties ?? {});")
  private static native String getProperties(JSString path);

  @JS("globalThis.wasmbot.nt.setProperties(path, JSON.parse(properties));")
  private static native void setProperties(JSString path, JSString properties);

  @JS("globalThis.wasmbot.nt.unpublish(owner, path);")
  private static native void unpublishValue(JSObject owner, JSString path);
}
