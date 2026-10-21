package local.ae2dashboard.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class SqliteWatchdogTest {
  private static final String EXPENSIVE =
      "WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<1000000000) SELECT"
          + " sum(x) FROM n";

  @Test
  void readDeadlineDoesNotCancelAnotherConnectionOrLeaveALateTask() throws Exception {
    try (var watchdog = new SqliteWatchdog();
        var writer = DriverManager.getConnection("jdbc:sqlite::memory:");
        var reader = DriverManager.getConnection("jdbc:sqlite::memory:")) {
      try (var operation = watchdog.watch(reader, 50, null);
          var query = reader.createStatement()) {
        long start = System.nanoTime();
        assertThrows(SQLException.class, () -> query.executeQuery(EXPENSIVE));
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
        assertThrows(SQLTimeoutException.class, operation::check);
        try (var write = writer.createStatement()) {
          write.execute("CREATE TABLE unaffected(q TEXT)");
        }
      }
      Thread.sleep(80);
      try (var operation = watchdog.watch(reader, 1000, null);
          var query = reader.createStatement();
          var result = query.executeQuery("SELECT 42")) {
        assertTrue(result.next());
        assertEquals(42, result.getInt(1));
      }
    }
  }

  @Test
  void closedScopeCannotCancelASubsequentOperationOnTheSameConnection() throws Exception {
    try (var watchdog = new SqliteWatchdog();
        var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
      try (var operation = watchdog.watch(connection, 20, null)) {
        assertDoesNotThrow(operation::check);
      }
      try (var operation = watchdog.watch(connection, 3000, null);
          var statement = connection.createStatement();
          var result =
              statement.executeQuery(
                  "WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<1000000)"
                      + " SELECT sum(x) FROM n")) {
        assertTrue(result.next());
        assertEquals(500000500000L, result.getLong(1));
      }
    }
  }

  @Test
  void threadCancellationStopsNativeQueryAndLeavesOtherConnectionsUsable() throws Exception {
    var pool = Executors.newSingleThreadExecutor();
    var entered = new CountDownLatch(1);
    var finished = new CountDownLatch(1);
    try (var watchdog = new SqliteWatchdog();
        var writer = DriverManager.getConnection("jdbc:sqlite::memory:")) {
      Future<?> task =
          pool.submit(
              () -> {
                try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
                    var operation = watchdog.watch(connection, 0, null);
                    var statement = connection.createStatement()) {
                  entered.countDown();
                  assertThrows(SQLException.class, () -> statement.executeQuery(EXPENSIVE));
                } catch (SQLException e) {
                  throw new RuntimeException(e);
                } finally {
                  finished.countDown();
                }
              });
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      task.cancel(true);
      assertTrue(finished.await(2, TimeUnit.SECONDS));
      try (var statement = writer.createStatement();
          var result = statement.executeQuery("SELECT 7")) {
        assertTrue(result.next());
        assertEquals(7, result.getInt(1));
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void deadlineAlsoBoundsJavaReconstructionAfterSqlFinishes() throws Exception {
    try (var watchdog = new SqliteWatchdog();
        var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        var operation = watchdog.watch(connection, 20, null)) {
      Thread.sleep(50);
      assertThrows(SQLTimeoutException.class, SqliteWatchdog::checkCurrent);
    }
    assertDoesNotThrow(SqliteWatchdog::checkCurrent);
  }
}
