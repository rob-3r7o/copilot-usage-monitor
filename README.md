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
default. Click the widget to enable or disable either visualization. An
additional **Today: x/y** display is available in the widget menu and is
disabled by default. `x` is today's usage, derived from timestamped cumulative
quota entries in the IDE log. It subtracts the latest same-month entry from
before today; if that baseline is unavailable, it uses the first entry logged
today, so earlier usage that day is not included. The baseline is stored in IDE
settings so log rotation does not erase it. This is an estimate and may include
usage since the last logged entry before midnight. `y` is the remaining credits divided by the
working days from today through month-end, counting Monday through Friday and
including today when it is a weekday. If no working days remain, `y` is
unavailable. If all
visualizations are disabled, the widget displays **Usage hidden** so its menu
remains accessible. It reads Copilot data from `idea.log` and its rotated logs,
and writes its own rolling
`copilot-usage-monitor-0.log` alongside it. Click the widget to change the
refresh interval, from 1 second to 1 hour, or choose **Open Plugin Log** to view
the plugin's log. **Open Plugin Log** is disabled when the log file is
unavailable.
