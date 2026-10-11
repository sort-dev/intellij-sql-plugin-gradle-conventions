# intellij-sql-plugin-gradle-conventions

Shared Gradle build logic for the sort-dev SQL dialect IntelliJ plugins (Doris, DuckDB, Trino, SQL Transpiler). Not published: each plugin vendors a copy into `build-logic/`.

`build-logic/` holds the `dev.sort.sql-dialect-plugin` convention plugin and the shared version catalog, `build-logic/gradle/libs.versions.toml`.

## Using it in a plugin

`settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") { from(files("build-logic/gradle/libs.versions.toml")) }
    }
}

rootProject.name = "doris-intellij-plugin"
```

`build.gradle.kts`:

```kotlin
plugins {
    id("dev.sort.sql-dialect-plugin")
}

group = "dev.sort.doris"
version = "1.5.8"

sqlDialectPlugin {
    embeddedLibraries = setOf("brikk-sql-jvmMain", "brikk-sql-metadata-jvmMain", "antlr4-runtime")
    requiredNotices = listOf("ANTLR")
}

dependencies {
    implementation(libs.brikk.sql.jvm)
    implementation(libs.brikk.sql.metadata.jvm)
    implementation(libs.antlr4.runtime)
}
```

`gradle.properties` keeps `kotlin.stdlib.default.dependency=false`.

The plugin applies Kotlin JVM and the IntelliJ Platform Gradle plugin, compiles for Java 21 against the catalog's 261 SDK (`-P<dialect>.localIde=<path>` uses a local IDE instead), sets since/until builds, adds the database, JSON, Java, Kotlin and Git bundled plugins, configures the Plugin Verifier with a project-local cache, packages `LICENSE`, `NOTICE` and `THIRD_PARTY_NOTICES.md` into `META-INF`, and keeps the Kotlin standard library and kotlinx-serialization out of the distribution. A plugin can pin a different version in its settings with `version("<alias>", "<version>")` inside `create("libs")`.

### `sqlDialectPlugin`

| Property | Default | Meaning |
| --- | --- | --- |
| `pluginId` | `dev.sort.<project name>` | Plugin loaded by the test fixture |
| `bundledPlugins` | database, JSON, Java, Kotlin, Git | Bundled plugins for compilation and tests |
| `embeddedLibraries` | none | Jar names, without versions, expected in `lib/` beside the plugin jar |
| `bytecodeCeilings` | none | Class file version allowed per library, instead of 65 (Java 21) |
| `requiredNotices` | none | Text that `THIRD_PARTY_NOTICES.md` must contain |
| `internalApiPackages` | none | Packages whose use is confined to `internalApiAllowedIn` |
| `internalApiAllowedIn` | none | Packages allowed to reference `internalApiPackages` |

## Test lanes

| Task or property | Effect |
| --- | --- |
| `test` | Headless suite on the 261 SDK |
| `test262`, `test263` | The same suite on each `intellij-runtime-*` build in the catalog; `-Ptest.ide<generation>=<path>` uses a local IDE |
| `-Ptest.kotlin=absent` | Leaves out the Java and Kotlin plugins and `**/brikk/**` tests (default `present`) |
| `-Ptest.pluginIsolation=true` | Runs only `*Isolation*Test`, loading the plugin and its libraries through real plugin classloaders |
| `-Ptest.sqlTranspiler=installed` | Installs the SQL Transpiler plugin from Marketplace |
| `-Ptest.<sibling>PluginZip=<zip>` | Installs a sibling plugin ZIP (`doris`, `duckdb`, `trino`) |

Tests receive `test.kotlin`, `test.pluginIsolation`, `test.sqlTranspiler` and `test.siblingPlugins` as system properties. Sibling plugins' private `brikk-*` and Kotlin standard library jars are filtered from the flattened test classpath.

## Tasks

- `verifyDistribution` (part of `check`): checks the plugin ZIP's embedded jars against `embeddedLibraries`, class file versions, the packaged license files against the project's, `requiredNotices` and every embedded `dev.brikk` `artifact:version` in `THIRD_PARTY_NOTICES.md`, and that `plugin.xml` declares no dependency on a sibling plugin. A release version refuses `-SNAPSHOT` libraries.
- `dogfoodZip`: copies the ZIP to `build/dogfood/<name>-<version>.zip`, writes a `.sha256` beside it and prints the since/until builds.
- `verifyBuildLogic` (part of `check`): fails when `build-logic/` differs from the commit it was vendored from.

## Syncing

Commit here first, then:

```sh
./sync-into.sh ../doris-intellij-plugin            # HEAD
./sync-into.sh ../doris-intellij-plugin <commit>
```

The script replaces `<plugin>/build-logic/` with `build-logic/` at that commit and writes `build-logic/VENDORED-FROM` with the repository, commit and the commit's git tree id for `build-logic`. `verifyBuildLogic` computes the git tree id of the vendored copy, ignoring `VENDORED-FROM`, `build/`, `.gradle/` and `.kotlin/`, and compares it with the recorded one, so a local edit fails `check`. Anyone can confirm the recorded id with `git rev-parse <commit>:build-logic`.
