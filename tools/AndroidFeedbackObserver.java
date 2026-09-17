import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Observes the unchanged, debuggable APK over an existing loopback ADB JDWP forward.
 * Uses only metadata queries and SUSPEND_NONE event requests. No application method
 * invocation, local/field-value reads, code replacement, VM suspension, or verdict
 * generation. Branch hits prove execution reached the selected tone call site;
 * they do not prove audible output, speaker routing, or ToneGenerator success.
 *
 * Compile: javac --add-modules jdk.jdi -d NEW_CLASSES_DIR AndroidFeedbackObserver.java
 * Run: java --add-modules jdk.jdi -cp NEW_CLASSES_DIR AndroidFeedbackObserver
 *      --host 127.0.0.1 --port PORT --duration-seconds 180 --output NEW_OUTPUT.jsonl
 * Create the forward separately: adb -s SERIAL forward tcp:0 jdwp:APP_PID
 * Start after app configuration; do not press Audio test during observation.
 */
public final class AndroidFeedbackObserver {
    private static final String FEEDBACK = "com.vaylith.cleanrepsmobile.feedback.AthleteFeedback";
    private static final String TONE = "com.vaylith.cleanrepsmobile.model.VerdictTone";
    private static final Map<Integer, String> BRANCHES = Map.of(
            17, "ACCEPTED", 18, "REJECTED", 19, "NEUTRAL");
    private static final String CALLBACK_PREFIX = "com.vaylith.cleanrepsmobile.MainActivity";
    private static final String VERDICT = "com.vaylith.cleanrepsmobile.api.MobileVerdictEvent";

    private final BufferedWriter output;
    private final List<EventRequest> requests = new ArrayList<>();
    private final Map<String, Integer> hits = new LinkedHashMap<>();
    private VirtualMachine vm;
    private boolean feedbackInstalled;
    private int callbackLocations;

    private AndroidFeedbackObserver(BufferedWriter output) {
        this.output = output;
        for (String tone : List.of("ACCEPTED", "REJECTED", "NEUTRAL")) hits.put(tone, 0);
    }

    private void write(String event, Map<String, ?> fields) throws IOException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event", event);
        row.put("observedAt", Instant.now().toString());
        row.putAll(fields);
        output.write(json(row));
        output.newLine();
        output.flush();
    }

    private static Map<String, Object> locationInfo(Location location) throws AbsentInformationException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("class", location.declaringType().name());
        value.put("method", location.method().name());
        value.put("signature", location.method().signature());
        value.put("source", location.sourceName());
        value.put("line", location.lineNumber());
        value.put("codeIndex", location.codeIndex());
        return value;
    }

    private void install(Location location, String kind, String tone) throws Exception {
        BreakpointRequest request = vm.eventRequestManager().createBreakpointRequest(location);
        request.setSuspendPolicy(EventRequest.SUSPEND_NONE);
        request.putProperty("kind", kind);
        request.putProperty("tone", tone);
        requests.add(request);
        request.enable();
        Map<String, Object> evidence = locationInfo(location);
        evidence.put("kind", kind);
        evidence.put("tone", tone);
        evidence.put("suspendPolicy", "NONE");
        write("breakpoint_installed", evidence);
    }

    private void installFeedback(ReferenceType type) throws Exception {
        if (feedbackInstalled || !type.name().equals(FEEDBACK)) return;
        if (!type.isPrepared()) return;
        List<Method> methods = type.methodsByName("verdict").stream()
                .filter(method -> method.argumentTypeNames().equals(List.of(TONE)))
                .toList();
        if (methods.size() != 1) throw new IllegalStateException("Expected exactly one verdict(VerdictTone) method");
        Method method = methods.get(0);
        List<Location> all = method.allLineLocations();
        List<Map<String, Object>> table = new ArrayList<>();
        for (Location location : all) table.add(locationInfo(location));
        write("feedback_line_table", Map.of("locations", table));

        List<Location> selected = new ArrayList<>();
        for (int line : List.of(17, 18, 19)) {
            Location location = all.stream()
                    .filter(item -> item.lineNumber() == line)
                    .filter(item -> {
                        try { return item.sourceName().equals("AthleteFeedback.kt"); }
                        catch (AbsentInformationException error) { return false; }
                    })
                    .min(Comparator.comparingLong(Location::codeIndex))
                    .orElseThrow(() -> new IllegalStateException("Missing exact feedback branch line " + line));
            selected.add(location);
        }
        if (selected.stream().map(Location::codeIndex).distinct().count() != 3) {
            throw new IllegalStateException("Feedback branches do not have distinct executable locations");
        }
        // Validate every branch before enabling any of its requests.
        for (Location location : selected) install(location, "feedback_branch", BRANCHES.get(location.lineNumber()));
        feedbackInstalled = true;
        write("observer_ready", Map.of("feedbackBranchLocations", 3,
                "meaning", "Execution reaching unchanged APK tone branch; no audio-output assertion"));
    }

    private void installOptionalCallback() throws Exception {
        // Kotlin may move this callback into a generated class. Only accept the
        // typed invoke(MobileVerdictEvent) method at the verified callback line.
        for (ReferenceType type : vm.allClasses()) {
            if (!type.name().startsWith(CALLBACK_PREFIX) || !type.isPrepared()) continue;
            for (Method method : type.methodsByName("invoke")) {
                if (!method.argumentTypeNames().equals(List.of(VERDICT))) continue;
                try {
                    List<Location> matches = new ArrayList<>();
                    for (Location location : method.allLineLocations()) {
                        if (location.lineNumber() == 127 && location.sourceName().equals("MainActivity.kt")) {
                            matches.add(location);
                        }
                    }
                    if (!matches.isEmpty()) {
                        install(matches.stream().min(Comparator.comparingLong(Location::codeIndex)).orElseThrow(),
                                "sse_verdict_callback", "");
                        callbackLocations++;
                    }
                } catch (AbsentInformationException unavailable) {
                    // Optional evidence only. Never substitute a nearby line.
                }
            }
        }
        write("optional_callback_probe", Map.of("installedLocations", callbackLocations,
                "requiredForFeedbackBranchObservation", false));
    }

    private boolean run(String host, int port, int durationSeconds) throws Exception {
        long started = System.nanoTime();
        long deadline = started + durationSeconds * 1_000_000_000L;
        write("observer_start", Map.of("format", "clean-reps-android-feedback-observation-v1",
                "durationLimitSeconds", durationSeconds, "suspendPolicy", "NONE",
                "readsApplicationValues", false, "invokesApplicationMethods", false,
                "changesApk", false, "audioTestMustRemainUnused", true));
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(item -> item.name().equals("com.sun.jdi.SocketAttach"))
                .findFirst().orElseThrow(() -> new IllegalStateException("JDI SocketAttach unavailable"));
        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("hostname").setValue(host);
        arguments.get("port").setValue(Integer.toString(port));
        if (arguments.containsKey("timeout")) arguments.get("timeout").setValue("5000");
        vm = connector.attach(arguments);
        write("attached", Map.of("vmName", vm.name(), "vmVersion", vm.version()));

        ClassPrepareRequest prepare = vm.eventRequestManager().createClassPrepareRequest();
        prepare.addClassFilter(FEEDBACK);
        prepare.setSuspendPolicy(EventRequest.SUSPEND_NONE);
        requests.add(prepare);
        prepare.enable();
        List<ReferenceType> existing = vm.classesByName(FEEDBACK);
        if (existing.size() > 1) throw new IllegalStateException("Ambiguous feedback class loaders");
        for (ReferenceType type : existing) installFeedback(type);
        installOptionalCallback();

        boolean disconnected = false;
        long classDeadline = Math.min(deadline, System.nanoTime() + 30_000_000_000L);
        while (System.nanoTime() < deadline && !disconnected) {
            if (!feedbackInstalled && System.nanoTime() > classDeadline) {
                throw new IllegalStateException("Feedback class not prepared within 30 seconds; launch/configure APK first");
            }
            long remainingMs = Math.max(1, (deadline - System.nanoTime()) / 1_000_000L);
            EventSet events = vm.eventQueue().remove(Math.min(1000, remainingMs));
            if (events == null) continue;
            if (events.suspendPolicy() != EventRequest.SUSPEND_NONE) {
                throw new IllegalStateException("Unexpected suspending event set; disposing observer");
            }
            for (Event event : events) {
                if (event instanceof ClassPrepareEvent prepared) {
                    installFeedback(prepared.referenceType());
                } else if (event instanceof BreakpointEvent reached) {
                    Map<String, Object> evidence = locationInfo(reached.location());
                    String kind = (String) reached.request().getProperty("kind");
                    String tone = (String) reached.request().getProperty("tone");
                    evidence.put("kind", kind);
                    evidence.put("tone", tone);
                    evidence.put("threadId", reached.thread().uniqueID());
                    evidence.put("elapsedMs", (System.nanoTime() - started) / 1_000_000L);
                    evidence.put("suspendPolicy", "NONE");
                    write("location_reached", evidence);
                    if ("feedback_branch".equals(kind)) hits.compute(tone, (key, value) -> value + 1);
                } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                    disconnected = true;
                }
            }
            // SUSPEND_NONE events require no resume operation.
        }
        write("observer_summary", Map.of("feedbackInstalled", feedbackInstalled,
                "branchHits", hits, "callbackLocations", callbackLocations,
                "disconnected", disconnected, "audioOutputVerified", false));
        return feedbackInstalled;
    }

    private void detach() throws IOException {
        if (vm == null) return;
        try {
            if (!requests.isEmpty()) vm.eventRequestManager().deleteEventRequests(requests);
        } catch (RuntimeException ignored) {
            // Dispose also removes all remaining requests if target is alive.
        } finally {
            try { vm.dispose(); }
            catch (VMDisconnectedException ignored) { }
        }
        write("observer_detached", Map.of("applicationTerminateRequested", false));
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        if (args.length % 2 != 0) throw new IllegalArgumentException("Expected --name value pairs");
        Set<String> allowed = Set.of("--host", "--port", "--duration-seconds", "--output");
        for (int i = 0; i < args.length; i += 2) {
            if (!allowed.contains(args[i]) || options.putIfAbsent(args[i], args[i + 1]) != null) {
                throw new IllegalArgumentException("Unknown or duplicate argument");
            }
        }
        String host = options.getOrDefault("--host", "127.0.0.1");
        if (!host.equals("127.0.0.1") && !host.equals("::1")) {
            throw new IllegalArgumentException("Observer accepts literal loopback host only");
        }
        int port = Integer.parseInt(options.getOrDefault("--port", "0"));
        int duration = Integer.parseInt(options.getOrDefault("--duration-seconds", "180"));
        if (port < 1 || port > 65535 || duration < 1 || duration > 600 || !options.containsKey("--output")) {
            throw new IllegalArgumentException("Require port 1..65535, duration 1..600, and NEW output file");
        }
        Path output = Path.of(options.get("--output")).toAbsolutePath();
        int exit = 0;
        var channel = Files.newByteChannel(output,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try (BufferedWriter writer = new BufferedWriter(Channels.newWriter(channel, StandardCharsets.UTF_8))) {
            AndroidFeedbackObserver observer = new AndroidFeedbackObserver(writer);
            try {
                if (!observer.run(host, port, duration)) exit = 2;
            } catch (Exception error) {
                exit = 2;
                observer.write("observer_error", Map.of("errorType", error.getClass().getSimpleName(),
                        "message", error.getMessage() == null ? "" : error.getMessage()));
            } finally {
                try { observer.detach(); }
                catch (Exception error) {
                    exit = 2;
                    observer.write("detach_error", Map.of("errorType", error.getClass().getSimpleName()));
                }
            }
        }
        System.out.println("{\"observerExit\":" + exit + ",\"output\":" + quote(output.toString()) + "}");
        if (exit != 0) System.exit(exit);
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String string) return quote(string);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            List<String> pairs = new ArrayList<>();
            map.forEach((key, item) -> pairs.add(quote(key.toString()) + ":" + json(item)));
            return "{" + String.join(",", pairs) + "}";
        }
        if (value instanceof Iterable<?> sequence) {
            List<String> items = new ArrayList<>();
            for (Object item : sequence) items.add(json(item));
            return "[" + String.join(",", items) + "]";
        }
        throw new IllegalArgumentException("Unsupported JSON type");
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 32) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }
}
