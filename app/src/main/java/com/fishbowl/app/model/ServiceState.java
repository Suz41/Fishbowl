package com.fishbowl.app.model;

/**
 * Standardized lifecycle states for media services managed by Fishbowl.
 */
public enum ServiceState {
    NOT_INSTALLED,
    INSTALLING,
    INSTALLED,
    STARTING,
    PROCESS_READY,
    SOCKET_READY,
    API_READY,
    RUNNING,
    STOPPING,
    STOPPED,
    UNHEALTHY,
    RECOVERING,
    FAILED,
    ERROR,
    BLOCKED,
    DISABLED,
    CRASH_LOOP_PROTECTION,
    TEST_MODE,
    UPDATING;

    public boolean isRunning() {
        return this == RUNNING || this == API_READY;
    }

    public boolean isReady() {
        return this == API_READY || this == RUNNING;
    }

    public boolean isStarting() {
        return this == STARTING || this == PROCESS_READY || this == SOCKET_READY || this == RECOVERING;
    }

    public boolean isTransitioning() {
        return this == STARTING || this == PROCESS_READY || this == SOCKET_READY ||
               this == API_READY || this == STOPPING || this == INSTALLING || this == UPDATING ||
               this == RECOVERING;
    }

    public String getDisplayStatus() {
        switch (this) {
            case RUNNING: return "Running";
            case API_READY: return "API Ready";
            case SOCKET_READY: return "Socket Ready";
            case PROCESS_READY: return "Process Ready";
            case STARTING: return "Starting";
            case RECOVERING: return "Recovering";
            case STOPPING: return "Stopping";
            case STOPPED: return "Stopped";
            case UNHEALTHY: return "Unhealthy";
            case FAILED: return "Failed";
            case ERROR: return "Error";
            case BLOCKED: return "Blocked";
            case DISABLED: return "Disabled";
            case CRASH_LOOP_PROTECTION: return "Crash Loop Protection";
            case TEST_MODE: return "Test Mode";
            case INSTALLING: return "Installing";
            case INSTALLED: return "Installed";
            case UPDATING: return "Updating";
            case NOT_INSTALLED: return "Not Installed";
            default: return name();
        }
    }

    public String getDisplayLabel() {
        return getDisplayStatus();
    }

    public String getDisplayName() {
        return getDisplayStatus();
    }
}
