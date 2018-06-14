package com.puzzel.gradle.plugin.pushlink

import com.android.build.gradle.AppExtension
import com.android.build.gradle.AppPlugin
import com.android.build.gradle.api.ApplicationVariant
import org.gradle.api.Plugin
import org.gradle.api.Project

class PushlinkPublisherPlugin implements Plugin<Project> {

    void apply(Project project) {
        def hasAndroidApp = project.plugins.hasPlugin AppPlugin

        if (!hasAndroidApp) {
            throw new IllegalStateException("Android plugin is not found")
        }

        applyExtensions(project)
        applyTasks(project)
    }

    void applyExtensions(final Project project) {
        project.extensions.add("pushlinkPublisher", PushlinkPublisherExtension)
    }

    void applyTasks(final Project project) {
        AppExtension android = project.android
        android.applicationVariants.all { ApplicationVariant variant ->
            if (!variant.buildType.isDebuggable()) {
                PushlinkPublisherTask task = project.tasks.create("publish${variant.name.capitalize()}ToPushlink", PushlinkPublisherTask)

                task.group = "Publish"
                task.description = "Publishes '${variant.name}' to Pushlink"
                task.variant = variant
                task.dependsOn variant.assemble
            }
        }
    }
}