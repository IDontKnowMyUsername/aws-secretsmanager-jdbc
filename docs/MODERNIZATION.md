# Modernization Guide: aws-secretsmanager-jdbc v2.0.5

## Executive Summary

This document catalogs concrete modernization opportunities for the `aws-secretsmanager-jdbc` library as of 2026. The project provides JDBC driver wrappers that transparently resolve database credentials from AWS Secrets Manager.

**Current state:**
- **Source target:** Java 8 (`<release>8</release>` in `pom.xml:140`)
- **CI runtime:** JDK 17+
- **Dependencies:** Modern (AWS SDK v2.42.6, Jackson 3.1.0, JUnit Jupiter 6.x, Mockito 5.22)
- **Codebase size:** ~10 production source files, well-tested

The Java 8 target is the single biggest constraint. Upgrading to Java 17 LTS unlocks every improvement listed below with minimal risk given the project's small surface area and comprehensive test suite.

---

## 1. Java Version Upgrade

**File:** `pom.xml:140`

Change `<release>8</release>` to `<release>17</release>`.

**What this unlocks:**
- Pattern matching `instanceof` (Java 16)
- `DriverManager.drivers()` stream (Java 9)
- Try-with-resources on effectively final variables (Java 9)
- `Properties.stringPropertyNames()` iteration without unchecked casts (available since Java 6 but replaces `Enumeration`-based patterns)
- JPMS `module-info.java` support (Java 9)

**Compatibility impact:** Consumers still on Java 8 would no longer be able to use this library. Given that Java 8 reached its last free public update in March 2022 and the AWS SDK v2 itself requires Java 8+, this is a reasonable tradeoff. Java 17 LTS (supported through at least September 2029) is the pragmatic floor for new library development in 2026.

---

## 2. Bug Fixes ✅ DONE

These are correctness issues that should be fixed regardless of the Java version target.

### 2a. Resource Leak in Config

**File:** `src/main/java/com/amazonaws/secretsmanager/util/Config.java:69-76`

The `InputStream` from `getResourceAsStream()` is closed manually with no guarantee of closure if `newConfig.load()` throws:

```java
configFile = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName);
if(configFile != null) {
    newConfig.load(configFile);
    configFile.close();  // skipped if load() throws
}
```

**Fix:** Use try-with-resources:

```java
try (InputStream configFile = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName)) {
    if (configFile != null) {
        newConfig.load(configFile);
    }
}
```

This is valid even on Java 8.

### 2b. Swallowed InterruptedException

**File:** `src/main/java/com/amazonaws/secretsmanager/sql/AWSSecretsManagerDriver.java:390-392`

```java
} catch (InterruptedException e) {
    throw new RuntimeException(e);
}
```

When catching `InterruptedException` and rethrowing as a different exception, the thread's interrupt flag must be restored. Without this, callers higher in the stack have no way to know the thread was interrupted.

**Fix:**

```java
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    throw new RuntimeException(e);
}
```

---

## 3. Modern Java Idioms ✅ DONE

These changes require Java 16+ (or as noted, some work on Java 8 but currently use older patterns).

### 3a. Pattern Matching instanceof

Five files use the cast-after-instanceof pattern:

| File | Line | Current Pattern |
|------|------|-----------------|
| `SQLExceptionUtils.java` | 25 | `t instanceof SQLException && ((SQLException)t).getErrorCode()` |
| `AWSSecretsManagerPostgreSQLDriver.java` | 113-114 | `e instanceof SQLException` then cast |
| `AWSSecretsManagerOracleDriver.java` | 124 | same |
| `AWSSecretsManagerMSSQLServerDriver.java` | 109 | same |
| `AWSSecretsManagerRedshiftDriver.java` | 109 | same |

**Example (SQLExceptionUtils.java:25):**

```java
// Before
if (t instanceof SQLException && ((SQLException)t).getErrorCode() == errorCode) {

// After
if (t instanceof SQLException sqle && sqle.getErrorCode() == errorCode) {
```

**Example (AWSSecretsManagerPostgreSQLDriver.java:112-120):**

```java
// Before
if (e instanceof SQLException) {
    SQLException sqle = (SQLException) e;
    String sqlState = sqle.getSQLState();
    ...
}

// After
if (e instanceof SQLException sqle) {
    String sqlState = sqle.getSQLState();
    ...
}
```

### 3b. Replace Enumeration with stringPropertyNames()

**File:** `src/main/java/com/amazonaws/secretsmanager/util/Config.java:140-161`

The `getSubconfig()` method uses an unchecked cast to `Enumeration<String>` (suppressed with `@SuppressWarnings("unchecked")`):

```java
@SuppressWarnings("unchecked")
public Config getSubconfig(String subprefix) {
    Enumeration<String> propertyNames = (Enumeration<String>) config.propertyNames();
    ...
    while (propertyNames.hasMoreElements()) {
        String name = propertyNames.nextElement();
```

**Fix:** Replace with `Properties.stringPropertyNames()`, which returns `Set<String>` and requires no unchecked cast:

```java
public Config getSubconfig(String subprefix) {
    for (String name : config.stringPropertyNames()) {
```

This also removes the `@SuppressWarnings("unchecked")` annotation and the `java.util.Enumeration` import (if no longer needed elsewhere in the file).

### 3c. Replace DriverManager.getDrivers() with DriverManager.drivers()

**File:** `src/main/java/com/amazonaws/secretsmanager/sql/AWSSecretsManagerDriver.java:281-287`

```java
// Before (Enumeration-based, pre-Java 9)
Enumeration<Driver> availableDrivers = DriverManager.getDrivers();
while (availableDrivers.hasMoreElements()) {
    Driver driver = availableDrivers.nextElement();
    if (driver.getClass().getName().equals(this.realDriverClass)) {
        return driver;
    }
}

// After (Stream-based, Java 9+)
return DriverManager.drivers()
    .filter(d -> d.getClass().getName().equals(this.realDriverClass))
    .findFirst()
    .orElseThrow(() -> new IllegalStateException(...));
```

### 3d. Use startsWith() Instead of indexOf() == 0

**File:** `src/main/java/com/amazonaws/secretsmanager/util/Config.java:116`

```java
// Before
return propertyName.indexOf(subprefix + ".") == 0;

// After
return propertyName.startsWith(subprefix + ".");
```

This is a readability improvement and works on any Java version.

---

## 4. Dependency Cleanup: Remove Lombok ✅ DONE

**File:** `pom.xml:76-81`, `Config.java:21,34`

Lombok is included as a `provided` dependency (v1.18.42) but is only used in a single place: `@EqualsAndHashCode` on the `Config` class.

```java
import lombok.EqualsAndHashCode;

@EqualsAndHashCode
public final class Config {
```

**Recommendation:** Remove Lombok entirely and generate `equals()` and `hashCode()` manually (or via IDE). For a class with two fields (`Properties config` and `String prefix`), this is trivial:

```java
@Override
public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Config c)) return false;
    return Objects.equals(config, c.config) && Objects.equals(prefix, c.prefix);
}

@Override
public int hashCode() {
    return Objects.hash(config, prefix);
}
```

**Why:** Lombok adds build complexity (annotation processing, IDE plugins, compatibility risks with newer JDKs) for negligible benefit when used this sparingly. Removing it simplifies the build and eliminates a `provided`-scope dependency.

---

## 5. Build Improvements ✅ DONE

### 5a. Add maven-enforcer-plugin

Enforce minimum Maven and JDK versions to prevent silent build failures:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-enforcer-plugin</artifactId>
    <version>3.5.0</version>
    <executions>
        <execution>
            <id>enforce-versions</id>
            <goals><goal>enforce</goal></goals>
            <configuration>
                <rules>
                    <requireMavenVersion>
                        <version>3.8.0</version>
                    </requireMavenVersion>
                    <requireJavaVersion>
                        <version>17</version>
                    </requireJavaVersion>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### 5b. JPMS module-info.java

Consider adding a `module-info.java` for consumers who use the module path. This is optional since most JDBC libraries remain on the classpath, but it future-proofs the library. A minimal module descriptor:

```java
module com.amazonaws.secretsmanager.jdbc {
    requires java.sql;
    requires software.amazon.awssdk.secretsmanager;
    requires com.amazonaws.secretsmanager.caching;
    requires tools.jackson.databind;

    exports com.amazonaws.secretsmanager.sql;
    exports com.amazonaws.secretsmanager.util;
}
```

**Caveat:** All transitive dependencies must also be module-compatible. Test this thoroughly before adopting.

---

## 6. Logging ✅ DONE (no changes needed; SLF4J preference noted for future logging)

**File:** `src/main/java/com/amazonaws/secretsmanager/sql/AWSSecretsManagerDriver.java:23`

The `java.util.logging.Logger` import exists only to satisfy the `getParentLogger()` method in the `java.sql.Driver` interface (line 409-410). The import is necessary for the method signature, but no actual logging is performed anywhere in the production code.

If logging is added in the future, prefer SLF4J (already a test dependency via `slf4j-simple`) over JUL. This would align with the AWS SDK v2's own SLF4J usage.

---

## 7. Items Not Recommended

The following Java 17 features were considered and intentionally excluded:

| Feature | Why Not |
|---------|---------|
| **Records** | `Config` has private mutable state and complex construction logic. The driver classes use inheritance. No classes are simple data carriers suitable for records. |
| **Sealed classes** | The `AWSSecretsManagerDriver` hierarchy is `abstract` with subclasses in the same package, but sealing adds no value -- users don't extend these classes, and the existing `final`/package-private design is sufficient. |
| **Switch expressions** | No `switch` statements exist in the production code. |
| **Text blocks** | No multi-line string literals exist in the production code. The URL-building methods use concatenation which is clearer for their short dynamic strings. |

---

## 8. Priority Summary

| # | Change | Impact | Risk | Effort |
|---|--------|--------|------|--------|
| 1 | Fix resource leak (`Config.java:69-76`) | High (correctness) | Low | 5 min |
| 2 | Fix swallowed `InterruptedException` (`AWSSecretsManagerDriver.java:390`) | High (correctness) | Low | 2 min |
| 3 | `startsWith()` instead of `indexOf() == 0` (`Config.java:116`) | Low (readability) | None | 1 min |
| 4 | Upgrade `<release>` to 17 (`pom.xml:140`) | High (enables everything below) | Medium | 10 min |
| 5 | Pattern matching `instanceof` (5 files) | Medium (readability) | Low | 15 min |
| 6 | Replace `Enumeration` iteration (`Config.java:141`, `AWSSecretsManagerDriver.java:281`) | Medium (readability) | Low | 10 min |
| 7 | Remove Lombok | Medium (build simplicity) | Low | 15 min |
| 8 | Add `maven-enforcer-plugin` | Medium (build safety) | None | 5 min |
| 9 | Add `module-info.java` | Low (future-proofing) | Medium | 30 min |

Items 1-3 can be done immediately on the current Java 8 target. Items 4-9 should be done together in a single version bump.
