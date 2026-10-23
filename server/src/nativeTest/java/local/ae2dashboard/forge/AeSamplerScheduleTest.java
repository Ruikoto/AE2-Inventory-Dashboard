package local.ae2dashboard.forge;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AeSamplerScheduleTest {
  @Test
  void onTimeAndLateTicksKeepOriginalPhase() {
    assertEquals(20_000, AeSampler.nextSampleAt(10_000, 10_000, 10_000));
    assertEquals(20_000, AeSampler.nextSampleAt(10_000, 10_050, 10_000));
    assertEquals(40_000, AeSampler.nextSampleAt(10_000, 39_999, 10_000));
    assertEquals(50_000, AeSampler.nextSampleAt(10_000, 40_000, 10_000));
  }

  @Test
  void longTickDoesNotPermanentlySynchronizeNetworks() {
    long now = 62_500;
    long[] due = {10_000, 11_000, 12_000, 13_000, 14_000};
    long[] next = {70_000, 71_000, 72_000, 63_000, 64_000};
    for (int i = 0; i < due.length; i++) {
      long scheduled = AeSampler.nextSampleAt(due[i], now, 10_000);
      assertEquals(next[i], scheduled);
      assertTrue(scheduled > now);
      assertTrue(scheduled <= now + 10_000);
      assertEquals(due[i] % 10_000, scheduled % 10_000);
    }
  }
}
