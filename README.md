# Copilot Credit Usage

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

Enable **Copilot Credit Usage** in the status bar if it is hidden. The widget
shows used premium credits, entitlement, and the remaining percentage. Click
the widget to change the refresh interval, from 1 second to 1 hour.
