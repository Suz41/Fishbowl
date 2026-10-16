package com.fishbowl.app.model;

import java.io.Serializable;

/**
 * Metadata definition specifying runtime requirements, ports, directories,
 * and lifecycle contracts for a managed media service.
 */
public class ServiceManifest implements Serializable {

    private final String id;
    private final String displayName;
    private final String category;
    private final String version;
    private final String description;
    private final int defaultPort;
    private final String healthCheckPath;
    private final String webUiPath;
    private final String installDir;
    private final String executableOrDllPath;
    private final String configDir;
    private final String dataDir;
    private final String logDir;
    private final String runtimeDependency;
    private final boolean isCore;
    private final long storageRequiredMb;
    private final int minAndroidSdk;

    public ServiceManifest(
            String id,
            String displayName,
            String category,
            String version,
            String description,
            int defaultPort,
            String healthCheckPath,
            String webUiPath,
            String installDir,
            String executableOrDllPath,
            String configDir,
            String dataDir,
            String logDir,
            String runtimeDependency,
            boolean isCore,
            long storageRequiredMb,
            int minAndroidSdk) {
        this.id = id;
        this.displayName = displayName;
        this.category = category;
        this.version = version;
        this.description = description;
        this.defaultPort = defaultPort;
        this.healthCheckPath = healthCheckPath;
        this.webUiPath = webUiPath;
        this.installDir = installDir;
        this.executableOrDllPath = executableOrDllPath;
        this.configDir = configDir;
        this.dataDir = dataDir;
        this.logDir = logDir;
        this.runtimeDependency = runtimeDependency;
        this.isCore = isCore;
        this.storageRequiredMb = storageRequiredMb;
        this.minAndroidSdk = minAndroidSdk;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getCategory() { return category; }
    public String getVersion() { return version; }
    public String getDescription() { return description; }
    public int getDefaultPort() { return defaultPort; }
    public String getHealthCheckPath() { return healthCheckPath; }
    public String getWebUiPath() { return webUiPath; }
    public String getWebPath() { return webUiPath; }
    public String getInstallDir() { return installDir; }
    public String getExecutableOrDllPath() { return executableOrDllPath; }
    public String getConfigDir() { return configDir; }
    public String getDataDir() { return dataDir; }
    public String getLogDir() { return logDir; }
    public String getRuntimeDependency() { return runtimeDependency; }
    public boolean isCore() { return isCore; }
    public long getStorageRequiredMb() { return storageRequiredMb; }
    public int getMinAndroidSdk() { return minAndroidSdk; }

    public String getLocalUrl() {
        return "http://127.0.0.1:" + defaultPort + webUiPath;
    }

    public String getHealthUrl() {
        return "http://127.0.0.1:" + defaultPort + healthCheckPath;
    }
}
