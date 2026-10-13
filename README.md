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

`dev.brikk.house` snapshot versions resolve only from Maven Local, even when a build declares a snapshot repository; released versions come from Maven Central.

The plugin applies Kotlin JVM and the IntelliJ Platform Gradle plugin, compiles for Java 21 against the catalog's 261 SDK (`-P<dialect>.localIde=<path>` uses a local IDE instead), sets since/until builds, adds the database, JSON, Java and Kotlin bundled plugins, configures the Plugin Verifier with a project-local cache, packages the legal files into `META-INF`, and builds an unversioned `buildPlugin` ZIP. A plugin keeps its current versions by pinning them in settings with `version("<alias>", "<version>")` inside `create("libs")`, and overrides anything else with the usual DSL, for example `tasks.buildPlugin { archiveVersion = … }` or `kotlin { compilerOptions { jvmDefault = … } }`.

### `sqlDialectPlugin`

| Property | Default | Meaning |
| --- | --- | --- |
| `pluginId` | `dev.sort.<project name>` | Plugin loaded by the test fixture |
| `additionalBundledPlugins` | none | Bundled plugins beyond database, JSON, Java and Kotlin |
| `kotlinTests` | `true` | Whether lanes load the Java and Kotlin plugins and run `**/brikk/**` tests unless `-Ptest.kotlin` says otherwise |
| `excludePlatformKotlin` | `true` | Removes `kotlin-stdlib` and kotlinx-serialization from `dev.brikk.house` dependencies and compiles against kotlinx-serialization instead; `false` for a plugin that ships its own copies |
| `legalFiles` | `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md` | Files packaged into `META-INF`; each must exist |
| `additionalVerifierIdes` | none | Verifier IDEs (`IU-2026.1.3`) beyond the catalog's list |
| `embeddedLibraries` | none | Jars expected in `lib/` beside the plugin jar: an exact file name, or a name without version |
| `bytecodeCeilings` | none | Class file version allowed per library name, instead of 65 (Java 21) |
| `requiredNotices` | none | Text that `THIRD_PARTY_NOTICES.md` must contain |
| `internalApiPackages` | none | Packages whose use is confined to `internalApiAllowedIn` |
| `internalApiAllowedIn` | none | Packages allowed to reference `internalApiPackages` |

## Test lanes

| Task or property | Effect |
| --- | --- |
| `test` | Headless suite on the 261 SDK |
| `test262`, `test263` | The same suite on each `intellij-runtime-*` build in the catalog, with Git4Idea added; `-Ptest.ide<generation>=<path>` uses a local IDE |
| `-Ptest.kotlin=present\|absent` | Overrides `kotlinTests` |
| `-Ptest.pluginIsolation=true` | Runs only `*Isolation*Test`, without Kotlin, loading the plugin and its libraries through real plugin classloaders; refuses `-Ptest.kotlin=present` |
| `-Ptest.sqlTranspiler=installed` | Installs the SQL Transpiler plugin from Marketplace |
| `-Ptest.<sibling>PluginZip=<zip>` | Installs a sibling plugin ZIP (`clickhouse`, `doris`, `duckdb`, `trino`, `sqlTranspiler`); `sqlTranspiler` cannot be combined with `-Ptest.sqlTranspiler` |

Tests receive `test.kotlin`, `test.pluginIsolation`, `test.sqlTranspiler` (`installed` for either source) and `test.siblingPlugins` (the dialect ZIPs) as system properties. Sibling plugins' private `brikk-*` and Kotlin standard library jars are filtered from the flattened test classpath.

## Tasks

- `verifyDistribution` (part of `check`, required by `publishPlugin`): checks the plugin ZIP's embedded jars against `embeddedLibraries`, duplicate jars, class file versions, the packaged legal files against the project's, `requiredNotices` and every embedded `dev.brikk.house` `artifact:version` in `THIRD_PARTY_NOTICES.md`, and that `plugin.xml` declares no dependency on a sibling plugin.
- `verifyRelease` (required by `publishPlugin`): refuses `-SNAPSHOT` libraries in a release version. `check` and `dogfoodZip` allow them for local development.
- `dogfoodZip`: copies the ZIP to `build/dogfood/<name>-<version>.zip`, writes a `.sha256` beside it and prints the since/until builds.
- `verifyBuildLogic` (part of `check`, `assemble` and `buildPlugin`): fails when `build-logic/` differs from the commit it was vendored from.

## Syncing

Commit here first, then:

```sh
./sync-into.sh ../doris-intellij-plugin            # HEAD
./sync-into.sh ../doris-intellij-plugin <commit>
./sync-into.sh --force ../doris-intellij-plugin    # replace a copy with local changes
```

The script replaces `<plugin>/build-logic/` with `build-logic/` at that commit and writes `build-logic/VENDORED-FROM` with the repository, commit and the commit's git tree id for `build-logic`. It refuses to replace a copy that differs from its recorded commit unless given `--force`.

`verifyBuildLogic` computes the git tree id of the vendored copy and compares it with the recorded one, so an edited file fails the build. It skips `VENDORED-FROM`, `build`, `local.properties`, dotfiles and `*.orig` at any depth, so `build-logic/` here must not track such names. The check trusts `VENDORED-FROM`: someone who edits the files and the recorded tree together is not detected offline. Confirm the record against this repository with `git rev-parse <commit>:build-logic`.
