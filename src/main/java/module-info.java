module com.amazonaws.secretsmanager.jdbc {
    requires java.sql;
    requires software.amazon.awssdk.services.secretsmanager;
    requires software.amazon.awssdk.regions;
    requires software.amazon.awssdk.utils;
    requires software.amazon.awssdk.http;
    requires software.amazon.awssdk.http.crt;
    requires aws.secretsmanager.caching.java;
    requires tools.jackson.databind;

    exports com.amazonaws.secretsmanager.sql;
    exports com.amazonaws.secretsmanager.util;
}
