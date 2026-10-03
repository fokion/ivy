package xyz.fokion.ivy.connectors.sql;

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
 * Translates Go database driver DSNs into JDBC URLs. A DSN starting with
 * {@code jdbc:} is used as is.
 */
final class Dsn {

    /** {@code [net(address)]/db[?params]}, after the credentials are cut at the last '@'. */
    private static final Pattern MYSQL = Pattern.compile(
            "^(?:(?<net>\\w+)\\((?<address>[^)]*)\\))?/(?<db>[^?]*)(?:\\?(?<params>.*))?$");

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
        // relative paths are relative to the suite, unless the file exists in the working directory
        Path p = Path.of(path);
        if (!p.isAbsolute() && workdir != null && !Files.exists(p)) {
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
            // parsed by hand: passwords may contain '@' or other characters URI rejects
            String rest = dsn.substring(dsn.indexOf("://") + 3);
            int slash = rest.indexOf('/');
            int question = rest.indexOf('?');
            int end = slash >= 0 ? slash : question >= 0 ? question : rest.length();
            String authority = rest.substring(0, end);
            String tail = rest.substring(end);
            int at = authority.lastIndexOf('@');
            if (at >= 0) {
                String[] up = authority.substring(0, at).split(":", 2);
                params.put("user", decode(up[0]));
                if (up.length > 1) {
                    params.put("password", decode(up[1]));
                }
                authority = authority.substring(at + 1);
            }
            if (authority.isEmpty()) {
                throw new ConnectorException("invalid postgres DSN: missing host");
            }
            int colon = authority.lastIndexOf(':');
            if (colon > 0 && !authority.endsWith("]")) {
                host = authority.substring(0, colon);
                port = authority.substring(colon + 1);
            } else {
                host = authority;
            }
            int q = tail.indexOf('?');
            String path = q >= 0 ? tail.substring(0, q) : tail;
            db = path.replaceFirst("^/", "");
            if (q >= 0 && q + 1 < tail.length()) {
                for (String kv : tail.substring(q + 1).split("&")) {
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
        Map<String, String> params = new LinkedHashMap<>();
        // as Go's driver: the credentials end at the last '@' before the database path
        int slash = dsn.lastIndexOf('/');
        int at = slash < 0 ? -1 : dsn.lastIndexOf('@', slash);
        if (at >= 0) {
            String[] up = dsn.substring(0, at).split(":", 2);
            if (!up[0].isEmpty()) {
                params.put("user", up[0]);
            }
            if (up.length > 1) {
                params.put("password", up[1]);
            }
            dsn = dsn.substring(at + 1);
        }
        Matcher m = MYSQL.matcher(dsn);
        if (!m.matches()) {
            throw new ConnectorException("invalid mysql DSN");
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
        if ("unix".equals(m.group("net"))) {
            // Connector/J reaches a socket through junixsocket, which is not bundled
            throw new ConnectorException("mysql unix sockets are not supported, use tcp(host:port)");
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
