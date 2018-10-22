# gradle-pushlink-plugin
Gradle PushLink plugin

Adds tasks to publish releases to PushLink.

Based on code from https://github.com/x2on/gradle-hockeyapp-plugin

# Configuration
To use this plugin you must first apply it in your app module:

    apply plugin: PushlinkPublisherPlugin

Next, add a new section with config for the plugin:
    pushlinkPublisher { 
        apiKey = ''
    //      setCurrent = true
    }

The _apiKey_ is mandatory and must be set to a valid PushLink API key.
The _setCurrent_ flag is optional. If set to _true_ then the uploaded APK will be set to current.

# Tasks
The plugin will add a task for each configuration named as _publish_ConfigurationName_ToPushlink_. For example _publishAppToPushlink_.
