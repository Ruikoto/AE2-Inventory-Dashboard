package local.ae2dashboard.io;

import java.io.*;
import java.util.Objects;

public final class BoundedOutputStream extends FilterOutputStream {
  private final long limit;
  private long count;

  public BoundedOutputStream(OutputStream output, long limit) {
    super(output);
    if (limit < 0) throw new IllegalArgumentException("Negative limit");
    this.limit = limit;
  }

  @Override
  public void write(int value) throws IOException {
    if (count == limit) throw new IOException("Output exceeds byte limit");
    out.write(value);
    count++;
  }

  @Override
  public void write(byte[] bytes, int offset, int length) throws IOException {
    Objects.checkFromIndexSize(offset, length, bytes.length);
    if (length > limit - count) throw new IOException("Output exceeds byte limit");
    out.write(bytes, offset, length);
    count += length;
  }
}
