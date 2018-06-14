package com.puzzel.gradle.plugin.pushlink

import com.android.build.gradle.api.ApplicationVariant
import de.felixschulze.gradle.util.ProgressHttpEntityWrapper
import de.undercouch.gradle.tasks.download.internal.ProgressLoggerWrapper
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.IOUtils
import org.apache.http.HttpHost
import org.apache.http.HttpResponse
import org.apache.http.HttpStatus
import org.apache.http.client.HttpClient
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.mime.MultipartEntityBuilder
import org.apache.http.impl.client.HttpClientBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.TaskAction

class PushlinkPublisherTask extends DefaultTask {
    ApplicationVariant variant
    PushlinkPublisherExtension pushlinkPublisher

    List<File> applicationFiles = []
    String variantName
    String uploadAllPath
    Object uploadResponse = null

    PushlinkPublisherTask() {
        super()
        this.description = 'Uploads the app apk to Pushlink'
    }

    @TaskAction
    def publish() throws IOException {
        pushlinkPublisher = project.pushlinkPublisher

        // Get all output apk files if android
        if (variant) {
            logger.debug('Using android application variants')

            variant.outputs.each {
                if (FilenameUtils.isExtension(it.outputFile.getName(), "apk")) {
                    if (it.outputFile.exists()) {
                        applicationFiles << it.outputFile
                    } else {
                        logger.debug("App file doesn't exist: $it.outputFile.absolutePath")
                    }
                }
            }
        }
        else {
            logger.debug('Not using android application variants')
        }

        if (!getApiKey()) {
            throw new IllegalArgumentException("Cannot upload to Pushlink because API key is missing")
        }

        if (!applicationFiles) {
            if (!variant && !pushlinkPublisher.appFileNameRegex) {
                throw new IllegalArgumentException("No appFileNameRegex provided.")
            }
            if (!pushlinkPublisher.outputDirectory || !pushlinkPublisher.outputDirectory.exists()) {
                throw new IllegalArgumentException("The outputDirectory (" + pushlinkPublisher.outputDirectory ? pushlinkPublisher.outputDirectory.absolutePath : " not defined " + ") doesn't exists")
            }
            applicationFiles = FileHelper.getFiles(pushlinkPublisher.appFileNameRegex, pushlinkPublisher.outputDirectory)
            if (!applicationFiles) {
                throw new IllegalStateException("No app file found in directory " + pushlinkPublisher.outputDirectory.absolutePath)
            }
        }

        def appFilePaths = applicationFiles.collect { it.name }

        if (!pushlinkPublisher.allowMultipleAppFiles && applicationFiles.size() > 1) {
            throw new IllegalStateException("Upload multiple files is not allowed: $appFilePaths")
        }

        logger.lifecycle("App files: $appFilePaths")

        String appId = null
        if (pushlinkPublisher.variantToApplicationId) {
            appId = pushlinkPublisher.variantToApplicationId[variantName]
            if (!appId) {
                if(project.getGradle().getTaskGraph().hasTask(uploadAllPath)) {
                    logger.error("Could not resolve app ID for variant: ${variantName} in the variantToApplicationId map.")
                } else {
                    throw new IllegalArgumentException("Could not resolve app ID for variant: ${variantName} in the variantToApplicationId map.")
                }
            }
        }

        applicationFiles.each {
            uploadFilesToPushlink(it)
        }

    }

    void uploadFilesToPushlink(File appFile) {
        ProgressLoggerWrapper progressLogger = new ProgressLoggerWrapper(project, "Upload file to Pushlink")

        progressLogger.started()

        RequestConfig.Builder requestBuilder = RequestConfig.custom()
        requestBuilder = requestBuilder.setConnectTimeout(pushlinkPublisher.timeout)
        requestBuilder = requestBuilder.setConnectionRequestTimeout(pushlinkPublisher.timeout)

        String proxyHost = System.getProperty("http.proxyHost", "")
        int proxyPort = System.getProperty("http.proxyPort", "0") as int
        if (proxyHost.length() > 0 && proxyPort > 0) {
            logger.lifecycle("Using proxy: " + proxyHost + ":" + proxyPort)
            HttpHost proxy = new HttpHost(proxyHost, proxyPort)
            requestBuilder = requestBuilder.setProxy(proxy)
        }

        HttpClientBuilder builder = HttpClientBuilder.create()
        builder.setDefaultRequestConfig(requestBuilder.build())
        HttpClient httpClient = builder.build()

        String uploadUrl = pushlinkPublisher.pushlinkApiUrl

        HttpPost httpPost = new HttpPost(uploadUrl)
        logger.info("Will upload to: ${uploadUrl}")

        MultipartEntityBuilder entityBuilder = MultipartEntityBuilder.create()
            .addTextBody("apiKey", pushlinkPublisher.apiKey)
            .addBinaryBody("apk", appFile)

        decorateWithOptionalProperties(entityBuilder)

        int lastProgress = 0
        Logger loggerForCallback = logger
        ProgressHttpEntityWrapper.ProgressCallback progressCallback = new ProgressHttpEntityWrapper.ProgressCallback() {

            @Override
            void progress(float progress) {
                int progressInt = (int)progress
                if (progressInt > lastProgress) {
                    lastProgress = progressInt
                    if (progressInt % 5 == 0) {
                        progressLogger.progress(progressInt + "% uploaded")
                        loggerForCallback.info(progressInt + "% uploaded")
                    }
                }
            }

        }

        httpPost.setEntity(new ProgressHttpEntityWrapper(entityBuilder.build(), progressCallback))

        logger.info("Request: " + httpPost.getRequestLine().toString())

        HttpResponse response = httpClient.execute(httpPost)

        logger.debug("Response status code: " + response.getStatusLine().getStatusCode())

        if (response.getStatusLine().getStatusCode() != HttpStatus.SC_CREATED
                && response.getStatusLine().getStatusCode() != HttpStatus.SC_OK)
        {
            parseResponseAndThrowError(response)
        }
        else {
            logger.lifecycle("Application uploaded successfully.")
            if (response.getEntity() && response.getEntity().getContentLength() > 0) {
                InputStreamReader reader = new InputStreamReader(response.getEntity().content)

                try {
                    uploadResponse = IOUtils.toString(reader)
                }
                catch (Exception e) {
                    logger.error("Error while parsing JSON response: " + e.toString())
                }
                reader.close()
                if (uploadResponse) {
                    logger.lifecycle("Upload response: " + uploadResponse.toString())
                }
            }
            progressLogger.completed()
        }
    }

    private void parseResponseAndThrowError(HttpResponse response) {
        if (response.getEntity()?.getContentLength() > 0) {
            logger.debug("Response Content-Type: " + response.getFirstHeader("Content-type").getValue())
            InputStreamReader reader = new InputStreamReader(response.getEntity().content)

            try {
                uploadResponse = IOUtils.readFully(reader)
            } catch (Exception e) {
                logger.debug("Error while parsing JSON response: " + e.toString())
            } finally {
                reader.close()
            }

            if (uploadResponse) {
                logger.debug("Upload response: " + uploadResponse.toString())

                if (uploadResponse.status && uploadResponse.status.equals("error") && uploadResponse.message) {
                    logger.error("Error response from Pushlink: " + uploadResponse.message.toString())
                    throw new IllegalStateException("File upload failed: " + uploadResponse.message.toString() + " - Status: " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase())
                }
                if (uploadResponse.errors?.credentials) {
                    if (uploadResponse.errors.credentials instanceof ArrayList) {
                        ArrayList credentialsError = uploadResponse.errors.credentials
                        if (!credentialsError.isEmpty()) {
                            logger.error(credentialsError.get(0).toString())
                            throw new IllegalStateException(credentialsError.get(0).toString())
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("File upload failed: " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase())
    }

    private void decorateWithOptionalProperties(MultipartEntityBuilder entityBuilder) {
        Boolean setCurrent = optionalProperty(pushlinkPublisher.setCurrent as Boolean, pushlinkPublisher.variantToSetCurrent)
        if (setCurrent) {
            entityBuilder.addTextBody("current", "true")
        }
    }

    private String optionalProperty(String property, Map<String, String> variantToProperty) {
        if(variantToProperty) {
            if(variantToProperty[variantName]) {
                property = variantToProperty[variantName]
            }
        }
        return property
    }

    private String optionalProperty(Boolean property, Map<String, Boolean> variantToProperty) {
        if(variantToProperty) {
            if(variantToProperty[variantName]) {
                property = variantToProperty[variantName]
            }
        }
        return property
    }

    private String getApiKey() {
        String apiKey = pushlinkPublisher.apiKey
        if (pushlinkPublisher.variantToApiKey) {
            if (pushlinkPublisher.variantToApiKey[variantName]) {
                apiKey = pushlinkPublisher.variantToApiKey[variantName]
            }
        }
        return apiKey
    }


}
