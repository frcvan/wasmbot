package org.teamvan.wasmbot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSObject;
import org.graalvm.webimage.api.JSString;
import org.wpilib.tunable.ComplexTunable;
import org.wpilib.tunable.Tunable;
import org.wpilib.tunable.TunableBackend;
import org.wpilib.tunable.TunableBase;
import org.wpilib.tunable.TunableBoolean;
import org.wpilib.tunable.TunableConfig;
import org.wpilib.tunable.TunableDouble;
import org.wpilib.tunable.TunableFloat;
import org.wpilib.tunable.TunableInt;
import org.wpilib.tunable.TunableLong;
import org.wpilib.tunable.TunableRegistry;
import org.wpilib.tunable.util.PathUtil;

/**
 * Host-backed backend for SDK primitive and string tunables, including Selectable's real children.
 * Network writes are sampled only by TunableRegistry.update() on the robot tick thread. Robust
 * entries use /tune for requests and /value for acknowledgements, exactly like the SDK NT backend.
 */
public final class JsTunables implements TunableBackend {
  private static final JSString EMPTY_PROPERTIES = JSString.of("{}");

  private final Map<String, Stored> entries = new LinkedHashMap<>();
  private Stored[] snapshot;
  private boolean closed;

  private static final class Stored {
    final String path;
    final TunableBase tunable;
    final ComplexTunable complex;
    final JSObject state;
    final String kind;
    final boolean mutable;
    boolean dirty = true;

    Stored(String path, TunableBase tunable, ComplexTunable complex, String kind, JSObject state) {
      this.path = path;
      this.tunable = tunable;
      this.complex = complex;
      this.kind = kind;
      this.state = state;
      mutable = tunable != null && (tunable.getConfig() == null || tunable.getConfig().isMutable());
    }
  }

  /** Install before constructing the robot; the normal SDK loop updates the registry. */
  public static void install() {
    TunableRegistry.registerBackend("", new JsTunables());
  }

  @Override
  public boolean publish(String path, TunableBase tunable) {
    path = PathUtil.normalizeName(path);
    if (closed) return false;
    if (entries.containsKey(path)) {
      warn(path, "already published");
      return false;
    }
    String kind = kind(tunable);
    if (kind == null) {
      warn(path, "unsupported tunable type: " + tunable.getClass().getName());
      return false;
    }
    TunableConfig config = tunable.getConfig();
    String type = config != null && config.getTypeString() != null ? config.getTypeString() : kind;
    JSObject state =
        createState(
            JSString.of("/Tunables" + path),
            JSString.of(type),
            JSString.of(tunable.getProperties()),
            tunable.isRobust(),
            config == null || config.isMutable());
    Stored stored = new Stored(path, tunable, null, kind, state);
    try {
      publishValue(stored);
    } catch (RuntimeException e) {
      closeState(state);
      warn(path, "publication failed: " + e);
      return false;
    }
    entries.put(path, stored);
    snapshot = null;
    return true;
  }

  @Override
  public boolean publishComplex(String path, ComplexTunable tunable) {
    path = PathUtil.normalizeName(path);
    if (closed) return false;
    if (entries.containsKey(path)) {
      warn(path, "already published");
      return false;
    }
    JSObject state = null;
    String type = tunable.getTunableType();
    if (type != null) {
      state =
          createState(
              JSString.of("/Tunables" + path + "/.type"),
              JSString.of("string"),
              EMPTY_PROPERTIES,
              false,
              false);
      putString(state, JSString.of(type));
    }
    entries.put(path, new Stored(path, null, tunable, null, state));
    snapshot = null;
    TunableRegistry.publishComplexChildren(path, tunable);
    return true;
  }

  private static String kind(TunableBase tunable) {
    if (tunable instanceof TunableBoolean) return "boolean";
    if (tunable instanceof TunableInt || tunable instanceof TunableLong) return "int";
    if (tunable instanceof TunableFloat) return "float";
    if (tunable instanceof TunableDouble) return "double";
    if (tunable instanceof Tunable<?> value) {
      if (value.getTypeClass() == String.class) return "string";
      if (value.getTypeClass() == String[].class) return "string[]";
    }
    return null;
  }

  @Override
  public void remove(String path) {
    Stored stored = entries.remove(PathUtil.normalizeName(path));
    if (stored != null) {
      if (stored.state != null) closeState(stored.state);
      snapshot = null;
    }
  }

  @Override
  public List<PublishedTunable> removePrefix(String prefix) {
    String normalized = PathUtil.normalizePrefix(prefix);
    List<PublishedTunable> removed = new ArrayList<>();
    var iterator = entries.values().iterator();
    while (iterator.hasNext()) {
      Stored stored = iterator.next();
      if (!PathUtil.isPathOrDescendant(stored.path, normalized)) continue;
      removed.add(new PublishedTunable(stored.path, stored.tunable, stored.complex));
      if (stored.state != null) closeState(stored.state);
      iterator.remove();
    }
    if (!removed.isEmpty()) snapshot = null;
    return removed;
  }

  @Override
  public void markDirty(TunableBase tunable) {
    for (Stored stored : entries.values()) {
      if (stored.tunable == tunable) stored.dirty = true;
    }
  }

  @Override
  public void update() {
    if (closed) return;
    if (snapshot == null) snapshot = entries.values().toArray(new Stored[0]);
    Stored[] current = snapshot;
    // Snapshot replacement happens only on registration/removal, not every tick. Complex updates
    // may add/remove children without invalidating iteration or invoking a removed child's setter.
    for (Stored stored : current) {
      if (entries.get(stored.path) != stored || stored.tunable == null) continue;
      try {
        boolean tuned = stored.mutable && takeInput(stored.state);
        if (tuned) applyInput(stored);
        TunableConfig config = stored.tunable.getConfig();
        if (tuned
            || stored.dirty
            || stored.tunable.hasChanged()
            || config != null && config.isAlwaysGet()
            || !stored.tunable.supportsChangeNotification()) {
          publishValue(stored);
          stored.dirty = false;
          TunableRegistry.resetChangedAfterUpdate(stored.tunable);
        }
        if (tuned && config != null && config.getOnTune() != null)
          TunableRegistry.runAfterUpdate(config.getOnTune());
      } catch (RuntimeException e) {
        warn(stored.path, "update failed: " + e);
      }
    }
    for (Stored stored : current) {
      if (entries.get(stored.path) != stored || stored.complex == null) continue;
      try {
        TunableRegistry.updateComplexIfNeeded(stored.complex);
      } catch (RuntimeException e) {
        warn(stored.path, "complex update failed: " + e);
      }
    }
  }

  private static void publishValue(Stored stored) {
    TunableBase tunable = stored.tunable;
    if (tunable instanceof TunableBoolean value) putBoolean(stored.state, value.get());
    else if (tunable instanceof TunableInt value) putNumber(stored.state, value.get());
    else if (tunable instanceof TunableLong value) putNumber(stored.state, value.get());
    else if (tunable instanceof TunableFloat value) putNumber(stored.state, value.get());
    else if (tunable instanceof TunableDouble value) putNumber(stored.state, value.get());
    else if (tunable instanceof Tunable<?> value) {
      Object data = value.get();
      if (data instanceof String text) putString(stored.state, JSString.of(text));
      else if (data instanceof String[] strings) {
        JSObject array = newArray(strings.length);
        for (int i = 0; i < strings.length; i++) arrayString(array, i, JSString.of(strings[i]));
        putArray(stored.state, array);
      } else if (data == null) {
        warn(stored.path, "null value retained; no sample published");
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static void applyInput(Stored stored) {
    TunableBase tunable = stored.tunable;
    if (tunable instanceof TunableBoolean value) value.set(inputBoolean(stored.state));
    else if (tunable instanceof TunableInt value) value.set((int) inputNumber(stored.state));
    else if (tunable instanceof TunableLong value) value.set((long) inputNumber(stored.state));
    else if (tunable instanceof TunableFloat value) value.set((float) inputNumber(stored.state));
    else if (tunable instanceof TunableDouble value) value.set(inputNumber(stored.state));
    else if ("string".equals(stored.kind))
      ((Tunable<String>) tunable).set(inputString(stored.state));
    else if ("string[]".equals(stored.kind)) {
      String[] strings = new String[inputLength(stored.state)];
      for (int i = 0; i < strings.length; i++) strings[i] = inputStringAt(stored.state, i);
      ((Tunable<String[]>) tunable).set(strings);
    }
  }

  private static void warn(String path, String message) {
    TunableRegistry.reportWarning("Host tunable '/Tunables" + path + "': " + message);
  }

  @Override
  public void close() {
    for (Stored stored : entries.values()) if (stored.state != null) closeState(stored.state);
    entries.clear();
    snapshot = null;
    closed = true;
  }

  @JS.Coerce
  @JS(
      "const properties = JSON.parse(json); properties.mutable = mutable;"
          + " if (robust) properties.robust = true;"
          + " const state = {owner: {}, output: path + (robust ? '/value' : ''),"
          + " input: path + (robust ? '/tune' : ''), type, topic: null, revision: -1};"
          + " globalThis.wasmbot.nt.publish(state.owner, state.output, type, properties); return state;")
  private static native JSObject createState(
      JSString path, JSString type, JSString json, boolean robust, boolean mutable);

  @JS.Coerce
  @JS(
      "const topic = globalThis.wasmbot.nt.getTopic(state.input);"
          + " if (!topic || topic.type !== state.type || topic.value === undefined) return false;"
          + " if (state.topic === topic && state.revision === topic.revision) return false;"
          + " state.topic = topic; state.revision = topic.revision;"
          + " if (topic.lastPublisher === state.owner) return false;"
          + " state.value = topic.value; return true;")
  private static native boolean takeInput(JSObject state);

  @JS("globalThis.wasmbot.nt.unpublish(state.owner, state.output);")
  private static native void closeState(JSObject state);

  @JS.Coerce
  @JS(
      "globalThis.wasmbot.nt.put(state.owner, state.output, globalThis.wasmbot.nowNanos() / 1000, value);")
  private static native void putBoolean(JSObject state, boolean value);

  @JS.Coerce
  @JS(
      "globalThis.wasmbot.nt.put(state.owner, state.output, globalThis.wasmbot.nowNanos() / 1000, value);")
  private static native void putNumber(JSObject state, double value);

  @JS(
      "globalThis.wasmbot.nt.put(state.owner, state.output, globalThis.wasmbot.nowNanos() / 1000, value);")
  private static native void putString(JSObject state, JSString value);

  @JS(
      "globalThis.wasmbot.nt.put(state.owner, state.output, globalThis.wasmbot.nowNanos() / 1000, value);")
  private static native void putArray(JSObject state, JSObject value);

  @JS.Coerce
  @JS("return state.value;")
  private static native boolean inputBoolean(JSObject state);

  @JS.Coerce
  @JS("return state.value;")
  private static native double inputNumber(JSObject state);

  @JS.Coerce
  @JS("return state.value;")
  private static native String inputString(JSObject state);

  @JS.Coerce
  @JS("return state.value.length;")
  private static native int inputLength(JSObject state);

  @JS.Coerce
  @JS("return state.value[index];")
  private static native String inputStringAt(JSObject state, int index);

  @JS.Coerce
  @JS("return new Array(length);")
  private static native JSObject newArray(int length);

  @JS.Coerce
  @JS("array[index] = value;")
  private static native void arrayString(JSObject array, int index, JSString value);
}
