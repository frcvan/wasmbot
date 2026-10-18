package org.wpilib.networktables;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSObject;
import org.graalvm.webimage.api.JSString;
import org.teamvan.wasmbot.JsBridge;

/**
 * Host-backed subset of NT JNI for default-instance typed double and struct publishers. The
 * ordinary SDK topic, publisher, and struct serialization implementations remain in use, preserving
 * their interface ABI. Server/client networking, listeners, queues, and other value types are
 * unsupported.
 */
public final class NetworkTablesJNI {
  private static final HexFormat HEX = HexFormat.of();
  private static final Map<String, Integer> topicHandles = new HashMap<>();
  private static final Map<Integer, JSString> topics = new HashMap<>();
  private static final Map<Integer, Publication> publications = new HashMap<>();
  private static int nextHandle = 2;

  private record Publication(int topic, JSObject owner) {}

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
          topics.put(handle, JSString.of(key));
          return handle;
        });
  }

  public static int publish(int topic, int type, String typeStr, PubSubOption... options) {
    if (options.length != 0)
      throw new UnsupportedOperationException("NT publisher options are not supported");
    if (!(type == NetworkTableType.DOUBLE.getValue() && "double".equals(typeStr))
        && !(type == NetworkTableType.RAW.getValue() && typeStr.startsWith("struct:"))) {
      throw new UnsupportedOperationException(
          "Only typed double and struct NT publishers are supported");
    }
    JSString path = topics.get(topic);
    if (path == null) throw new IllegalArgumentException("Unknown NT topic handle: " + topic);
    JSObject owner = publishValue(path, JSString.of(typeStr));
    int handle = nextHandle++;
    publications.put(handle, new Publication(topic, owner));
    return handle;
  }

  public static boolean setDouble(int entry, long time, double value) {
    Publication publication = publications.get(entry);
    if (publication == null) return false;
    double timestamp = time == 0 ? JsBridge.nowNanos() : time;
    return putDouble(publication.owner(), topics.get(publication.topic()), timestamp / 1000, value);
  }

  public static boolean setDefaultDouble(int entry, long time, double value) {
    Publication publication = publications.get(entry);
    if (publication == null || hasValue(topics.get(publication.topic()))) return false;
    return setDouble(entry, time, value);
  }

  /** Receives the SDK StructBuffer's packed bytes without copying its reusable direct buffer. */
  public static boolean setRaw(int entry, long time, ByteBuffer value, int start, int len) {
    Publication publication = publications.get(entry);
    if (publication == null) return false;
    Objects.checkFromIndexSize(start, len, value.limit());
    String packed;
    if (value.hasArray()) {
      int offset = value.arrayOffset() + start;
      packed = HEX.formatHex(value.array(), offset, offset + len);
    } else {
      StringBuilder hex = new StringBuilder(len * 2);
      for (int i = start; i < start + len; i++) {
        byte element = value.get(i);
        hex.append(HEX.toHighHexDigit(element)).append(HEX.toLowHexDigit(element));
      }
      packed = hex.toString();
    }
    double timestamp = time == 0 ? JsBridge.nowNanos() : time;
    return putRaw(
        publication.owner(),
        topics.get(publication.topic()),
        timestamp / 1000,
        JSString.of(packed));
  }

  public static boolean setDefaultRaw(int entry, long time, ByteBuffer value, int start, int len) {
    Publication publication = publications.get(entry);
    if (publication == null || hasValue(topics.get(publication.topic()))) return false;
    return setRaw(entry, time, value, start, len);
  }

  static void addStructSchema(String type, String schema) {
    publishSchema(JSString.of("/.schema/" + type), JSString.of(schema));
  }

  public static void unpublish(int pubentry) {
    Publication publication = publications.remove(pubentry);
    if (publication != null) unpublishValue(publication.owner(), topics.get(publication.topic()));
  }

  public static void release(int pubsubentry) {
    unpublish(pubsubentry);
  }

  @JS("const owner = {}; globalThis.wasmbot.nt.publish(owner, path, type, {}); return owner;")
  private static native JSObject publishValue(JSString path, JSString type);

  @JS.Coerce
  @JS("return globalThis.wasmbot.nt.put(owner, path, timestamp, value);")
  private static native boolean putDouble(
      JSObject owner, JSString path, double timestamp, double value);

  @JS.Coerce
  @JS("return globalThis.wasmbot.nt.put(owner, path, timestamp, value);")
  private static native boolean putRaw(
      JSObject owner, JSString path, double timestamp, JSString value);

  @JS(
      "const owner = {};"
          + " globalThis.wasmbot.nt.publish(owner, path, 'structschema', {retained: true});"
          + " globalThis.wasmbot.nt.put(owner, path, 0, new TextEncoder().encode(value));")
  private static native void publishSchema(JSString path, JSString value);

  @JS.Coerce
  @JS("return globalThis.wasmbot.ntGet(path) !== undefined;")
  private static native boolean hasValue(JSString path);

  @JS("globalThis.wasmbot.nt.unpublish(owner, path);")
  private static native void unpublishValue(JSObject owner, JSString path);
}
