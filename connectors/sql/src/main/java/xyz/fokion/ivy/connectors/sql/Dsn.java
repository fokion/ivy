package xyz.fokion.ivy.connectors.sql;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import xyz.fokion.ivy.spi.ConnectorException;

/**
 * Translates the DSNs venom suites use (Go drivers) into JDBC URLs. A DSN starting with
 * {@code jdbc:} is used as is.
 */
final class Dsn {

    private static final Pattern MYSQL = Pattern.compile(
            "^(?:(?<user>[^:@]*)(?::(?<password>[^@]*))?@)?(?:(?<net>\\w+)\\((?<address>[^)]*)\\))?/(?<db>[^?]*)(?:\\?(?<params>.*))?$");

    private Dsn() {
    }

    static String toJdbc(String driver, String dsn, Path workdir) {
        if (dsn.startsWith("jdbc:")) {
            return dsn;
        }
        return switch (driver) {
            case "sqlite", "sqlite3" -> sqlite(dsn, workdir);
            case "postgres", "postgresql", "pgx" -> postgres(dsn);
            case "mysql" -> mysql(dsn);
            default -> throw new ConnectorException("unsupported driver \"" + driver
                    + "\": use a jdbc: URL with a bundle embedding its JDBC driver");
        };
    }

    private static String sqlite(String dsn, Path workdir) {
        String path = dsn.startsWith("file:") ? dsn.substring(5) : dsn;
        String query = "";
        int q = path.indexOf('?');
        if (q >= 0) {
            query = path.substring(q);
            path = path.substring(0, q);
        }
        if (path.equals(":memory:") || path.isEmpty()) {
            return "jdbc:sqlite::memory:";
        }
        Path p = Path.of(path);
        if (!p.isAbsolute() && workdir != null && !Files.exists(p) && Files.exists(workdir.resolve(p))) {
            p = workdir.resolve(p);
        }
        return "jdbc:sqlite:" + p + query;
    }

    /** {@code postgres://user:pass@host:port/db?params} or {@code key=value} pairs. */
    private static String postgres(String dsn) {
        Map<String, String> params = new LinkedHashMap<>();
        String host = "localhost";
        String port = "5432";
        String db = "";
        if (dsn.startsWith("postgres://") || dsn.startsWith("postgresql://")) {
            URI uri = URI.create(dsn);
            if (uri.getHost() != null) {
                host = uri.getHost();
            }
            if (uri.getPort() != -1) {
                port = Integer.toString(uri.getPort());
            }
            db = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
            if (uri.getRawUserInfo() != null) {
                String[] up = uri.getRawUserInfo().split(":", 2);
                params.put("user", decode(up[0]));
                if (up.length > 1) {
                    params.put("password", decode(up[1]));
                }
            }
            if (uri.getRawQuery() != null) {
                for (String kv : uri.getRawQuery().split("&")) {
                    String[] p = kv.split("=", 2);
                    params.put(decode(p[0]), p.length > 1 ? decode(p[1]) : "");
                }
            }
        } else {
            Matcher m = Pattern.compile("(\\w+)=('(?:[^'\\\\]|\\\\.)*'|\\S*)").matcher(dsn);
            while (m.find()) {
                String v = m.group(2);
                if (v.startsWith("'") && v.endsWith("'") && v.length() >= 2) {
                    v = v.substring(1, v.length() - 1).replace("\\'", "'").replace("\\\\", "\\");
                }
                switch (m.group(1)) {
                    case "host" -> host = v;
                    case "port" -> port = v;
                    case "dbname" -> db = v;
                    default -> params.put(m.group(1), v);
                }
            }
        }
        return "jdbc:postgresql://" + host + ":" + port + "/" + db + query(params);
    }

    /** {@code user:password@tcp(host:port)/db?params}. */
    private static String mysql(String dsn) {
        Matcher m = MYSQL.matcher(dsn);
        if (!m.matches()) {
            throw new ConnectorException("invalid mysql DSN");
        }
        Map<String, String> params = new LinkedHashMap<>();
        if (m.group("user") != null && !m.group("user").isEmpty()) {
            params.put("user", m.group("user"));
        }
        if (m.group("password") != null) {
            params.put("password", m.group("password"));
        }
        if (m.group("params") != null && !m.group("params").isEmpty()) {
            for (String kv : m.group("params").split("&")) {
                String[] p = kv.split("=", 2);
                // Go-only options have no JDBC equivalent
                if (!p[0].equals("parseTime") && !p[0].equals("multiStatements")) {
                    params.put(decode(p[0]), p.length > 1 ? decode(p[1]) : "");
                } else if (p[0].equals("multiStatements")) {
                    params.put("allowMultiQueries", p.length > 1 ? p[1] : "true");
                }
            }
        }
        String address = m.group("address") == null || m.group("address").isEmpty() ? "localhost:3306" : m.group("address");
        return "jdbc:mysql://" + address + "/" + m.group("db") + query(params);
    }

    private static String query(Map<String, String> params) {
        if (params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("?");
        params.forEach((k, v) -> {
            if (sb.length() > 1) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
