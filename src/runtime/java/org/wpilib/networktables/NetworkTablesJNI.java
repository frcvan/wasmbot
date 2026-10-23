package org.wpilib.networktables;

import java.nio.ByteBuffer;
import java.util.Arrays;
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
 *
 * <p>Handles are allocated sequentially starting at 2, so topic and publication state lives in flat
 * arrays indexed by handle instead of boxed map keys. In a 50 Hz robot loop that keeps every {@code
 * setDouble} / {@code getDouble} on a bounds check and one array read.
 */
public final class NetworkTablesJNI {
  private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
  private static final int INITIAL_HANDLES = 64;
  private static final JSString EMPTY_PROPERTIES = JSString.of("{}");
  private static final JSString RETAINED_PROPERTIES = JSString.of("{\"retained\":true}");

  /** Topic names are resolved once per publication, never per sample. */
  private static final Map<String, Topic> topicByName = new HashMap<>();

  private static Topic[] topics = new Topic[INITIAL_HANDLES];
  private static Publication[] publications = new Publication[INITIAL_HANDLES];
  private static int nextHandle = 2;

  /** One NetworkTables topic name and its cached JavaScript path string. */
  private static final class Topic {
    final int handle;
    final String name;
    final JSString path;

    Topic(int handle, String name) {
      this.handle = handle;
      this.name = name;
      this.path = JSString.of(name);
    }
  }

  private static final class Publication {
    final Topic topic;
    final JSString path;
    final int type;
    final JSString typeString;
    final JSObject owner = newOwner();
    final boolean writable;
    boolean published;
    private JsBytes rawBytes;

    Publication(Topic topic, int type, String typeString, boolean writable) {
      this.topic = topic;
      this.path = topic.path;
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
    Topic topic = topicByName.get(name);
    if (topic == null) {
      topic = new Topic(nextHandle++, name);
      grow(topic.handle);
      topicByName.put(name, topic);
      topics[topic.handle] = topic;
    }
    return topic.handle;
  }

  private static void grow(int handle) {
    if (handle < topics.length) return;
    int capacity = topics.length;
    while (capacity <= handle) capacity <<= 1;
    topics = Arrays.copyOf(topics, capacity);
    publications = Arrays.copyOf(publications, capacity);
  }

  private static Publication publication(int handle) {
    Publication[] all = publications;
    return handle >= 0 && handle < all.length ? all[handle] : null;
  }

  private static Topic topic(int handle) {
    Topic[] all = topics;
    return handle >= 0 && handle < all.length ? all[handle] : null;
  }

  private static Topic resolveTopic(int handle) {
    Publication publication = publication(handle);
    return topic(publication == null ? handle : publication.topic.handle);
  }

  private static JSString path(int handle) {
    Publication publication = publication(handle);
    if (publication != null) return publication.path;
    Topic topic = topic(handle);
    if (topic == null) throw new IllegalArgumentException("Unknown NT handle: " + handle);
    return topic.path;
  }

  public static String getTopicName(int topic) {
    Topic resolved = resolveTopic(topic);
    return resolved == null ? null : resolved.name;
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
    Topic owner = resolveTopic(topic);
    if (owner == null) throw new IllegalArgumentException("Unknown NT topic: " + topic);
    if (NetworkTableType.getFromString(typeString).getValue() != type)
      throw new IllegalArgumentException("NT type does not match declared type: " + typeString);
    int handle = nextHandle++;
    grow(handle);
    publications[handle] = new Publication(owner, type, typeString, writable);
    return handle;
  }

  public static int publish(int topic, int type, String typeString, PubSubOption... options) {
    return publishEx(topic, type, typeString, "{}", options);
  }

  public static int publishEx(
      int topic, int type, String typeString, String properties, PubSubOption... options) {
    checkOptions(options);
    int handle = allocate(topic, type, typeString, true);
    Publication publication = publications[handle];
    publishValue(
        publication.owner, publication.path, publication.typeString, properties(properties));
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
    Publication publication = publication(entry);
    if (publication == null || !publication.writable || publication.type != type.getValue())
      return null;
    if (!publication.published) {
      publishValue(publication.owner, publication.path, publication.typeString, EMPTY_PROPERTIES);
      publication.published = true;
    }
    return publication;
  }

  private static double timestamp(long time) {
    return (double) time / 1000;
  }

  private static boolean canSetDefault(int entry) {
    Publication publication = publication(entry);
    return publication != null && !hasValue(publication.path);
  }

  public static boolean setBoolean(int entry, long time, boolean value) {
    Publication publication = writable(entry, NetworkTableType.BOOLEAN);
    return publication != null
        && putBoolean(publication.owner, publication.path, timestamp(time), value);
  }

  public static boolean setInteger(int entry, long time, long value) {
    Publication publication = writable(entry, NetworkTableType.INTEGER);
    if (publication == null) return false;
    // JavaScript numbers lose precision beyond 2^53 - 1, so exact int64 values travel as
    // decimal strings, as they do for telemetry entries.
    return isSafeInteger(value)
        ? putNumber(publication.owner, publication.path, timestamp(time), value)
        : putString(
            publication.owner,
            publication.path,
            timestamp(time),
            JSString.of(Long.toString(value)));
  }

  public static boolean setFloat(int entry, long time, float value) {
    Publication publication = writable(entry, NetworkTableType.FLOAT);
    return publication != null
        && putNumber(publication.owner, publication.path, timestamp(time), value);
  }

  public static boolean setDouble(int entry, long time, double value) {
    Publication publication = writable(entry, NetworkTableType.DOUBLE);
    return publication != null
        && putNumber(publication.owner, publication.path, timestamp(time), value);
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
        && putString(publication.owner, publication.path, timestamp(time), JSString.of(value));
  }

  public static boolean setRaw(int entry, long time, byte[] value) {
    return setRaw(entry, time, value, 0, value.length);
  }

  public static boolean setRaw(int entry, long time, byte[] value, int start, int len) {
    Publication publication = writable(entry, NetworkTableType.RAW);
    if (publication == null) return false;
    return publication
        .rawBytes()
        .put(publication.owner, publication.path, timestamp(time), value, start, len);
  }

  /** Publishes the SDK StructBuffer's absolute packed range without changing its state. */
  public static boolean setRaw(int entry, long time, ByteBuffer value, int start, int len) {
    Publication publication = writable(entry, NetworkTableType.RAW);
    if (publication == null) return false;
    return publication
        .rawBytes()
        .put(publication.owner, publication.path, timestamp(time), value, start, len);
  }

  public static boolean setBooleanArray(int entry, long time, boolean[] value) {
    Publication publication = writable(entry, NetworkTableType.BOOLEAN_ARRAY);
    if (publication == null) return false;
    JSObject array = newArray(value.length);
    fillBooleans(array, value);
    return putArray(publication.owner, publication.path, timestamp(time), array);
  }

  public static boolean setIntegerArray(int entry, long time, long[] value) {
    Publication publication = writable(entry, NetworkTableType.INTEGER_ARRAY);
    if (publication == null) return false;
    JSObject array = newArray(value.length);
    if (allSafeIntegers(value)) {
      fillNumbers(array, value);
    } else {
      for (int i = 0; i < value.length; i++) {
        if (isSafeInteger(value[i])) arrayNumber(array, i, value[i]);
        else arrayString(array, i, JSString.of(Long.toString(value[i])));
      }
    }
    return putArray(publication.owner, publication.path, timestamp(time), array);
  }

  private static boolean allSafeIntegers(long[] value) {
    for (long item : value) {
      if (!isSafeInteger(item)) return false;
    }
    return true;
  }

  private static boolean isSafeInteger(long value) {
    return value >= -MAX_SAFE_INTEGER && value <= MAX_SAFE_INTEGER;
  }

  public static boolean setFloatArray(int entry, long time, float[] value) {
    Publication publication = writable(entry, NetworkTableType.FLOAT_ARRAY);
    if (publication == null) return false;
    JSObject array = newArray(value.length);
    fillNumbers(array, value);
    return putArray(publication.owner, publication.path, timestamp(time), array);
  }

  public static boolean setDoubleArray(int entry, long time, double[] value) {
    Publication publication = writable(entry, NetworkTableType.DOUBLE_ARRAY);
    if (publication == null) return false;
    JSObject array = newArray(value.length);
    fillNumbers(array, value);
    return putArray(publication.owner, publication.path, timestamp(time), array);
  }

  public static boolean setStringArray(int entry, long time, String[] value) {
    Publication publication = writable(entry, NetworkTableType.STRING_ARRAY);
    if (publication == null) return false;
    JSObject array = newArray(value.length);
    fillStrings(array, value);
    return putArray(publication.owner, publication.path, timestamp(time), array);
  }

  /**
   * Writes four elements per JS crossing. Element-at-a-time writes cost one boundary crossing per
   * element, which dominates short telemetry arrays (poses, module states, trajectories).
   */
  private static void fillNumbers(JSObject array, double[] value) {
    int i = 0;
    for (int end = value.length - 3; i < end; i += 4)
      arrayNumbers4(array, i, value[i], value[i + 1], value[i + 2], value[i + 3]);
    switch (value.length - i) {
      case 3 -> arrayNumbers3(array, i, value[i], value[i + 1], value[i + 2]);
      case 2 -> arrayNumbers2(array, i, value[i], value[i + 1]);
      case 1 -> arrayNumber(array, i, value[i]);
      default -> {}
    }
  }

  private static void fillNumbers(JSObject array, float[] value) {
    int i = 0;
    for (int end = value.length - 3; i < end; i += 4)
      arrayNumbers4(array, i, value[i], value[i + 1], value[i + 2], value[i + 3]);
    switch (value.length - i) {
      case 3 -> arrayNumbers3(array, i, value[i], value[i + 1], value[i + 2]);
      case 2 -> arrayNumbers2(array, i, value[i], value[i + 1]);
      case 1 -> arrayNumber(array, i, value[i]);
      default -> {}
    }
  }

  private static void fillNumbers(JSObject array, long[] value) {
    int i = 0;
    for (int end = value.length - 3; i < end; i += 4)
      arrayNumbers4(array, i, value[i], value[i + 1], value[i + 2], value[i + 3]);
    switch (value.length - i) {
      case 3 -> arrayNumbers3(array, i, value[i], value[i + 1], value[i + 2]);
      case 2 -> arrayNumbers2(array, i, value[i], value[i + 1]);
      case 1 -> arrayNumber(array, i, value[i]);
      default -> {}
    }
  }

  private static void fillBooleans(JSObject array, boolean[] value) {
    int i = 0;
    for (int end = value.length - 3; i < end; i += 4)
      arrayBooleans4(array, i, value[i], value[i + 1], value[i + 2], value[i + 3]);
    switch (value.length - i) {
      case 3 -> arrayBooleans3(array, i, value[i], value[i + 1], value[i + 2]);
      case 2 -> arrayBooleans2(array, i, value[i], value[i + 1]);
      case 1 -> arrayBoolean(array, i, value[i]);
      default -> {}
    }
  }

  private static void fillStrings(JSObject array, String[] value) {
    int i = 0;
    for (int end = value.length - 3; i < end; i += 4)
      arrayStrings4(
          array,
          i,
          JSString.of(value[i]),
          JSString.of(value[i + 1]),
          JSString.of(value[i + 2]),
          JSString.of(value[i + 3]));
    switch (value.length - i) {
      case 3 ->
          arrayStrings3(
              array,
              i,
              JSString.of(value[i]),
              JSString.of(value[i + 1]),
              JSString.of(value[i + 2]));
      case 2 -> arrayStrings2(array, i, JSString.of(value[i]), JSString.of(value[i + 1]));
      case 1 -> arrayString(array, i, JSString.of(value[i]));
      default -> {}
    }
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
    publishValue(owner, path, JSString.of(type), RETAINED_PROPERTIES);
    new JsBytes().putSchema(owner, path, schema);
  }

  public static void unpublish(int pubentry) {
    Publication publication = publication(pubentry);
    if (publication != null && publication.published) {
      unpublishValue(publication.owner, publication.path);
      publication.published = false;
    }
  }

  public static void release(int pubsubentry) {
    unpublish(pubsubentry);
    Publication[] all = publications;
    if (pubsubentry >= 0 && pubsubentry < all.length) all[pubsubentry] = null;
  }

  private static JSString properties(String json) {
    return json == null || "{}".equals(json) ? EMPTY_PROPERTIES : JSString.of(json);
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

  /** Arrays built by {@code newArray} here are handed to the transport without a second copy. */
  @JS.Coerce
  @JS(
      "return globalThis.wasmbot.nt.put(owner, path,"
          + " timestamp === 0 ? globalThis.wasmbot.nowNanos() / 1000 : timestamp, value, true);")
  private static native boolean putArray(
      JSObject owner, JSString path, double timestamp, JSObject value);

  @JS.Coerce
  @JS("return new Array(length);")
  private static native JSObject newArray(int length);

  @JS.Coerce
  @JS("array[index] = value;")
  private static native void arrayBoolean(JSObject array, int index, boolean value);

  @JS.Coerce
  @JS("array[index] = a; array[index + 1] = b;")
  private static native void arrayBooleans2(JSObject array, int index, boolean a, boolean b);

  @JS.Coerce
  @JS("array[index] = a; array[index + 1] = b; array[index + 2] = c;")
  private static native void arrayBooleans3(
      JSObject array, int index, boolean a, boolean b, boolean c);

  @JS.Coerce
  @JS("array[index] = a; array[index + 1] = b; array[index + 2] = c; array[index + 3] = d;")
  private static native void arrayBooleans4(
      JSObject array, int index, boolean a, boolean b, boolean c, boolean d);

  @JS.Coerce
  @JS("array[index] = value;")
  private static native void arrayNumber(JSObject array, int index, double value);

  @JS.Coerce
  @JS("array[index] = a; array[index + 1] = b;")
  private static native void arrayNumbers2(JSObject array, int index, double a, double b);

  @JS.Coerce
  @JS("array[index] = a; array[index + 1] = b; array[index + 2] = c;")
  private static native void arrayNumbers3(JSObject array, int index, double a, double b, double c);

  @JS.Coerce
  @JS("array[index] = a; array[index + 1] = b; array[index + 2] = c; array[index + 3] = d;")
  private static native void arrayNumbers4(
      JSObject array, int index, double a, double b, double c, double d);

  @JS.Coerce
  @JS("array[index] = value;")
  private static native void arrayString(JSObject array, int index, JSString value);

  @JS.Coerce
  @JS("array[index] = a; array[index + 1] = b;")
  private static native void arrayStrings2(JSObject array, int index, JSString a, JSString b);

  @JS.Coerce
  @JS("array[index] = a; array[index + 1] = b; array[index + 2] = c;")
  private static native void arrayStrings3(
      JSObject array, int index, JSString a, JSString b, JSString c);

  @JS.Coerce
  @JS("array[index] = a; array[index + 1] = b; array[index + 2] = c; array[index + 3] = d;")
  private static native void arrayStrings4(
      JSObject array, int index, JSString a, JSString b, JSString c, JSString d);

  @JS("globalThis.wasmbot.nt.putSchema(path, value);")
  private static native void publishSchema(JSString path, JSString value);

  @JS.Coerce
  @JS("return globalThis.wasmbot.nt.getTopic(path)?.value !== undefined;")
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
