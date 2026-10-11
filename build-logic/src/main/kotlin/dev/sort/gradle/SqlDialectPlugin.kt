package dev.sort.gradle

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.exclude
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformDependenciesExtension
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformExtension
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformRepositoriesExtension
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformTestingExtension
import org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

private val dialects = listOf("doris", "duckdb", "trino", "sql-transpiler")
private val kotlinHosts = listOf("com.intellij.java", "org.jetbrains.kotlin")
private const val ISOLATION_TESTS = "**/*Isolation*Test.class"
private const val RUNTIME_ALIAS = "intellij.runtime."
private val platformSerialization = listOf("core", "json").flatMap { listOf("kotlinx-serialization-$it", "kotlinx-serialization-$it-jvm") }

class SqlDialectPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        pluginManager.apply("java")
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("org.jetbrains.intellij.platform")

        val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
        fun version(alias: String) = catalog.findVersion(alias).get().requiredVersion

        val dialect = name.removeSuffix("-intellij-plugin")
        val siblings = dialects - dialect
        val extension = extensions.create<SqlDialectPluginExtension>("sqlDialectPlugin").apply {
            pluginId.convention("dev.sort.$name")
            bundledPlugins.convention(listOf("com.intellij.database", "com.intellij.modules.json", "Git4Idea") + kotlinHosts)
        }

        val kotlinMode = providers.gradleProperty("test.kotlin").getOrElse("present")
        require(kotlinMode in setOf("present", "absent")) { "test.kotlin must be present or absent" }
        val sqlTranspilerMode = providers.gradleProperty("test.sqlTranspiler").orNull
        require(sqlTranspilerMode in setOf(null, "installed", "absent")) { "test.sqlTranspiler must be installed or absent" }
        val pluginIsolation = providers.gradleProperty("test.pluginIsolation").orNull == "true"
        val siblingZips = (siblings - "sql-transpiler").mapNotNull { sibling ->
            providers.gradleProperty("test.${sibling}PluginZip").orNull?.let { sibling to file(it) }
        }.toMap()
        siblingZips.forEach { (sibling, zip) -> require(zip.isFile) { "Missing $sibling plugin ZIP: $zip" } }
        val sqlTranspilerPlugin = "dev.sort.sql-transpiler-intellij-plugin:${version("sql-transpiler-plugin")}"
            .takeIf { sqlTranspilerMode == "installed" && "sql-transpiler" in siblings }
        val installedSiblings = siblingZips.keys + listOfNotNull("sql-transpiler".takeIf { sqlTranspilerPlugin != null })

        repositories.mavenLocal {
            content { includeVersionByRegex("dev\\.brikk\\..*", ".*", ".*-SNAPSHOT") }
        }
        repositories.mavenCentral()
        (repositories as ExtensionAware).extensions.configure<IntelliJPlatformRepositoriesExtension> { defaultRepositories() }

        dependencies.add("compileOnly", catalog.findLibrary("kotlinx-serialization-json").get())
        dependencies.add("testImplementation", "org.jetbrains.kotlin:kotlin-test")
        dependencies.add("testImplementation", catalog.findLibrary("junit").get())
        dependencies.extensions.configure<IntelliJPlatformDependenciesExtension> {
            val localIde = providers.gradleProperty("$dialect.localIde")
            if (localIde.isPresent) local(localIde) else intellijIdea(version("intellij-sdk"))
            bundledPlugins(extension.bundledPlugins)
            sqlTranspilerPlugin?.let { plugin(it) }
            siblingZips.values.forEach { localPlugin(it) }
            testFramework(TestFrameworkType.Platform)
        }
        configurations.named("runtimeClasspath") {
            exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
            platformSerialization.forEach { exclude(group = "org.jetbrains.kotlinx", module = it) }
        }
        configurations.named("testRuntimeClasspath") {
            platformSerialization.forEach { exclude(group = "org.jetbrains.kotlinx", module = it) }
        }

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_21)
                jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
            }
        }

        extensions.configure<IntelliJPlatformExtension> {
            buildSearchableOptions.set(false)
            pluginConfiguration {
                ideaVersion {
                    sinceBuild.set(version("intellij-since-build"))
                    untilBuild.set(version("intellij-until-build"))
                }
            }
            pluginVerification {
                failureLevel.set(
                    listOf(
                        FailureLevel.COMPATIBILITY_PROBLEMS,
                        FailureLevel.COMPATIBILITY_WARNINGS,
                        FailureLevel.INVALID_PLUGIN,
                        FailureLevel.MISSING_DEPENDENCIES,
                        FailureLevel.OVERRIDE_ONLY_API_USAGES,
                        FailureLevel.NON_EXTENDABLE_API_USAGES,
                        FailureLevel.PLUGIN_STRUCTURE_WARNINGS,
                    ),
                )
                ides {
                    create(provider { version("intellij-verifier-ides").split(' ') })
                }
            }
            publishing {
                token.set(providers.gradleProperty("intellijPlatformPublishingToken"))
            }
        }

        val testPluginIds = extension.bundledPlugins.zip(extension.pluginId) { bundled, id ->
            (bundled.filter { kotlinMode == "present" || it !in kotlinHosts } + id + installedSiblings.map { "dev.sort.$it-intellij-plugin" })
                .joinToString(",")
        }
        val siblingPaths = siblings.map { "/$it-intellij-plugin/" }
        val sourceSets = extensions.getByType<SourceSetContainer>()
        val productFiles = files(
            sourceSets.named("main").map { it.output },
            layout.buildDirectory.dir("instrumented/instrumentCode"),
            sourceSets.named("test").map { it.output.resourcesDir!! },
            configurations.named("runtimeClasspath"),
        )
        val productJarPrefix = "$name-"
        val sandboxLibraries = "/$name/lib/"
        fun Test.configureSuite() {
            useJUnit()
            systemProperty("java.awt.headless", "true")
            systemProperty("test.kotlin", kotlinMode)
            systemProperty("test.pluginIsolation", pluginIsolation.toString())
            systemProperty("test.siblingPlugins", siblingZips.keys.joinToString(","))
            sqlTranspilerMode?.let { systemProperty("test.sqlTranspiler", it) }
            jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Didea.load.plugins.id=${testPluginIds.get()}") })
            if (kotlinMode == "absent") exclude("**/brikk/**")
            if (pluginIsolation) {
                include(ISOLATION_TESTS)
                classpath = (classpath - productFiles).filter { entry ->
                    siblingPaths.none { it in entry.path } && !entry.name.startsWith(productJarPrefix) && sandboxLibraries !in entry.path
                }
                jvmArgumentProviders.add(CommandLineArgumentProvider {
                    listOf("-Didea.force.use.core.classloader=true", "-Didea.use.core.classloader.for.plugin.path=false")
                })
            } else {
                include("**/*Test.class")
                exclude(ISOLATION_TESTS)
                classpath = classpath.filter { entry ->
                    siblingPaths.none { it in entry.path } || !(entry.name.startsWith("brikk-") || entry.name.startsWith("kotlin-stdlib"))
                }
            }
        }

        val testing = extensions.getByType<IntelliJPlatformTestingExtension>()
        catalog.versionAliases.filter { it.startsWith(RUNTIME_ALIAS) }.forEach { alias ->
            val generation = alias.removePrefix(RUNTIME_ALIAS)
            testing.testIde.register("test$generation") {
                type.set(IntelliJPlatformType.IntellijIdea)
                version.set(catalog.findVersion(alias).get().requiredVersion)
                providers.gradleProperty("test.ide$generation").orNull?.let { localPath.set(file(it)) }
                testFramework(TestFrameworkType.Platform)
                plugins {
                    bundledPlugins(extension.bundledPlugins)
                    sqlTranspilerPlugin?.let { plugin(it) }
                    siblingZips.values.forEach { localPlugin(it) }
                }
                task { configureSuite() }
            }
        }
        if (installedSiblings.isNotEmpty()) tasks.named<PrepareSandboxTask>("prepareTestSandbox") {
            sandboxSuffix.set("-test-" + installedSiblings.sorted().joinToString("-"))
        }

        val licenseFiles = files("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md")
        tasks.named<ProcessResources>("processResources") {
            from(licenseFiles) { into("META-INF") }
        }
        tasks.named<ProcessResources>("processTestResources") {
            from("src/main/resources/META-INF") {
                include("*-kotlin.xml")
                into("META-INF")
            }
        }
        tasks.withType<VerifyPluginTask>().configureEach {
            systemProperty("plugin.verifier.home.dir", layout.buildDirectory.dir("plugin-verifier-cache").get().asFile.absolutePath)
        }
        tasks.named<Test>("test") { configureSuite() }

        val buildPlugin = tasks.named<BuildPluginTask>("buildPlugin") { archiveVersion.set("") }
        val verifyDistribution = tasks.register<VerifyDistribution>("verifyDistribution") {
            group = "verification"
            description = "Checks the embedded libraries, bytecode levels, notices and descriptor of the plugin ZIP."
            distribution.set(buildPlugin.flatMap { it.archiveFile })
            pluginJar.set(tasks.named<Jar>("composedJar").flatMap { it.archiveFileName })
            pluginVersion.set(provider { project.version.toString() })
            embeddedLibraries.set(extension.embeddedLibraries)
            embeddedComponents.set(
                configurations.named("runtimeClasspath").flatMap { it.incoming.artifacts.resolvedArtifacts }.map { artifacts ->
                    artifacts.mapNotNull { (it.id.componentIdentifier as? ModuleComponentIdentifier)?.displayName }
                },
            )
            bytecodeCeilings.set(extension.bytecodeCeilings)
            legalFiles.from(licenseFiles)
            requiredNotices.set(extension.requiredNotices)
            siblingPluginIds.set(siblings.map { "dev.sort.$it-intellij-plugin" })
            internalApiPackages.set(extension.internalApiPackages)
            internalApiAllowedIn.set(extension.internalApiAllowedIn)
        }
        tasks.named("check") { dependsOn(verifyDistribution) }
    }
}
