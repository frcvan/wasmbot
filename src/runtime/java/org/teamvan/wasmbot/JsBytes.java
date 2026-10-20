package org.teamvan.wasmbot;

import java.nio.ByteBuffer;
import java.util.Objects;
import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSObject;
import org.graalvm.webimage.api.JSString;

/**
 * Publishes byte snapshots without hex or per-byte JS calls. Web Image's WASM backend does not
 * coerce primitive arrays to typed arrays: two bytes travel in each UTF-16 code unit instead. The
 * carrier is private to the bridge; the host receives a Uint8Array and snapshots it on put.
 */
public final class JsBytes {
  private char[] words = new char[0];

  /** Publishes a byte-array range, resolving timestamp zero against the host clock. */
  public boolean put(
      JSObject owner, JSString path, double timestamp, byte[] value, int start, int length) {
    return putPacked(owner, path, timestamp, pack(value, start, length), length, true);
  }

  /** Publishes an absolute buffer range without changing its position, limit, or byte order. */
  public boolean put(
      JSObject owner, JSString path, double timestamp, ByteBuffer value, int start, int length) {
    Objects.checkFromIndexSize(start, length, value.limit());
    if (value.hasArray()) {
      return put(owner, path, timestamp, value.array(), value.arrayOffset() + start, length);
    }
    int count = prepare(length);
    int end = start + length;
    int index = 0;
    for (; start < end - 1; start += 2) {
      words[index++] = (char) ((value.get(start) & 0xff) | ((value.get(start + 1) & 0xff) << 8));
    }
    if (start < end) words[index] = (char) (value.get(start) & 0xff);
    return putPacked(owner, path, timestamp, new String(words, 0, count), length, true);
  }

  /** Publishes a schema with literal timestamp zero, not the default publication clock. */
  public void putSchema(JSObject owner, JSString path, byte[] value) {
    putPacked(owner, path, 0, pack(value, 0, value.length), value.length, false);
  }

  private String pack(byte[] value, int start, int length) {
    Objects.checkFromIndexSize(start, length, value.length);
    int count = prepare(length);
    int end = start + length;
    int index = 0;
    for (; start < end - 1; start += 2) {
      words[index++] = (char) ((value[start] & 0xff) | ((value[start + 1] & 0xff) << 8));
    }
    if (start < end) words[index] = (char) (value[start] & 0xff);
    return new String(words, 0, count);
  }

  private int prepare(int length) {
    int count = length / 2 + length % 2;
    if (words.length < count) words = new char[Math.max(64, count)];
    return count;
  }

  @JS.Coerce
  @JS(
      """
      let state = owner.rawBytes;
      if (!state || state.bytes.length < length) {
        const bytes = new Uint8Array(length);
        owner.rawBytes = state = {bytes, value: bytes};
      } else if (state.value.length !== length) {
        state.value = state.bytes.subarray(0, length);
      }
      const bytes = state.value;
      for (let offset = 0; offset < length; offset += 2) {
        const word = packed.charCodeAt(offset / 2);
        bytes[offset] = word & 255;
        if (offset + 1 < length) bytes[offset + 1] = word >>> 8;
      }
      return globalThis.wasmbot.nt.put(owner, path,
        defaultTimestamp && timestamp === 0 ? globalThis.wasmbot.nowNanos() / 1000 : timestamp, bytes);
      """)
  private static native boolean putPacked(
      JSObject owner,
      JSString path,
      double timestamp,
      String packed,
      int length,
      boolean defaultTimestamp);
}
