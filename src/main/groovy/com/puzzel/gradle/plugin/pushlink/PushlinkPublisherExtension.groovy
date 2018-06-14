package com.puzzel.gradle.plugin.pushlink

class PushlinkPublisherExtension {
    String apiKey
    Map<String, String> variantToApiKey = null
    boolean setCurrent
    Map<String, Boolean> variantToSetCurrent = null
    Object outputDirectory
    String appFileNameRegex = null
    int timeout = 60 * 1000
    Map<String, String> variantToApplicationId = null
    String pushlinkApiUrl = "https://www.pushlink.com/apps/api_upload"
    boolean allowMultipleAppFiles = true
}
