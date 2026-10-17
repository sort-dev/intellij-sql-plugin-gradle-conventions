# intellij-sql-plugin-gradle-conventions

Shared Gradle build logic for the sort-dev SQL dialect IntelliJ plugins (ClickHouse, Doris, DuckDB, Trino, SQL Transpiler). Not published: each plugin vendors a copy into `build-logic/`.

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

The plugin applies Kotlin JVM and the IntelliJ Platform Gradle plugin, compiles for Java 21 against the catalog's 261 SDK (`-P<dialect>.localIde=<path>` uses a local IDE instead), sets since/until builds, adds the database, JSON, Java and Kotlin bundled plugins, configures the Plugin Verifier with a project-local cache, and packages the legal files into `META-INF`. A plugin keeps its dependency versions by pinning them in settings with `version("<alias>", "<version>")` inside `create("libs")`, and overrides anything else with the usual DSL.

## Build versions and ZIP names

Keep `version = "<base>"` in the plugin build. By default, the patched IDE descriptor
and ZIP use `<base>-dev.<yyyyMMddHHmm UTC>+<12-character git sha>`, adding `.dirty`
for tracked or untracked changes. The time is HEAD's committer time, not build time.
Ignored outputs do not mark a build dirty. Git is a configuration-cache input, so
new commits and clean/dirty transitions invalidate the cached version.

ZIPs are `build/distributions/<last part of plugin id>-<version>.zip`, for example
`duckdb-intellij-plugin-0.4.0-dev.202610132007+661798ef63e4.zip`.
`verifyDistribution` requires the descriptor version to match that build version.
`dogfoodZip` preserves the same filename.

`./gradlew buildPlugin -Prelease=true` uses the declared base unchanged and runs
`verifyRelease`, so a stable ZIP that embeds `-SNAPSHOT` libraries is never built.
It does not choose a release number or publish anything. `publishPlugin` refuses dev
labels and snapshot dependencies. Builds without `-Prelease=true` keep dev labels
and allow Maven Local snapshots.

### Releasing

The catalog pins released Brikk libraries only: core, metadata and verify `0.20.0`,
SQL support and test kit `0.2.0`, Kotlin helper `0.3.0`. They must be on Maven
Central before a plugin can build against them. To adopt them, a plugin:

1. Removes its `version("brikk-…", "…-SNAPSHOT")` overrides from `settings.gradle.kts`
   and uses the catalog versions.
2. Sets `version = "<next release base>"` in `build.gradle.kts`.
3. Builds the release with `./gradlew buildPlugin -Prelease=true` (or `publishPlugin
   -Prelease=true`). Its release workflow checks that the ZIP is named
   `<plugin>-<base>.zip` and that `META-INF/plugin.xml` declares `<version><base></version>`;
   it never renames a dev ZIP into a release. The shared check does this and prints
   the validated path to upload unchanged:

   ```sh
   ZIP=$(python3 build-logic/tools/verify-release-zip.py dist dev.sort.doris-intellij-plugin "$V")
   ```

   It requires exactly one ZIP in the directory, `<name>/lib/<name>-<base>.jar` as the
   only main jar, and the descriptor's `<id>` and `<version>` (parsed as XML). Its test
   runs with `python3 build-logic/tools/verify-release-zip-test.py`.

### Release workflow

`.github/workflows/release-plugin-zip.yml` is a reusable workflow for GitHub release
assets. The calling commit must already be on `main`, with its final version committed there.
Create `release/<version>` from that commit. The workflow rejects unrelated branch commits and
branch/version mismatches. It reads the one `version = "<base>"` line, builds in release mode,
runs Plugin Verifier against IntelliJ 262 and 263 with strict internal/experimental API checks, validates the ZIP
with `build-logic/tools/verify-release-zip.py`, creates tag `v<base>` at that commit if
absent, and creates release `v<base>` with the validated ZIP unchanged. It refuses an
existing release or a tag on another commit. It uses only `GITHUB_TOKEN` and never
publishes to JetBrains Marketplace or Maven. Internal and experimental API findings block release;
Compatible alone is not approval. Complete reports are retained even on failure. Any exception needs
explicit owner approval of the exact usages, not an assumed transfer of a previous Marketplace waiver.

A plugin calls it from `.github/workflows/release.yml`, pinned to a commit of this
repository:

```yaml
name: Release

on:
  push:
    branches: [ 'release/**' ]

jobs:
  release:
    permissions:
      contents: write
    uses: sort-dev/intellij-sql-plugin-gradle-conventions/.github/workflows/release-plugin-zip.yml@<commit>
    with:
      plugin-id: dev.sort.doris-intellij-plugin
      title: SQL Dialect for Apache Doris
```

`java-version` defaults to `21`. A plugin that already has a release job in `ci.yml`
deletes that job so each `release/<version>` push creates one release. Workflow-only changes
here need no re-sync; the vendored `build-logic/` must already contain
`tools/verify-release-zip.py`.

Local API audits can select targets with `-PverificationIdes='IU-262.10968.63 IU-263.5701.42'` and
use `-PstrictApiVerification=true`. Without a target override, the complete configured IDE list is used.

**IntelliJ ordering:** its comparator treats `-dev` as newer than the same bare
base, not as a SemVer prerelease. Thus `0.3.5 < 0.4.0-dev.… < 0.4.1`, but a bare
`0.4.0` does not automatically replace `0.4.0-dev.…`. Use manual downgrade/removal
to install that same-base release, or let the owner choose a later release base.
Dates order different minutes; SHA strings distinguish commits within a minute,
not their chronological order. Dirty labels identify uncommitted builds, not each
individual dirty edit; staging refuses them.

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
| `-Ptest.pluginIsolation=true` | Runs only `*Isolation*Test`, without Kotlin, loading the plugin and its libraries through real plugin classloaders; refuses `-Ptest.kotlin=present`. Discovers the newest matching versioned ZIP by modification time, preferring matching worktrees, then adjacent checkouts. Legacy unversioned ZIPs remain accepted until peers sync. Missing ZIPs fail with build instructions, never a silent Marketplace fallback. |
| `-Ptest.sqlTranspiler=installed` | Installs the SQL Transpiler plugin from Marketplace |
| `-Ptest.<sibling>PluginZip=<zip>` | Installs a sibling plugin ZIP (`clickhouse`, `doris`, `duckdb`, `trino`, `sqlTranspiler`); `sqlTranspiler` cannot be combined with `-Ptest.sqlTranspiler` |

Tests receive `test.kotlin`, `test.pluginIsolation`, `test.sqlTranspiler` (`installed` for either source) and `test.siblingPlugins` (the dialect ZIPs) as system properties. Sibling plugins' private `brikk-*` and Kotlin standard library jars are filtered from the flattened test classpath.

## Tasks

- `verifyDistribution` (part of `check`, required by `publishPlugin`): checks the plugin ZIP's embedded jars against `embeddedLibraries`, duplicate jars, class file versions, the packaged legal files against the project's, `requiredNotices` and every embedded `dev.brikk.house` `artifact:version` in `THIRD_PARTY_NOTICES.md`, and that `plugin.xml` declares no dependency on a sibling plugin.
- `verifyRelease` (required by `publishPlugin`, and by `buildPlugin` with `-Prelease=true`): refuses dev labels and `-SNAPSHOT` libraries in a release version. Dev builds, `check` and `dogfoodZip` allow snapshots for local development.
- `verifyBundledSnapshotHashes` (explicit only): compares every resolved `dev.brikk.house` snapshot JAR in the ZIP with Maven Local by full SHA-256. Run `./gradlew buildPlugin verifyBundledSnapshotHashes` for local snapshot delivery checks. Missing, duplicate or stale bundled JARs fail. Released libraries are not compared; tests, builds and release tasks do not depend on this check. Uses `maven.repo.local` when set, otherwise `~/.m2/repository`.
- `dogfoodZip`: copies the ZIP to `build/dogfood/<name>-<version>.zip`, writes a `.sha256` beside it and prints the since/until builds.
- `verifyBuildLogic` (part of `check`, `assemble` and `buildPlugin`): fails when `build-logic/` differs from the commit it was vendored from.

## Staging the five plugins

```sh
tools/stage-plugin-builds.sh --dry-run
tools/stage-plugin-builds.sh
```

The dry run only reads Git/checkouts and prints the commands. The real run builds
`buildPlugin` for the Doris, DuckDB, Trino and SQL Transpiler `grand-unification`
worktrees under `/home/jayson/DEV/sortdev`, plus `clickhouse-intellij-plugin`.
It uses an in-memory Gradle init script to apply the same dev label even to a peer
that has not yet synced these conventions. It never edits sources, syncs, commits,
publishes or rewrites a built ZIP. Only normal ignored build outputs are produced.

Clean successful artifacts go to
`/home/jayson/DEV/sortdev/plugin-builds/<yyyyMMdd-HHmm UTC>/`. `MANIFEST.md` records
plugin id, IDE version, branch/full SHA, source checkout, IDE since/until range,
ZIP SHA-256 and built-at UTC. Build logs are kept beside it. Dirty, failed or
concurrently changed checkouts are skipped and reported; a partial real run exits
nonzero. Existing minute directories and ZIPs are never overwritten. Use
`--output-root DIR` only when a different staging root is wanted.

## Syncing

Commit here first, then:

```sh
./sync-into.sh ../doris-intellij-plugin            # HEAD
./sync-into.sh ../doris-intellij-plugin <commit>
./sync-into.sh --force ../doris-intellij-plugin    # replace a copy with local changes
```

The script replaces `<plugin>/build-logic/` with `build-logic/` at that commit and writes `build-logic/VENDORED-FROM` with the repository, commit and the commit's git tree id for `build-logic`. It refuses to replace a copy that differs from its recorded commit unless given `--force`.

`verifyBuildLogic` computes the git tree id of the vendored copy and compares it with the recorded one, so an edited file fails the build. It skips `VENDORED-FROM`, `build`, `local.properties`, dotfiles and `*.orig` at any depth, so `build-logic/` here must not track such names. The check trusts `VENDORED-FROM`: someone who edits the files and the recorded tree together is not detected offline. Confirm the record against this repository with `git rev-parse <commit>:build-logic`.
