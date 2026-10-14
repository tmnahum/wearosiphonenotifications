package dev.tmn.watchtest.bridge

enum class ConnectionPhase(val title: String, val detail: String) {
    DISCONNECTED("Disconnected", "Tap Connect to receive iPhone notifications."),
    LOADING("Loading saved state", "Getting ready to connect…"),
    CONNECTING("Connecting", "Reaching your paired iPhone…"),
    DISCOVERING("Setting up notifications", "Bluetooth is connected. Preparing the notification service…"),
    SUBSCRIBING("Setting up notifications", "Enabling iPhone notification delivery…"),
    CONNECTED("Connected", "Receiving iPhone notifications."),
    RETRYING("Reconnecting", "The connection dropped. Retrying automatically…"),
    ERROR("Connection needs attention", "Open Connection for details."),
}
