package local.ae2dashboard.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.concurrent.*;
import org.sqlite.SQLiteConnection;
import org.sqlite.core.DB;

/** Connection-bound cancellation avoids the old driver's global progress callback. */
final class SqliteWatchdog implements AutoCloseable {
  private static final ThreadLocal<Operation> CURRENT = new ThreadLocal<>();
  private final ScheduledThreadPoolExecutor timer =
      new ScheduledThreadPoolExecutor(
          1,
          r -> {
            var thread = new Thread(r, "ae2-dashboard-sql-watchdog");
            thread.setDaemon(true);
            return thread;
          });

  @FunctionalInterface
  interface Check {
    void run() throws SQLException;
  }

  SqliteWatchdog() {
    timer.setRemoveOnCancelPolicy(true);
    timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    timer.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
  }

  Operation watch(Connection connection, long timeoutMillis, Check capacity) throws SQLException {
    var operation =
        new Operation(((SQLiteConnection) connection).getDatabase(), timeoutMillis, capacity);
    operation.task = timer.scheduleAtFixedRate(operation::tick, 1, 1, TimeUnit.MILLISECONDS);
    CURRENT.set(operation);
    return operation;
  }

  static void checkCurrent() throws SQLException {
    if (Thread.currentThread().isInterrupted())
      throw new SQLException("SQLite operation cancelled", "57014");
    var operation = CURRENT.get();
    if (operation != null) operation.check();
  }

  final class Operation implements AutoCloseable {
    private final DB database;
    private final Thread owner = Thread.currentThread();
    private final Operation previous = CURRENT.get();
    private final long deadline;
    private final Check capacity;
    private ScheduledFuture<?> task;
    private SQLException failure;
    private boolean closed;

    Operation(DB database, long timeoutMillis, Check capacity) {
      this.database = database;
      deadline =
          timeoutMillis == 0
              ? Long.MAX_VALUE
              : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
      this.capacity = capacity;
    }

    private synchronized void tick() {
      if (closed || failure != null) return;
      try {
        if (owner.isInterrupted()) throw new SQLException("SQLite operation cancelled", "57014");
        if (System.nanoTime() >= deadline)
          throw new SQLTimeoutException("History query exceeded its deadline");
        if (capacity != null) capacity.run();
      } catch (SQLException e) {
        failure = e;
        try {
          database.interrupt();
        } catch (SQLException interrupt) {
          e.addSuppressed(interrupt);
        }
      }
    }

    synchronized void check() throws SQLException {
      if (failure != null) throw failure;
      if (System.nanoTime() >= deadline)
        throw new SQLTimeoutException("History query exceeded its deadline");
    }

    @Override
    public synchronized void close() {
      if (closed) return;
      closed = true;
      task.cancel(false);
      if (CURRENT.get() == this) {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
      }
    }
  }

  @Override
  public void close() {
    timer.shutdownNow();
  }
}
