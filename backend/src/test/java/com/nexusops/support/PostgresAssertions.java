package com.nexusops.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.dao.DataAccessException;

/** Assertions about what PostgreSQL itself refuses. */
public final class PostgresAssertions {

    private PostgresAssertions() {}

    /** The call fails with SQLState 42501 (row-level security or a missing grant), not any other database error. */
    public static void assertDeniedByPostgres(ThrowingCallable call, String what) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).as(what).isInstanceOf(DataAccessException.class);
        Throwable cause = thrown;
        while (cause != null && !(cause instanceof SQLException)) {
            cause = cause.getCause();
        }
        assertThat(cause).as(what + " (SQL cause)").isNotNull();
        assertThat(((SQLException) cause).getSQLState()).as(what).isEqualTo("42501");
    }
}
