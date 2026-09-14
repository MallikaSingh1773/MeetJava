package com.mallika.meetjava;

import com.mallika.meetjava.config.DatabaseUrlEnvironmentPostProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The conversion from a platform connection string to JDBC properties is the one
 * piece of deployment wiring that fails silently and only in production, so it is
 * tested directly.
 */
class DatabaseUrlEnvironmentPostProcessorTest {

    private Map<String, Object> parse(String url) {
        return DatabaseUrlEnvironmentPostProcessor.parse(url);
    }

    @Test
    @DisplayName("an internal Render URL becomes a plaintext JDBC URL with split credentials")
    void internalUrl() {
        Map<String, Object> p = parse("postgresql://meetjava:s3cret@dpg-abc123-a/meetjava");

        assertEquals("jdbc:postgresql://dpg-abc123-a:5432/meetjava", p.get("spring.datasource.url"));
        assertEquals("meetjava", p.get("spring.datasource.username"));
        assertEquals("s3cret", p.get("spring.datasource.password"));
    }

    @Test
    @DisplayName("a public host gets sslmode=require, which the managed providers demand")
    void externalUrlRequiresTls() {
        Map<String, Object> p = parse("postgres://u:p@oregon-postgres.render.com:5432/meetjava");

        assertEquals("jdbc:postgresql://oregon-postgres.render.com:5432/meetjava?sslmode=require",
                p.get("spring.datasource.url"));
    }

    @Test
    @DisplayName("an explicit query string is preserved instead of being overwritten")
    void queryStringIsKept() {
        Map<String, Object> p = parse("postgresql://u:p@ep-cool.neon.tech/meetjava?sslmode=verify-full");

        assertEquals("jdbc:postgresql://ep-cool.neon.tech:5432/meetjava?sslmode=verify-full",
                p.get("spring.datasource.url"));
    }

    @Test
    @DisplayName("a value that is already a JDBC URL is left alone")
    void jdbcUrlIsIgnored() {
        assertTrue(parse("jdbc:postgresql://localhost:5432/meetjava").isEmpty());
    }
}
