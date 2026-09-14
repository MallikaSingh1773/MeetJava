package com.mallika.meetjava.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Translates a platform supplied {@code DATABASE_URL} into the three properties
 * Spring actually needs.
 *
 * <p>Render, Railway, Fly, Heroku and Neon all hand the application a single
 * connection string in libpq form:
 *
 * <pre>postgresql://user:password@host:5432/database</pre>
 *
 * <p>The JDBC driver cannot read that. It wants {@code jdbc:postgresql://host:5432/database}
 * with the credentials supplied separately. Doing the conversion here, before the
 * context starts, keeps every deployment target on one environment variable and
 * leaves {@code application.properties} free of platform specific wiring.
 *
 * <p>An explicit {@code DB_URL} always wins, so local development and
 * docker-compose are untouched.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String SOURCE_NAME = "meetjava-database-url";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = environment.getProperty("DATABASE_URL");
        if (raw == null || raw.isBlank()) {
            return;
        }
        if (environment.getProperty("DB_URL") != null) {
            return;
        }

        Map<String, Object> resolved = parse(raw.trim());
        if (resolved.isEmpty()) {
            return;
        }
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, resolved));
    }

    /**
     * Returns an empty map for anything that is not a libpq style PostgreSQL
     * URL, including a JDBC URL that needs no conversion.
     */
    public static Map<String, Object> parse(String databaseUrl) {
        if (!databaseUrl.startsWith("postgres://") && !databaseUrl.startsWith("postgresql://")) {
            return Map.of();
        }

        URI uri = URI.create(databaseUrl);
        String host = uri.getHost();
        if (host == null) {
            return Map.of();
        }
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String database = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");

        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://")
                .append(host).append(':').append(port).append('/').append(database);

        if (uri.getQuery() != null && !uri.getQuery().isBlank()) {
            jdbc.append('?').append(uri.getQuery());
        } else if (host.contains(".")) {
            // An internal hostname on Render or Fly is a single label and speaks
            // plaintext inside the private network. A dotted public hostname is
            // reached over the internet, where the managed providers all require TLS.
            jdbc.append("?sslmode=require");
        }

        Map<String, Object> props = new HashMap<>();
        props.put("spring.datasource.url", jdbc.toString());

        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            int split = userInfo.indexOf(':');
            props.put("spring.datasource.username", split < 0 ? userInfo : userInfo.substring(0, split));
            props.put("spring.datasource.password", split < 0 ? "" : userInfo.substring(split + 1));
        }
        return props;
    }
}
