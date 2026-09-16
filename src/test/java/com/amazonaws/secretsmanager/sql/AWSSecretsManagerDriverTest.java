/*
 * Copyright 2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance with
 * the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package com.amazonaws.secretsmanager.sql;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.amazonaws.secretsmanager.caching.SecretCache;
import com.amazonaws.secretsmanager.caching.SecretCacheConfiguration;
import com.amazonaws.secretsmanager.util.TestClass;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClientBuilder;

/**
 * Tests for AWSSecretsManagerDriver. Uses a config file in the resources folder just to make sure it can read from
 * the file.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class AWSSecretsManagerDriverTest extends TestClass {

    private AWSSecretsManagerDummyDriver sut;
    private static final String VALID_USER = "VALID_USER";
    private static final String INVALID_USER = "INVALID_USER";
    private static final String BAD_FORMAT_SECRET = "BAD_FORMAT_SECRET";
    private static final String NEEDS_REFRESH_SECRET = "NEEDS_REFRESH_SECRET";
    private static final String BAD_REFRESH_SECRET = "BAD_REFRESH_SECRET";
    private static final String INVALID_AFTER_REFRESH = "INVALID_AFTER_REFRESH";

    @Mock
    private SecretCache cache;

    boolean hasRefreshed;

    @BeforeEach
    public void setup() throws InterruptedException {
        System.clearProperty("drivers.dummy.realDriverClass");

        // Instantiate mocks
        hasRefreshed = false;
        Mockito.when(cache.getSecretString(Mockito.any(String.class))).thenAnswer(invocation -> {
            Object[] arguments = invocation.getArguments();

            if (arguments != null && arguments.length > 0 && arguments[0] != null) {
                var secretId = (String) arguments[0];
                String returnUser = secretId;
                if (INVALID_USER.equals(secretId)) {
                    return null;
                } else if (BAD_FORMAT_SECRET.equals(secretId)) {
                    return "NotJSONFormat";
                } else if (NEEDS_REFRESH_SECRET.equals(secretId) || BAD_REFRESH_SECRET.equals(secretId)) {
                    returnUser = hasRefreshed ? VALID_USER : DummyDriver.SQL_ERROR_USERNAME;
                } else if (INVALID_AFTER_REFRESH.equals(secretId)) {
                    returnUser = DummyDriver.SQL_ERROR_USERNAME;
                }

                return String.format("{\"username\": \"%s\",\n\"password\": \"%s\",\n\"host\": \"%s\"}",
                        returnUser, secretId, secretId);
            }

            return null;
        });
        Mockito.when(cache.refreshNow(Mockito.any(String.class))).thenAnswer(invocation -> {
            Object[] arguments = invocation.getArguments();

            if (arguments != null && arguments.length > 0 && arguments[0] != null) {
                var secretId = (String) arguments[0];
                if (BAD_REFRESH_SECRET.equals(secretId)) {
                    return false;
                }

                hasRefreshed = true;
                return true;
            }

            return false;
        });

        // Instantiate the driver
        sut = new AWSSecretsManagerDummyDriver(cache);
        DummyDriver.reset();
        try {
            DriverManager.registerDriver(sut);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    public void cleanup() {
        System.clearProperty("drivers.dummy.realDriverClass");
    }

    /*******************************************************************************************************************
     * init Tests
     ******************************************************************************************************************/

    @Test
    public void test_init_constructor_null_builder() {
        assertThrows(Exception.class, () -> new AWSSecretsManagerDummyDriver((SecretsManagerClientBuilder) null));
    }

    @Test
    public void test_init_constructor_null_cacheConfig() {
        assertDoesNotThrow(() -> new AWSSecretsManagerDummyDriver((SecretCacheConfiguration) null));
    }

    @Test
    public void test_init_constructor_null_client() {
        assertDoesNotThrow(() -> new AWSSecretsManagerDummyDriver((SecretsManagerClient) null));
    }

    @Test
    public void test_init_works_realDriverFromConfig() {
        System.setProperty("drivers.dummy.realDriverClass", "some.other.class");
        var sut2 = new AWSSecretsManagerDummyDriver(cache);
        assertEquals(getFieldFrom(sut2, "realDriverClass"), "some.other.class");
    }

    /*******************************************************************************************************************
     * getWrappedDriver Tests
     ******************************************************************************************************************/

    @Test
    public void test_getWrappedDriver_works_goodDriver() {
        assertEquals(DummyDriver.instance, sut.getWrappedDriver());
    }

    @Test
    public void test_getWrappedDriver_throws_badDriver() {
        setFieldFrom(sut, "realDriverClass", "some.bad.class");
        assertThrows(IllegalStateException.class, () -> sut.getWrappedDriver());
    }

    /*******************************************************************************************************************
     * acceptsURL Tests
     ******************************************************************************************************************/

    @Test
    public void test_acceptsURL_throws_nullURL() {
        assertThrows(SQLException.class, () -> sut.acceptsURL(null));
    }

    @Test
    public void test_acceptsURL_returnsFalse_wrongURL() {
        assertDoesNotThrow(() -> assertFalse(sut.acceptsURL("jdbc-secretsmanager:wrongUrl")));
        assertEquals(1, DummyDriver.acceptsURLCallCount);
    }

    @Test
    public void test_acceptsURL_returnsTrue_correctURL() {
        assertDoesNotThrow(() -> assertTrue(sut.acceptsURL("jdbc-secretsmanager:expectedUrl")));
        assertEquals(1, DummyDriver.acceptsURLCallCount);
    }

    @Test
    public void test_acceptsURL_returnsFalse_JdbcUrl() {
        assertDoesNotThrow(() -> assertFalse(sut.acceptsURL("jdbc:expectedUrl")));
        assertEquals(0, DummyDriver.acceptsURLCallCount);
    }

    @Test
    public void test_acceptsURL_returnsTrue_secretId() {
        assertDoesNotThrow(() -> assertTrue(sut.acceptsURL("someSecretId")));
        assertEquals(0, DummyDriver.acceptsURLCallCount);
    }

    /*******************************************************************************************************************
     * connect Tests
     ******************************************************************************************************************/

    @Test
    public void test_connect_throws_nullURL() {
        assertThrows(SQLException.class, () -> sut.connect(null, null));
    }

    @Test
    public void test_connect_works_nullInfo() {
        assertDoesNotThrow(() -> sut.connect("jdbc-secretsmanager:expectedUrl", null));
        assertEquals(1, DummyDriver.connectCallCount);
    }

    @Test
    public void test_connect_works_nullUser() {
        var props = new Properties();
        assertDoesNotThrow(() -> sut.connect("jdbc-secretsmanager:expectedUrl", props));
        assertEquals(1, DummyDriver.connectCallCount);
    }

    @Test
    public void test_connect_works_valid_url() {
        var props = new Properties();
        props.setProperty("user", "user");
        assertDoesNotThrow(() -> sut.connect("jdbc-secretsmanager:expectedUrl", props));
        assertEquals(1, DummyDriver.connectCallCount);
    }

    @Test
    public void test_connect_jdbc_returnsNull() throws SQLException {
        Connection conn = sut.connect("jdbc:expectedUrl", null);
        assertEquals(conn, null);
    }

    @Test
    public void test_connect_works_secretId() {
        var props = new Properties();
        props.setProperty("user", "user");
        assertDoesNotThrow(() -> sut.connect("someSecretId", props));
        assertEquals(1, DummyDriver.connectCallCount);
    }

    @Test
    public void test_connect_works_withSecretRefresh() {
        var props = new Properties();
        props.setProperty("user", NEEDS_REFRESH_SECRET);
        sut.exceptionIsDueToAuth = true;
        assertDoesNotThrow(() -> sut.connect("jdbc-secretsmanager:expectedUrl", props));
        assertEquals(2, DummyDriver.connectCallCount);
    }

    @Test
    public void test_connect_throws_afterRetryMax() {
        var props = new Properties();
        props.setProperty("user", INVALID_AFTER_REFRESH);
        sut.exceptionIsDueToAuth = true;
        assertThrows(SQLException.class, () -> sut.connect("jdbc-secretsmanager:expectedUrl", props));
        assertEquals(AWSSecretsManagerDriver.MAX_RETRY + 1, DummyDriver.connectCallCount);
    }

    @Test
    public void test_connect_throws_withBadRefresh() {
        var props = new Properties();
        props.setProperty("user", BAD_REFRESH_SECRET);
        sut.exceptionIsDueToAuth = true;
        assertThrows(SQLException.class, () -> sut.connect("jdbc-secretsmanager:expectedUrl", props));
        assertEquals(1, DummyDriver.connectCallCount);
    }

    @Test
    public void test_connect_rethrowsSQLException_onFailure() {
        var props = new Properties();
        props.setProperty("user", DummyDriver.SQL_ERROR_USERNAME);
        sut.exceptionIsDueToAuth = false;
        assertThrows(SQLException.class, () -> sut.connect("jdbc-secretsmanager:expectedUrl", props));
        assertEquals(1, DummyDriver.connectCallCount);
    }

    @Test
    public void test_connect_rethrowsRuntimeException_onFailure() {
        var props = new Properties();
        props.setProperty("user", DummyDriver.RUNTIME_ERROR_USERNAME);
        sut.exceptionIsDueToAuth = false;
        assertThrows(RuntimeException.class, () -> sut.connect("jdbc-secretsmanager:expectedUrl", props));
        assertEquals(1, DummyDriver.connectCallCount);
    }

    @Test
    public void test_connect_throws_badSecretId() {
        var props = new Properties();
        props.setProperty("user", "user");
        assertThrows(SQLException.class, () -> sut.connect(INVALID_USER, props));
        assertEquals(0, DummyDriver.connectCallCount);
    }

    @Test
    public void test_connect_wrapsSdkFailure_inSQLException() throws Exception {
        Mockito.when(cache.getSecretString("boom"))
                .thenThrow(software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException
                        .builder().message("no such secret").build());
        var props = new Properties();
        props.setProperty("user", "boom");
        var e = assertThrows(SQLException.class, () -> sut.connect("jdbc-secretsmanager:expectedUrl", props));
        assertTrue(e.getCause() instanceof software.amazon.awssdk.core.exception.SdkException);
        assertEquals(0, DummyDriver.connectCallCount);
    }

    @Test
    public void test_connect_throws_SQLException_whenCredentialsSecretMissing() {
        var props = new Properties();
        props.setProperty("user", INVALID_USER);
        assertThrows(SQLException.class, () -> sut.connect("jdbc-secretsmanager:expectedUrl", props));
        assertEquals(0, DummyDriver.connectCallCount);
    }

    @Test
    public void test_connect_throws_SQLException_whenRealDriverMissing() {
        setFieldFrom(sut, "realDriverClass", "some.bad.class");
        assertThrows(SQLException.class, () -> sut.connect("jdbc-secretsmanager:expectedUrl", new Properties()));
    }

    @Test
    public void test_connect_throws_badlyFormattedSecretId() {
        var props = new Properties();
        props.setProperty("user", "user");
        assertThrows(SQLException.class, () -> sut.connect(BAD_FORMAT_SECRET, props));
        assertEquals(0, DummyDriver.connectCallCount);
    }

    @Test
    public void test_connect_throws_userBadlyFormattedSecretId() {
        var props = new Properties();
        props.setProperty("user", BAD_FORMAT_SECRET);
        assertThrows(SQLException.class, () -> sut.connect("jdbc-secretsmanager:expectedUrl", props));
        assertEquals(0, DummyDriver.connectCallCount);
    }

    /*******************************************************************************************************************
     * getMajorVersion Tests
     ******************************************************************************************************************/

    @Test
    public void test_getMajorVersion_propagatesToRealDriver() {
        assertEquals(DummyDriver.GET_MAJOR_VERSION_RETURN_VALUE, sut.getMajorVersion());
        assertEquals(1, DummyDriver.getMajorVersionCallCount);
    }

    /*******************************************************************************************************************
     * getMinorVersion Tests
     ******************************************************************************************************************/

    @Test
    public void test_getMinorVersion_propagatesToRealDriver() {
        assertEquals(DummyDriver.GET_MINOR_VERSION_RETURN_VALUE, sut.getMinorVersion());
        assertEquals(1, DummyDriver.getMinorVersionCallCount);
    }

    /*******************************************************************************************************************
     * getParentLogger Tests
     ******************************************************************************************************************/

    @Test
    public void test_getParentLogger_propagatesToRealDriver() {
        assertDoesNotThrow(() -> assertEquals(null, sut.getParentLogger()));
        assertEquals(1, DummyDriver.getParentLoggerCallCount);
    }

    /*******************************************************************************************************************
     * getPropertyInfo Tests
     ******************************************************************************************************************/

    @Test
    public void test_getPropertyInfo_propagatesToRealDriver() {
        String param1 = "jdbc-secretsmanager:expectedUrl";
        var param2 = new Properties();
        assertDoesNotThrow(() -> Assertions.assertNull(sut.getPropertyInfo(param1, param2)));
        assertEquals(1, DummyDriver.getPropertyInfoCallCount);
        String param1Expected = "jdbc:expectedUrl";
        assertEquals(param1Expected, DummyDriver.getPropertyInfoParam1);
        assertSame(param2, DummyDriver.getPropertyInfoParam2);
    }

    @Test
    public void test_getPropertyInfo_resolvesSecretIdUrl() {
        assertDoesNotThrow(() -> sut.getPropertyInfo("user", new Properties()));
        assertEquals(1, DummyDriver.getPropertyInfoCallCount);
        assertEquals("mysuperconnectionurl", DummyDriver.getPropertyInfoParam1);
    }

    @Test
    public void test_getPropertyInfo_returnsEmpty_forForeignJdbcUrl() throws SQLException {
        assertEquals(0, sut.getPropertyInfo("jdbc:other://x", new Properties()).length);
        assertEquals(0, DummyDriver.getPropertyInfoCallCount);
    }

    /*******************************************************************************************************************
     * jdbcCompliant Tests
     ******************************************************************************************************************/

    @Test
    public void test_jdbcCompliant_propagatesToRealDriver() {
        assertEquals(true, sut.jdbcCompliant());
        assertEquals(1, DummyDriver.jdbcCompliantCallCount);
    }

    /*******************************************************************************************************************
     * validateSecretFields Tests (URL Injection Prevention)
     ******************************************************************************************************************/

    @Test
    public void test_connect_throws_hostWithFragment() {
        Mockito.when(cache.getSecretString("MALICIOUS_HOST")).thenReturn(
                "{\"username\": \"user\", \"password\": \"pass\", \"host\": \"evil.com:3306/x?allowLoadLocalInfile=true#\", \"port\": \"3306\", \"dbname\": \"test\"}");
        Properties props = new Properties();
        props.setProperty("user", "user");
        assertThrows(SQLException.class, () -> sut.connect("MALICIOUS_HOST", props));
        assertEquals(0, DummyDriver.connectCallCount);
    }

    @Test
    public void test_connect_throws_hostWithQuestionMark() {
        Mockito.when(cache.getSecretString("MALICIOUS_HOST_Q")).thenReturn(
                "{\"username\": \"user\", \"password\": \"pass\", \"host\": \"evil.com?param=val\", \"port\": \"3306\", \"dbname\": \"test\"}");
        Properties props = new Properties();
        props.setProperty("user", "user");
        assertThrows(SQLException.class, () -> sut.connect("MALICIOUS_HOST_Q", props));
        assertEquals(0, DummyDriver.connectCallCount);
    }

    @Test
    public void test_connect_throws_hostWithSlash() {
        Mockito.when(cache.getSecretString("MALICIOUS_HOST_SLASH")).thenReturn(
                "{\"username\": \"user\", \"password\": \"pass\", \"host\": \"evil.com/path\", \"port\": \"3306\", \"dbname\": \"test\"}");
        Properties props = new Properties();
        props.setProperty("user", "user");
        assertThrows(SQLException.class, () -> sut.connect("MALICIOUS_HOST_SLASH", props));
        assertEquals(0, DummyDriver.connectCallCount);
    }

    @Test
    public void test_connect_throws_hostWithAtSign() {
        Mockito.when(cache.getSecretString("MALICIOUS_HOST_AT")).thenReturn(
                "{\"username\": \"user\", \"password\": \"pass\", \"host\": \"user@evil.com\", \"port\": \"3306\", \"dbname\": \"test\"}");
        Properties props = new Properties();
        props.setProperty("user", "user");
        assertThrows(SQLException.class, () -> sut.connect("MALICIOUS_HOST_AT", props));
        assertEquals(0, DummyDriver.connectCallCount);
    }

    @Test
    public void test_connect_throws_nonNumericPort() {
        Mockito.when(cache.getSecretString("BAD_PORT")).thenReturn(
                "{\"username\": \"user\", \"password\": \"pass\", \"host\": \"valid.host.com\", \"port\": \"3306;inject\", \"dbname\": \"test\"}");
        Properties props = new Properties();
        props.setProperty("user", "user");
        assertThrows(SQLException.class, () -> sut.connect("BAD_PORT", props));
        assertEquals(0, DummyDriver.connectCallCount);
    }

    @Test
    public void test_connect_throws_dbnameWithFragment() {
        Mockito.when(cache.getSecretString("BAD_DBNAME")).thenReturn(
                "{\"username\": \"user\", \"password\": \"pass\", \"host\": \"valid.host.com\", \"port\": \"3306\", \"dbname\": \"db#fragment\"}");
        Properties props = new Properties();
        props.setProperty("user", "user");
        assertThrows(SQLException.class, () -> sut.connect("BAD_DBNAME", props));
        assertEquals(0, DummyDriver.connectCallCount);
    }

    @Test
    public void test_connect_works_validHostPortDbname() {
        Mockito.when(cache.getSecretString("VALID_SECRET")).thenReturn(
                "{\"username\": \"user\", \"password\": \"pass\", \"host\": \"mydb.us-east-1.rds.amazonaws.com\", \"port\": \"5432\", \"dbname\": \"prod\"}");
        Properties props = new Properties();
        props.setProperty("user", "user");
        assertDoesNotThrow(() -> sut.connect("VALID_SECRET", props));
        assertEquals(1, DummyDriver.connectCallCount);
    }

    @Test
    public void test_connect_works_nullPortAndDbname() {
        Mockito.when(cache.getSecretString("MINIMAL_SECRET")).thenReturn(
                "{\"username\": \"user\", \"password\": \"pass\", \"host\": \"localhost\"}");
        Properties props = new Properties();
        props.setProperty("user", "user");
        assertDoesNotThrow(() -> sut.connect("MINIMAL_SECRET", props));
        assertEquals(1, DummyDriver.connectCallCount);
    }

    @Test
    public void test_connect_throws_emptyHost() {
        Mockito.when(cache.getSecretString("EMPTY_HOST")).thenReturn(
                "{\"username\": \"user\", \"password\": \"pass\", \"host\": \"\", \"port\": \"3306\", \"dbname\": \"test\"}");
        Properties props = new Properties();
        props.setProperty("user", "user");
        assertThrows(SQLException.class, () -> sut.connect("EMPTY_HOST", props));
        assertEquals(0, DummyDriver.connectCallCount);
    }

    @Test
    public void test_connect_works_emptyPortAndDbname() {
        Mockito.when(cache.getSecretString("EMPTY_OPTIONAL")).thenReturn(
                "{\"username\": \"user\", \"password\": \"pass\", \"host\": \"valid.host.com\", \"port\": \"\", \"dbname\": \"\"}");
        Properties props = new Properties();
        props.setProperty("user", "user");
        assertDoesNotThrow(() -> sut.connect("EMPTY_OPTIONAL", props));
        assertEquals(1, DummyDriver.connectCallCount);
    }
}
