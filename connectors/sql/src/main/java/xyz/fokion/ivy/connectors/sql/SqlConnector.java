package xyz.fokion.ivy.connectors.sql;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;

import xyz.fokion.ivy.spi.Connector;
import xyz.fokion.ivy.spi.ConnectorClass;
import xyz.fokion.ivy.spi.ConnectorException;
import xyz.fokion.ivy.spi.StepContext;
import xyz.fokion.ivy.spi.Struct;

/**
 * Runs SQL commands or a SQL file.
 * <p>
 * Result: {@code queries[]}, one per statement, each with {@code rows[]} of column values:
 * {@code result.queries[0].rows[1].name}. The step has no default assertion.
 */
@ConnectorClass(type = "sql", configurationClass = SqlConfiguration.class)
public final class SqlConnector implements Connector<SqlConfiguration> {

    @Override
    public java.util.Map<String, String> resultFields() {
        return Connector.fields(
                "queries", "one entry per command, with its rows[]: each row maps column names to values");
    }

    @Override
    public Object run(SqlConfiguration config, StepContext context) throws Exception {
        Path workdir = context.workdir();
        String url = Dsn.toJdbc(config.getDriver(), config.getDsn(), workdir);
        context.log(StepContext.Level.DEBUG, "connecting to database " + config.getDriver());
        List<Object> queries = new ArrayList<>();
        try (Connection db = connect(url)) {
            if (!config.getCommands().isEmpty()) {
                for (int i = 0; i < config.getCommands().size(); i++) {
                    context.log(StepContext.Level.DEBUG, "Executing command number " + i);
                    try {
                        queries.add(query(db, config.getCommands().get(i)));
                    } catch (SQLException e) {
                        throw new ConnectorException("failed to exec command number " + i + ": " + e.getMessage(), e);
                    }
                }
            } else if (!config.getFile().isEmpty()) {
                Path file = workdir.resolve(config.getFile());
                context.log(StepContext.Level.DEBUG, "loading SQL file from " + file);
                String sql = Files.readString(file);
                try {
                    queries.add(query(db, sql));
                } catch (SQLException e) {
                    throw new ConnectorException("failed to exec SQL file \"" + file + "\": " + e.getMessage(), e);
                }
            }
        } catch (SQLException e) {
            throw new ConnectorException("failed to connect to database: " + e.getMessage(), e);
        }
        return Struct.of("Result", Map.of("queries", queries));
    }

    /** Finds the driver among those embedded in this bundle, without the global DriverManager. */
    static Connection connect(String url) throws SQLException {
        return connect(url, new Properties());
    }

    static Connection connect(String url, Properties properties) throws SQLException {
        for (Driver driver : ServiceLoader.load(Driver.class, SqlConnector.class.getClassLoader())) {
            if (driver.acceptsURL(url)) {
                Connection c = driver.connect(url, properties);
                if (c != null) {
                    return c;
                }
            }
        }
        throw new SQLException("no JDBC driver for " + url.replaceAll("password=[^&;]*", "password=***"));
    }

    private static Struct query(Connection db, String sql) throws SQLException {
        List<Object> rows = new ArrayList<>();
        try (Statement st = db.createStatement()) {
            boolean hasResults = st.execute(sql);
            while (!hasResults && st.getUpdateCount() != -1) {
                hasResults = st.getMoreResults();
            }
            if (hasResults) {
                try (ResultSet rs = st.getResultSet()) {
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int c = 1; c <= md.getColumnCount(); c++) {
                            row.put(md.getColumnLabel(c), value(rs.getObject(c)));
                        }
                        rows.add(row);
                    }
                }
            }
        }
        return Struct.of("QueryResult", Map.of("rows", rows));
    }

    /** Converts JDBC values to the types results use: Long, Double, Boolean, String. */
    static Object value(Object v) throws SQLException {
        return switch (v) {
            case null -> null;
            case Integer i -> i.longValue();
            case Short s -> s.longValue();
            case Byte b -> b.longValue();
            case Long l -> l;
            case BigInteger bi -> bi.bitLength() < 64 ? (Object) bi.longValue() : bi.toString();
            case BigDecimal bd -> {
                try {
                    yield bd.stripTrailingZeros().scale() <= 0 ? (Object) bd.longValueExact() : bd.doubleValue();
                } catch (ArithmeticException tooLarge) {
                    yield bd.doubleValue();
                }
            }
            case Float f -> f.doubleValue();
            case Double d -> d;
            case Boolean b -> b;
            case String s -> s;
            case byte[] bytes -> new String(bytes, StandardCharsets.UTF_8);
            case Clob c -> c.getSubString(1, (int) c.length());
            case Blob b -> new String(b.getBytes(1, (int) b.length()), StandardCharsets.UTF_8);
            case java.sql.Timestamp t -> t.toLocalDateTime().toString();
            case java.sql.Date d -> d.toLocalDate().toString();
            case java.sql.Time t -> t.toLocalTime().toString();
            case TemporalAccessor t -> t.toString();
            default -> v.toString();
        };
    }
}
