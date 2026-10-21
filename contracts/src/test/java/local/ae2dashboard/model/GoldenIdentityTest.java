package local.ae2dashboard.model;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import local.ae2dashboard.identity.StableKey;
import org.junit.jupiter.api.Test;

class GoldenIdentityTest {
  @Test
  void agreesWith256ReleasedBinaryVectors() throws Exception {
    try (var input = getClass().getResourceAsStream("/stable-key-vectors.json")) {
      assertNotNull(input);
      String[] expected =
          new Gson().fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), String[].class);
      assertEquals(256, expected.length);
      for (int i = 0; i < 256; i++) {
        final int value = i;
        String actual =
            StableKey.create(
                    sink -> {
                      sink.putInt(value).putLong(12345678901234L).putByte((byte) 1);
                      StableKey.writeText(sink, "中文 identity " + value);
                    })
                .toString();
        assertEquals(expected[i], actual, "vector " + i);
      }
    }
  }
}
