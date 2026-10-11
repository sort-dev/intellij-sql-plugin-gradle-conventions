package dev.sort.gradle

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.exclude
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.withType
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformDependenciesExtension
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformExtension
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformRepositoriesExtension
import org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

private val dialects = listOf("doris", "duckdb", "trino", "sql-transpiler")
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
            bundledPlugins.convention(
                listOf("com.intellij.database", "com.intellij.modules.json", "com.intellij.java", "org.jetbrains.kotlin", "Git4Idea"),
            )
        }

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

        val testPluginIds = extension.bundledPlugins.zip(extension.pluginId) { bundled, id -> (bundled + id).joinToString(",") }
        val siblingPaths = siblings.map { "/$it-intellij-plugin/" }
        fun Test.configureSuite() {
            useJUnit()
            systemProperty("java.awt.headless", "true")
            jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Didea.load.plugins.id=${testPluginIds.get()}") })
            classpath = classpath.filter { entry ->
                siblingPaths.none { it in entry.path } || !(entry.name.startsWith("brikk-") || entry.name.startsWith("kotlin-stdlib"))
            }
        }

        tasks.named<ProcessResources>("processResources") {
            from(files("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md")) { into("META-INF") }
        }
        tasks.named<ProcessResources>("processTestResources") {
            from("src/main/resources/META-INF") {
                include("*-kotlin.xml")
                into("META-INF")
            }
        }
        tasks.named<BuildPluginTask>("buildPlugin") { archiveVersion.set("") }
        tasks.withType<VerifyPluginTask>().configureEach {
            systemProperty("plugin.verifier.home.dir", layout.buildDirectory.dir("plugin-verifier-cache").get().asFile.absolutePath)
        }
        tasks.named<Test>("test") { configureSuite() }
    }
}
