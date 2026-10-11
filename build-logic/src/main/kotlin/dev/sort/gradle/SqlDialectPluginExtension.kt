package dev.sort.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

abstract class SqlDialectPluginExtension {
    abstract val pluginId: Property<String>
    abstract val bundledPlugins: ListProperty<String>
}
