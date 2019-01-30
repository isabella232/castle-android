/*
 * Copyright (c) 2017 Castle
 */

package io.castle.android;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import io.castle.android.api.model.Event;
import io.castle.android.api.model.IdentifyEvent;
import io.castle.android.api.model.ScreenEvent;
import io.castle.android.queue.EventQueue;

public class Castle {
    public static final String clientIdHeaderName = "X-Castle-Client-Id";

    private static Castle instance;
    private Application application;
    private String identifier;
    private CastleConfiguration configuration;
    private EventQueue eventQueue;
    private StorageHelper storageHelper;
    private CastleActivityLifecycleCallbacks activityLifecycleCallbacks;
    private CastleComponentCallback componentCallbacks;

    private Castle(Application application, CastleConfiguration castleConfiguration) {
        setup(application, castleConfiguration);
    }

    private void setup(Application application, CastleConfiguration configuration) {
        Context context = application.getApplicationContext();
        this.storageHelper = new StorageHelper(context);
        this.configuration = configuration;
        this.identifier = storageHelper.getDeviceId();
        this.eventQueue = new EventQueue(context);
        this.application = application;
    }

    private void registerLifeCycleCallbacks(Application application) {
        activityLifecycleCallbacks = new CastleActivityLifecycleCallbacks();
        application.registerActivityLifecycleCallbacks(activityLifecycleCallbacks);

        componentCallbacks = new CastleComponentCallback();
        application.registerComponentCallbacks(componentCallbacks);

        // Get the current version.
        PackageInfo packageInfo = Utils.getPackageInfo(application);
        String currentVersion = packageInfo.versionName;
        int currentBuild = packageInfo.versionCode;

        // Get the previous recorded version.
        String previousVersion = storageHelper.getVersion();
        int previousBuild = storageHelper.getBuild();

        // Check and track Application Installed or Application Updated.
        if (previousBuild == -1) {
            Map<String, String> properties = new HashMap<>();
            properties.put("version", currentVersion);
            properties.put("build", "" + currentBuild);
            track("Application Installed", properties);
        } else if (currentBuild != previousBuild) {
            Map<String, String> properties = new HashMap<>();
            properties.put("version", currentVersion);
            properties.put("build", "" + currentBuild);
            properties.put("previous_version", previousVersion);
            properties.put("previous_build", "" + previousBuild);
            track("Application Updated", properties);
        }

        // Track Application Opened.
        Map<String, String> properties = new HashMap<>();
        properties.put("version", currentVersion);
        properties.put("build", "" + currentBuild);
        track("Application Opened", properties);

        flush();

        // Update the recorded version.
        storageHelper.setVersion(currentVersion);
        storageHelper.setBuild(currentBuild);
    }

    public static void configure(Application application, CastleConfiguration castleConfiguration) {
        if (instance == null) {
            if (castleConfiguration.publishableKey() == null || !castleConfiguration.publishableKey().startsWith("pk_")) {
                throw new RuntimeException("You must provide a valid Castle publishable key when initializing the SDK.");
            }
            instance = new Castle(application, castleConfiguration);
            instance.registerLifeCycleCallbacks(application);
        }
    }

    public static void configure(Application application, String publishableKey) {
        if (instance == null) {
            instance = new Castle(application, new CastleConfiguration.Builder().publishableKey(publishableKey).build());
            instance.registerLifeCycleCallbacks(application);
        }
    }

    public static void configure(Application application, String publishableKey, CastleConfiguration configuration) {
        if (instance == null) {
            instance = new Castle(application, new CastleConfiguration.Builder(configuration).publishableKey(publishableKey).build());
            instance.registerLifeCycleCallbacks(application);
        }
    }

    public static void configure(Application application) {
        try {
            ApplicationInfo applicationInfo =
                    application.getPackageManager()
                            .getApplicationInfo(application.getPackageName(),
                                    PackageManager.GET_META_DATA);
            Bundle bundle = applicationInfo.metaData;
            String publishableKey = bundle.getString("castle_publishable_key");

            configure(application, new CastleConfiguration.Builder().publishableKey(publishableKey).build());
        } catch (PackageManager.NameNotFoundException e) {
            CastleLogger.e("Failed to load meta-data, NameNotFound: " + e.getMessage());
        } catch (NullPointerException e) {
            CastleLogger.e("Failed to load meta-data, NullPointer: " + e.getMessage());
        }
    }

    public static void track(String event, Map<String, String> properties) {
        if (event == null || event.isEmpty() || properties == null) {
            return;
        }
        track(new Event(event, properties));
    }

    public static void track(String event) {
        if (event == null || event.isEmpty()) {
            return;
        }
        track(new Event(event));
    }

    private static void track(Event event) {
        CastleLogger.d("Tracking event " + Utils.getGsonInstance().toJson(event));
        instance.eventQueue.add(event);
        if (instance.eventQueue.needsFlush()) {
            flush();
        }
    }

    public static void identify(String userId) {
        if (userId == null || userId.isEmpty()) {
            return;
        }
        Castle.userId(userId);
        track(new IdentifyEvent(userId));
        flush();
    }

    public static void identify(String userId, Map<String, String> traits) {
        if (userId == null || userId.isEmpty() || traits == null) {
            return;
        }
        Castle.userId(userId);
        track(new IdentifyEvent(userId, traits));
        flush();
    }

    private static void userId(String userId) {
        instance.storageHelper.setIdentity(userId);
    }

    public static String userId() {
        return instance.storageHelper.getIdentity();
    }

    public static void reset() {
        Castle.flush();
        Castle.userId(null);
    }

    public static void screen(String name, Map<String, String> properties) {
        if (name == null || name.isEmpty() || properties == null) {
            return;
        }
        track(new ScreenEvent(name, properties));
    }

    public static void screen(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        track(new ScreenEvent(name));
    }

    public static void screen(Activity activity) {
        track(new ScreenEvent(activity));
    }

    public static String publishableKey() {
        return instance.configuration.publishableKey();
    }

    public static String clientId() {
        return instance.identifier;
    }

    public static CastleConfiguration configuration() {
        return instance.configuration;
    }

    public static void flush() {
        try {
            instance.eventQueue.flush();
        } catch (IOException exception) {
            CastleLogger.e("Unable to flush queue", exception);
        }
    }

    public static boolean flushIfNeeded(String url) {
        // Flush if request to whitelisted url
        if (isUrlWhiteListed(url)) {
            flush();
            return true;
        }
        return false;
    }

    public static Map<String, String> headers(String url) {
        Map<String, String> headers = new HashMap<>();

        if (isUrlWhiteListed(url)) {
            headers.put(clientIdHeaderName, Castle.clientId());
        }

        return headers;
    }

    public static CastleInterceptor castleInterceptor() {
        return new CastleInterceptor();
    }

    static boolean isUrlWhiteListed(String urlString) {
        try {
            URL url = new URL(urlString);
            String baseUrl = url.getProtocol() + "://" + url.getHost() + "/";

            if (Castle.configuration().baseURLWhiteList() != null && !Castle.configuration().baseURLWhiteList().isEmpty()) {
                if (Castle.configuration().baseURLWhiteList().contains(baseUrl)) {
                    return true;
                }
            }
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public static int queueSize() {
        return instance.eventQueue.size();
    }

    static boolean isFlushingQueue() {
        return instance.eventQueue.isFlushing();
    }

    public static void destroy(Application application) {
        if (instance != null) {
            instance.eventQueue.destroy();
            instance.unregisterLifeCycleCallbacks(application);
            instance.unregisterComponentCallbacks(application);
            instance = null;
        }
    }

    static int getCurrentBuild() {
        return instance.storageHelper.getBuild();
    }

    static String getCurrentVersion() {
        return instance.storageHelper.getVersion();
    }

    private void unregisterLifeCycleCallbacks(Application application) {
        application.unregisterActivityLifecycleCallbacks(activityLifecycleCallbacks);
    }

    private void unregisterComponentCallbacks(Application application) {
        application.unregisterComponentCallbacks(componentCallbacks);
    }

    public static io.castle.android.api.model.Context createContext() {
        return io.castle.android.api.model.Context.create(instance.application.getApplicationContext());
    }
}
