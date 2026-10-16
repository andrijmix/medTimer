# Ktor (io.ktor.util.debug.IntellijIdeaDebugDetector) looks at java.lang.management to detect an attached IntelliJ debugger. That package does not
# exist on Android. The lookup is wrapped in try/catch (Throwable) and falls back to "no debugger", so the missing classes are harmless.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# NEVER add an SLF4J binding (slf4j-android, logback-android, ...) to this module. The MCP SDK and Ktor log through SLF4J; without a binding
# SLF4J is a no-op, which is what keeps request details of the user's medication data out of logcat.
