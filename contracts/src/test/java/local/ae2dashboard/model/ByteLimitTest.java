package local.ae2dashboard.model;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import local.ae2dashboard.io.BoundedOutputStream;
import org.junit.jupiter.api.Test;

class ByteLimitTest {
  @Test
  void limitCountsUtf8BytesAndDoesNotPartiallyWriteAnOversizeBuffer() throws Exception {
    var target = new ByteArrayOutputStream();
    var limited = new BoundedOutputStream(target, 6);
    limited.write("铁".getBytes(StandardCharsets.UTF_8));
    assertThrows(IOException.class, () -> limited.write("锭锭".getBytes(StandardCharsets.UTF_8)));
    assertEquals("铁", target.toString(StandardCharsets.UTF_8));
    limited.write("锭".getBytes(StandardCharsets.UTF_8));
    assertThrows(IOException.class, () -> limited.write(1));
    assertEquals("铁锭", target.toString(StandardCharsets.UTF_8));
  }
}
