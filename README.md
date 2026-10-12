# Copilot Usage Monitor

CLion plugin that displays GitHub Copilot premium credit usage in the status bar.
It reads the Copilot messages already written to the local `idea.log`; it does
not make network requests or access Copilot credentials.

## Build

Use the bundled Gradle wrapper:

```bash
./gradlew buildPlugin
```

The distributable ZIP is written to `build/distributions/`.

For local development with the configured CLion installation:

```bash
./gradlew runIde
```

The sandbox log is under
`build/idea-sandbox/<sandbox-name>/log/idea.log`.

## Usage

Enable **Copilot Usage Monitor** in the status bar if it is hidden. The widget
shows used premium credits and the percentage of credits used, both enabled by
default. Click the widget to enable or disable either visualization. If both are
disabled, the widget displays **Usage hidden** so its menu remains accessible. It reads
Copilot data from `idea.log` and writes its own rolling
`copilot-usage-monitor-0.log` alongside it. Click the widget to change the
refresh interval, from 1 second to 1 hour, or choose **Open Plugin Log** to view
the plugin's log. **Open Plugin Log** is disabled when the log file is
unavailable.
