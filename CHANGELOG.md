# Changelog

### Release 2.2.0 (September 16, 2026)
* Requires Java 21. Builds on upstream 2.1.3 with the AWS SDK, Jackson 3 and JUnit 6.
* `connect` now reports every failure as `SQLException`: unknown or empty secrets, AWS client errors, and a missing real driver no longer escape as runtime exceptions.
* The AWS Secrets Manager client is created on first connect instead of when the driver class loads, so a missing region no longer breaks class loading.
* `getPropertyInfo` accepts a secret ID as the URL, matching `acceptsURL` and `connect`.
* `aws-crt-client` is optional; add it only when `drivers.postQuantumTlsEnabled=true`.
* Replaced `module-info.java` with an `Automatic-Module-Name` manifest entry; the shaded jar no longer carries stray dependency module descriptors.
* Invalid boolean properties throw `PropertyException` like the other typed properties.
* Retry log messages count refreshes correctly.

### Release 1.0.2 (May 28, 2018)
* Add support for MariaDB
* For MySQL, check for com.mysql.cj.jdbc.Driver in class path and fall back to com.mysql.jdbc.Driver
* Change JSON parsing error message to be more generic

### Release 1.0.1 (December 5, 2018)
* Fixed an issue with the way JDBC URLs are handled:
  * acceptsURL() now returns false if the URL is a JDBC URL that does not begin with jdbc-secretsmanager
  * connect() returns null if the URL parameter is not one we accept
* Updated jackson-databind dependency to 2.8.11.1
