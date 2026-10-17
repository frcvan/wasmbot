package org.wpilib.networktables;

import java.util.HashMap;
import java.util.Map;
import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSObject;
import org.graalvm.webimage.api.JSString;
import org.teamvan.wasmbot.JsBridge;

/**
 * Host-backed subset of NT JNI for default-instance typed double publishers.
 * The ordinary SDK DoubleTopic and DoublePublisher remain in use.
 * Server/client networking, listeners, queues, and other value types are not implemented here.
 */
public final class NetworkTablesJNI {
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
    if (inst != 1) throw new UnsupportedOperationException("Only the default NT instance is supported");
    return topicHandles.computeIfAbsent(name, key -> {
      int handle = nextHandle++;
      topics.put(handle, JSString.of(key));
      return handle;
    });
  }

  public static int publish(int topic, int type, String typeStr, PubSubOption... options) {
    if (options.length != 0) throw new UnsupportedOperationException("NT publisher options are not supported");
    if (type != NetworkTableType.DOUBLE.getValue() || !"double".equals(typeStr)) {
      throw new UnsupportedOperationException("Only typed double NT publishers are supported");
    }
    JSString path = topics.get(topic);
    if (path == null) throw new IllegalArgumentException("Unknown NT topic handle: " + topic);
    JSObject owner = publishDouble(path);
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

  public static void unpublish(int pubentry) {
    Publication publication = publications.remove(pubentry);
    if (publication != null) unpublishDouble(publication.owner(), topics.get(publication.topic()));
  }

  public static void release(int pubsubentry) {
    unpublish(pubsubentry);
  }

  @JS("const owner = {}; globalThis.wasmbot.nt.publish(owner, path, 'double', {}); return owner;")
  private static native JSObject publishDouble(JSString path);

  @JS.Coerce
  @JS("return globalThis.wasmbot.nt.put(owner, path, timestamp, value);")
  private static native boolean putDouble(JSObject owner, JSString path, double timestamp, double value);

  @JS.Coerce
  @JS("return globalThis.wasmbot.ntGet(path) !== undefined;")
  private static native boolean hasValue(JSString path);

  @JS("globalThis.wasmbot.nt.unpublish(owner, path);")
  private static native void unpublishDouble(JSObject owner, JSString path);
}
