package com.amazonaws.secretsmanager.util;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * SQL Exception Utilities
 */
public class SQLExceptionUtils {

    /**
     * Checks the thrown exception and all parent exceptions and returns true if
     *   a SQLException with a matching error code is found.
     *
     * @param t The SQLException to check
     * @param errorCode The error code to check for.
     * @return True if the exception or any parent exception is a SQL Exception
     *      and getErrorCode matches the error code.  Otherwise, false.
     */
    public static boolean unwrapAndCheckForCode(Throwable t, int errorCode) {
        final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (t != null && seen.add(t)) {
            if (t instanceof SQLException sqle && sqle.getErrorCode() == errorCode) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }


    /**
     * Hide constructor for static class
     */
    private SQLExceptionUtils() { }
}
