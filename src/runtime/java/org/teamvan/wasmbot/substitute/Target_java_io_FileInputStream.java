package org.teamvan.wasmbot.substitute;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Completes Web Image's {@code java.io.FileInputStream} support.
 *
 * <p>Web Image already serves file contents from its in-memory file system, but leaves {@code
 * length0} and {@code position0} unimplemented, so the JDK 25 {@code readAllBytes} and {@code
 * readNBytes} fast paths fail with {@code UnsupportedOperationException}. Both methods read through
 * the ordinary stream instead, which keeps every bundled deploy file readable through the standard
 * API.
 */
@TargetClass(className = "java.io.FileInputStream")
final class Target_java_io_FileInputStream {
  @Alias
  public native int read(byte[] b, int off, int len) throws IOException;

  @Substitute
  public byte[] readAllBytes() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int count;
    while ((count = read(buffer, 0, buffer.length)) >= 0) {
      bytes.write(buffer, 0, count);
    }
    return bytes.toByteArray();
  }

  @Substitute
  public byte[] readNBytes(int len) throws IOException {
    if (len < 0) {
      throw new IllegalArgumentException("len < 0");
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(len, 8192));
    byte[] buffer = new byte[Math.min(len, 8192)];
    int remaining = len;
    while (remaining > 0) {
      int count = read(buffer, 0, Math.min(buffer.length, remaining));
      if (count < 0) {
        break;
      }
      bytes.write(buffer, 0, count);
      remaining -= count;
    }
    return bytes.toByteArray();
  }
}
