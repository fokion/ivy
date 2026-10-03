package xyz.fokion.ivy.connectors.sql;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import xyz.fokion.ivy.spi.Connector;
import xyz.fokion.ivy.spi.ConnectorClass;
import xyz.fokion.ivy.spi.ConnectorException;
import xyz.fokion.ivy.spi.StepContext;
import xyz.fokion.ivy.spi.Struct;
import xyz.fokion.ivy.spi.util.Json;

/**
 * Prepares a database: runs schema files or applies migrations, then loads fixtures.
 * <p>
 * A fixture file is named after its table and holds a list of rows (column: value). Tables are
 * emptied then filled in one transaction, foreign key checks off. A value {@code RAW=<sql>} is
 * inserted as an SQL expression. Result: {@code tables[]} loaded, {@code rows} inserted and
 * {@code migrations[]} applied.
 */
@ConnectorClass(type = "dbfixtures", configurationClass = DbFixturesConfiguration.class)
public final class DbFixturesConnector implements Connector<DbFixturesConfiguration> {

    @Override
    public java.util.Map<String, String> resultFields() {
        return Connector.fields(
                "tables", "the tables loaded",
                "rows", "the number of rows inserted",
                "migrations", "the migrations applied");
    }

    private enum Dialect {
        POSTGRES, MYSQL, SQLITE;

        static Dialect of(String database) {
            return switch (database) {
                case "postgres", "postgresql", "pgx" -> POSTGRES;
                case "mysql" -> MYSQL;
                case "sqlite", "sqlite3" -> SQLITE;
                default -> throw new ConnectorException("unsupported database \"" + database + "\"");
            };
        }

        String quote(String identifier) {
            return this == MYSQL ? "`" + identifier.replace("`", "``") + "`" : "\"" + identifier.replace("\"", "\"\"") + "\"";
        }
    }

    private static Struct result(List<String> tables, long rows, List<String> migrations) {
        return Struct.builder("Result").put("tables", tables).put("rows", rows).put("migrations", migrations).build();
    }

    @Override
    public Object run(DbFixturesConfiguration c, StepContext context) throws Exception {
        Dialect dialect = Dialect.of(c.database());
        Path workdir = context.workdir();
        String url = Dsn.toJdbc(c.database(), c.dsn(), workdir);
        Properties properties = new Properties();
        if (dialect == Dialect.POSTGRES) {
            // let PostgreSQL cast fixture strings to the column types (dates, uuid, json...)
            properties.put("stringtype", "unspecified");
        }
        List<String> migrations = new ArrayList<>();
        try (Connection db = SqlConnector.connect(url, properties)) {
            if (!c.schemas().isEmpty()) {
                for (String schema : c.schemas()) {
                    context.log(StepContext.Level.DEBUG, "loading schema from file " + schema);
                    run(db, Files.readString(workdir.resolve(schema)), "schema " + schema);
                }
            } else if (!c.migrations().isEmpty()) {
                migrations = migrate(db, dialect, workdir.resolve(c.migrations()), c.migrationsTable());
            }
            Map<String, List<Map<String, Object>>> fixtures = fixtures(c, workdir);
            long rows = load(db, dialect, fixtures, !c.skipResetSequences());
            return result(new ArrayList<>(fixtures.keySet()), rows, migrations);
        } catch (SQLException e) {
            throw new ConnectorException(e.getMessage(), e);
        }
    }

    private static void run(Connection db, String script, String what) throws SQLException {
        try (Statement st = db.createStatement()) {
            for (String sql : SqlScript.statements(script)) {
                try {
                    st.execute(sql);
                } catch (SQLException e) {
                    throw new SQLException("failed to exec " + what + ": " + e.getMessage(), e);
                }
            }
        }
    }

    private static List<String> migrate(Connection db, Dialect dialect, Path folder, String table) throws Exception {
        String t = dialect.quote(table);
        try (Statement st = db.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + t + " (id VARCHAR(255) PRIMARY KEY, applied_at TIMESTAMP)");
        }
        List<String> applied = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".sql")).sorted().toList()) {
                String id = f.getFileName().toString();
                try (PreparedStatement q = db.prepareStatement("SELECT 1 FROM " + t + " WHERE id = ?")) {
                    q.setString(1, id);
                    try (ResultSet rs = q.executeQuery()) {
                        if (rs.next()) {
                            continue;
                        }
                    }
                }
                run(db, SqlScript.up(Files.readString(f)), "migration " + id);
                try (PreparedStatement ins = db.prepareStatement("INSERT INTO " + t + " (id, applied_at) VALUES (?, CURRENT_TIMESTAMP)")) {
                    ins.setString(1, id);
                    ins.executeUpdate();
                }
                applied.add(id);
            }
        }
        return applied;
    }

    /** Fixtures by table, from the folder, else from the files. */
    private static Map<String, List<Map<String, Object>>> fixtures(DbFixturesConfiguration c, Path workdir) throws Exception {
        List<Path> files = new ArrayList<>();
        if (!c.folder().isEmpty()) {
            try (Stream<Path> list = Files.list(workdir.resolve(c.folder()))) {
                list.filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml")).sorted().forEach(files::add);
            }
        } else {
            c.files().forEach(f -> files.add(workdir.resolve(f)));
        }
        Load yaml = new Load(LoadSettings.builder().build());
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (Path f : files) {
            String name = f.getFileName().toString();
            String table = name.substring(0, name.lastIndexOf('.'));
            Object loaded = yaml.loadFromString(Files.readString(f));
            List<Map<String, Object>> rows = new ArrayList<>();
            // a list of rows, or named rows (a map of rows)
            Iterable<?> items = loaded instanceof List<?> l ? l : loaded instanceof Map<?, ?> m ? m.values() : List.of();
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> row)) {
                    throw new ConnectorException("fixture " + f + ": a row must be a map of columns");
                }
                Map<String, Object> r = new LinkedHashMap<>();
                row.forEach((k, v) -> r.put(String.valueOf(k), v));
                rows.add(r);
            }
            out.put(table, rows);
        }
        return out;
    }

    private static long load(Connection db, Dialect dialect, Map<String, List<Map<String, Object>>> fixtures,
            boolean resetSequences) throws SQLException {
        if (fixtures.isEmpty()) {
            return 0;
        }
        boolean autoCommit = db.getAutoCommit();
        db.setAutoCommit(false);
        long rows = 0;
        try (Statement st = db.createStatement()) {
            switch (dialect) {
                case POSTGRES -> {
                    // needs a superuser; without it, fixtures must be in foreign key order
                    java.sql.Savepoint sp = db.setSavepoint();
                    try {
                        st.execute("SET LOCAL session_replication_role = replica");
                    } catch (SQLException e) {
                        db.rollback(sp);
                    }
                }
                case MYSQL -> st.execute("SET FOREIGN_KEY_CHECKS = 0");
                case SQLITE -> st.execute("PRAGMA defer_foreign_keys = ON");
            }
            for (String table : fixtures.keySet()) {
                st.execute("DELETE FROM " + dialect.quote(table));
            }
            for (Map.Entry<String, List<Map<String, Object>>> e : fixtures.entrySet()) {
                for (Map<String, Object> row : e.getValue()) {
                    insert(db, dialect, e.getKey(), row);
                    rows++;
                }
            }
            if (dialect == Dialect.MYSQL) {
                st.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            if (dialect == Dialect.POSTGRES && resetSequences) {
                resetSequences(db, fixtures.keySet());
            }
            db.commit();
        } catch (SQLException | RuntimeException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(autoCommit);
        }
        return rows;
    }

    private static void insert(Connection db, Dialect dialect, String table, Map<String, Object> row) throws SQLException {
        List<String> columns = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (Map.Entry<String, Object> col : row.entrySet()) {
            columns.add(dialect.quote(col.getKey()));
            Object v = col.getValue();
            if (v instanceof String s && s.startsWith("RAW=")) {
                placeholders.add(s.substring(4));
            } else {
                placeholders.add("?");
                values.add(v instanceof Map || v instanceof List ? Json.write(v, Json.COMPACT) : v);
            }
        }
        String sql = "INSERT INTO " + dialect.quote(table) + " (" + String.join(", ", columns) + ") VALUES ("
                + String.join(", ", placeholders) + ")";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            for (int i = 0; i < values.size(); i++) {
                ps.setObject(i + 1, values.get(i));
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new SQLException("failed to load fixture of table " + table + ": " + e.getMessage(), e);
        }
    }

    /** Moves the sequences of serial columns past the loaded rows. */
    private static void resetSequences(Connection db, Iterable<String> tables) throws SQLException {
        for (String table : tables) {
            List<String> columns = new ArrayList<>();
            try (PreparedStatement q = db.prepareStatement("SELECT column_name FROM information_schema.columns "
                    + "WHERE table_name = ? AND table_schema = current_schema() AND column_default LIKE 'nextval(%'")) {
                q.setString(1, table);
                try (ResultSet rs = q.executeQuery()) {
                    while (rs.next()) {
                        columns.add(rs.getString(1));
                    }
                }
            }
            for (String column : columns) {
                String t = Dialect.POSTGRES.quote(table);
                String col = Dialect.POSTGRES.quote(column);
                try (PreparedStatement ps = db.prepareStatement("SELECT setval(pg_get_serial_sequence(?, ?), "
                        + "COALESCE((SELECT MAX(" + col + ") FROM " + t + "), 0) + 1, false)")) {
                    ps.setString(1, t);
                    ps.setString(2, column);
                    ps.execute();
                }
            }
        }
    }
}
