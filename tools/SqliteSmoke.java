import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.sqlite.ProgressHandler;

/** Runs against the exact embedded driver on each supported OS. */
public final class SqliteSmoke {
  public static void main(String[] args) throws Exception {
    Class.forName("org.sqlite.JDBC");
    Path directory = Files.createTempDirectory(Path.of(args[0]), "jdbc-smoke-");
    String url = "jdbc:sqlite:" + directory.resolve("history.sqlite");
    try (var writer = DriverManager.getConnection(url);
        var statement = writer.createStatement()) {
      statement.execute("PRAGMA journal_mode=WAL");
      statement.execute("PRAGMA synchronous=NORMAL");
      statement.execute("CREATE TABLE quantities(q TEXT)");
      statement.execute("INSERT INTO quantities VALUES('18446744073709551616')");
      try (var reader = DriverManager.getConnection(url);
          var query = reader.createStatement();
          var result = query.executeQuery("SELECT q FROM quantities")) {
        if (!result.next() || !result.getString(1).equals("18446744073709551616")) {
          throw new AssertionError("Exact quantity round trip failed");
        }
      }
      ProgressHandler.setHandler(
          writer,
          1,
          new ProgressHandler() {
            @Override
            protected int progress() {
              return 1;
            }
          });
      boolean cancelled = false;
      try {
        statement.executeQuery(
            "WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<100000) SELECT"
                + " sum(x) FROM n");
      } catch (SQLException expected) {
        cancelled = expected.getMessage().contains("INTERRUPT");
      } finally {
        ProgressHandler.clearHandler(writer);
      }
      if (!cancelled) throw new AssertionError("Query cancellation failed");
      try (var result = statement.executeQuery("PRAGMA quick_check")) {
        if (!result.next() || !result.getString(1).equals("ok"))
          throw new AssertionError("Database integrity failed");
      }
      try (var result = statement.executeQuery("SELECT sqlite_version()")) {
        result.next();
        System.out.println(
            "sqlite="
                + result.getString(1)
                + "; os="
                + System.getProperty("os.name")
                + "; java="
                + System.getProperty("java.version")
                + "; WAL/integer/cancellation/integrity=passed");
      }
    }
  }
}
