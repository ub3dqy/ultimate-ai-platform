/* ABOUTME: Bounded ImageMagick batch transformations in managed workspaces. */
package ai.ultimate.tools.builtin;

import ai.ultimate.tools.UltimateTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
public class ImageProcessingTool implements UltimateTool {
    static final int MAX_FILES = 10;
    static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    static final long MAX_BATCH_BYTES = 100L * 1024 * 1024;
    static final int MAX_DIMENSION = 8192;
    static final long MAX_PIXELS = 40_000_000;
    private static final Duration BATCH_TIMEOUT = Duration.ofSeconds(90);
    private static final Set<OpenOption> READ_OPTIONS = Set.of(
            StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    private static final Set<OpenOption> CREATE_OPTIONS = Set.of(
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    private static final String POLICY = """
            <policymap>
              <policy domain="delegate" rights="none" pattern="*"/>
              <policy domain="filter" rights="none" pattern="*"/>
              <policy domain="coder" rights="none" pattern="*"/>
              <policy domain="coder" rights="read|write" pattern="{JPEG,PNG,WEBP}"/>
              <policy domain="coder" rights="write" pattern="INFO"/>
              <policy domain="path" rights="none" pattern="@*"/>
              <policy domain="resource" name="memory" value="128MiB"/>
              <policy domain="resource" name="map" value="0"/>
              <policy domain="resource" name="disk" value="256MiB"/>
              <policy domain="resource" name="thread" value="1"/>
              <policy domain="resource" name="width" value="8192"/>
              <policy domain="resource" name="height" value="8192"/>
              <policy domain="resource" name="time" value="30"/>
              <policy domain="resource" name="list-length" value="2"/>
            </policymap>
            """;

    private final Path managedRoot;
    private final NativeRunner runner;
    private final String binary;
    private final Semaphore slots = new Semaphore(2);

    public ImageProcessingTool() {
        this(Path.of(System.getProperty("user.home"), "ultimate-managed-workspaces"),
                new ProcessRunner(), locateBinary());
    }

    ImageProcessingTool(Path managedRoot, NativeRunner runner, String binary) {
        this.managedRoot = managedRoot.toAbsolutePath().normalize();
        this.runner = runner;
        this.binary = binary;
    }

    @Tool(description = "Transform 1-10 local JPEG, PNG or WebP images using ImageMagick. "
            + "Use for conversion, batch resize, crop, brightness/saturation/hue adjustment, "
            + "image watermarking and EXIF removal. Inputs are preserved. Final files are "
            + "outputPrefix-01.format etc. in an existing output directory; no overwrites. "
            + "Temporary files are removed on success/failure. Returns relative paths or a bounded "
            + "error String. Requires a blocking-capable worker and managed local workspace.")
    public String processImages(
            @ToolParam(description = "Absolute workspace below ~/ultimate-managed-workspaces")
            String workspacePath,
            @ToolParam(description = "List of 1-10 relative input file paths, e.g. [photos/a.jpg]")
            List<String> inputPaths,
            @ToolParam(description = "Existing relative output directory, e.g. results")
            String outputDirectory,
            @ToolParam(description = "Output prefix: 1-60 ASCII letters, digits, hyphen or underscore")
            String outputPrefix,
            @ToolParam(description = "Output format: jpeg, png or webp")
            String outputFormat,
            @ToolParam(description = "Resize width 1-8192, or 0 to retain size. Both dimensions must be set or 0.")
            int width,
            @ToolParam(description = "Resize height 1-8192; aspect ratio preserved within these bounds")
            int height,
            @ToolParam(description = "Crop BEFORE resize: widthxheight+x+y, e.g. 100x80+0+0; empty for none")
            String crop,
            @ToolParam(description = "Brightness percentage 0-200; 100 unchanged")
            double brightness,
            @ToolParam(description = "Saturation percentage 0-200; 100 unchanged, 0 grayscale")
            double saturation,
            @ToolParam(description = "Hue percentage 0-200; 100 unchanged")
            double hue,
            @ToolParam(description = "Relative JPEG/PNG/WebP watermark file, bottom-right; empty for none")
            String watermarkPath,
            @ToolParam(description = "Remove EXIF and other profiles/metadata when true")
            boolean stripMetadata) {
        if (Schedulers.isInNonBlockingThread()) {
            return "Image Processing Error: Run this tool on a blocking-capable worker.";
        }
        if (!slots.tryAcquire()) {
            return "Image Processing Error: Two image batches are already running; retry later.";
        }
        Path scratch = null;
        boolean cleaned;
        String result;
        try {
            validate(inputPaths, outputPrefix, outputFormat, width, height, crop,
                    brightness, saturation, hue);
            if (binary == null) {
                throw new IOException("ImageMagick executable is unavailable; install magick (or convert on Unix).");
            }
            Path root = managedRoot.toRealPath();
            Path workspace = Path.of(workspacePath).toRealPath();
            require(workspace.startsWith(root) && !workspace.equals(root),
                    "Workspace must be a directory below the managed root.");
            require(Files.isDirectory(workspace), "Workspace must be a directory.");
            Path outputRelative = relative(outputDirectory);
            Path output = workspace.resolve(outputRelative).toRealPath();
            require(output.startsWith(workspace), "Output directory escapes the workspace.");
            Object outputKey = Files.readAttributes(output, BasicFileAttributes.class).fileKey();
            long deadline = System.nanoTime() + BATCH_TIMEOUT.toNanos();
            scratch = Files.createTempDirectory("ultimate-image-").toRealPath();
            require(!scratch.toString().matches(".*[\\[\\]*?{}\\r\\n].*"),
                    "Temporary directory contains unsupported filename characters.");
            Files.writeString(scratch.resolve("policy.xml"), POLICY);
            List<Path> staged = new ArrayList<>();
            long total = 0;
            for (String input : inputPaths) {
                Path copy = scratch.resolve("input-" + staged.size());
                total += stage(workspace, input, copy, MAX_BATCH_BYTES - total);
                require(total <= MAX_BATCH_BYTES, "Batch exceeds 100 MiB.");
                staged.add(copy);
            }
            Path watermark = null;
            if (watermarkPath != null && !watermarkPath.isBlank()) {
                watermark = scratch.resolve("watermark");
                total += stage(workspace, watermarkPath, watermark, MAX_BATCH_BYTES - total);
                require(total <= MAX_BATCH_BYTES, "Batch exceeds 100 MiB including watermark.");
                probe(watermark, scratch, deadline);
            }
            List<Path> completed = new ArrayList<>();
            for (Path input : staged) {
                Dimensions original = probe(input, scratch, deadline);
                validateCrop(crop, original);
                Path image = Files.createFile(scratch.resolve("result-" + completed.size()
                        + "." + outputFormat)).toRealPath();
                List<String> command = command();
                command.add(coder(input) + ":" + input);
                if (crop != null && !crop.isBlank()) {
                    command.addAll(List.of("-crop", crop, "+repage"));
                }
                if (width > 0) {
                    command.addAll(List.of("-resize", width + "x" + height));
                }
                command.addAll(List.of("-modulate", decimal(brightness) + ","
                        + decimal(saturation) + "," + decimal(hue)));
                if (watermark != null) {
                    command.addAll(List.of(coder(watermark) + ":" + watermark,
                            "-gravity", "southeast", "-compose", "over", "-composite"));
                }
                if (stripMetadata) {
                    command.add("-strip");
                }
                command.add(outputFormat.toUpperCase(Locale.ROOT) + ":" + image);
                execute(command, scratch, deadline);
                require(Files.size(image) > 0 && Files.size(image) <= MAX_FILE_BYTES,
                        "Native output is empty or exceeds 20 MiB.");
                require(coder(image).equals(outputFormat.toUpperCase(Locale.ROOT)),
                        "Native output format differs from the request.");
                probe(image, scratch, deadline);
                completed.add(image);
            }
            // No final file is created until every transformation has succeeded.
            result = publish(workspace, outputRelative, outputKey, outputPrefix, outputFormat, completed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = "Image Processing Error: Interrupted; unfinished batch was discarded.";
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            result = "Image Processing Error: " + message.substring(0, Math.min(message.length(), 600));
        } finally {
            cleaned = cleanup(scratch);
            slots.release();
        }
        if (!cleaned) {
            return result + " Cleanup Error: Temporary image files could not all be removed.";
        }
        return result;
    }

    private static void validate(List<String> inputs, String prefix, String format,
                                 int width, int height, String crop,
                                 double brightness, double saturation, double hue) {
        require(inputs != null && !inputs.isEmpty() && inputs.size() <= MAX_FILES,
                "Provide 1-10 input images.");
        require(prefix != null && prefix.matches("[A-Za-z0-9_-]{1,60}"), "Invalid output prefix.");
        require(Set.of("jpeg", "png", "webp").contains(format == null ? "" : format),
                "Output format must be jpeg, png or webp.");
        require(width >= 0 && height >= 0 && width <= MAX_DIMENSION && height <= MAX_DIMENSION
                && ((width == 0) == (height == 0)) && (long) width * height <= MAX_PIXELS,
                "Resize dimensions must both be 0 or 1-8192, with at most 40 million pixels.");
        require(crop == null || crop.isBlank() || crop.matches("[1-9][0-9]{0,3}x[1-9][0-9]{0,3}\\+[0-9]{1,4}\\+[0-9]{1,4}"),
                "Crop must be widthxheight+x+y with positive dimensions and nonnegative offsets.");
        for (double value : new double[]{brightness, saturation, hue}) {
            require(Double.isFinite(value) && value >= 0 && value <= 200,
                    "Color percentages must be finite values between 0 and 200.");
        }
    }

    private static void validateCrop(String crop, Dimensions original) {
        if (crop == null || crop.isBlank()) {
            return;
        }
        String[] n = crop.split("[x+]");
        long width = Long.parseLong(n[0]), height = Long.parseLong(n[1]);
        require(width + Long.parseLong(n[2]) <= original.width
                        && height + Long.parseLong(n[3]) <= original.height,
                "Crop rectangle must fit inside every input image.");
    }

    private static String decimal(double number) {
        return java.math.BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
    }

    private static Path relative(String value) {
        require(value != null && !value.isBlank() && value.length() <= 512,
                "A relative path of 1-512 characters is required.");
        Path path = Path.of(value);
        require(!path.isAbsolute(), "Absolute input/output paths are not allowed.");
        for (Path part : path) {
            require(!part.toString().equals("..") && !part.toString().equals("."),
                    "Dot path components are not allowed.");
        }
        return path;
    }

    private static long stage(Path workspace, String name, Path destination, long remaining) throws IOException {
        Path input = relative(name);
        require(workspace.resolve(input).toRealPath().startsWith(workspace),
                "Input escapes the workspace.");
        try (DirectoryHandle parent = openDirectory(workspace, input.getParent())) {
            Path basename = input.getFileName();
            BasicFileAttributes attrs = parent.stream.getFileAttributeView(basename,
                    BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
            require(attrs.isRegularFile() && attrs.size() > 0 && attrs.size() <= MAX_FILE_BYTES,
                    "Input must be a nonempty regular image of at most 20 MiB.");
            require(attrs.size() <= remaining, "Batch exceeds 100 MiB including watermark.");
            try (SeekableByteChannel source = parent.stream.newByteChannel(basename, READ_OPTIONS);
                 SeekableByteChannel target = Files.newByteChannel(destination, CREATE_OPTIONS)) {
                return copy(source, target, Math.min(MAX_FILE_BYTES, remaining));
            }
        }
    }

    private static long copy(SeekableByteChannel source, SeekableByteChannel target) throws IOException {
        return copy(source, target, MAX_FILE_BYTES);
    }

    private static long copy(SeekableByteChannel source, SeekableByteChannel target, long limit) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);
        long count = 0;
        while (source.read(buffer) != -1) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.io.InterruptedIOException("Interrupted while copying an image.");
            }
            count += buffer.position();
            require(count <= limit, "Image or batch exceeds its byte limit while being copied.");
            buffer.flip();
            while (buffer.hasRemaining()) {
                target.write(buffer);
            }
            buffer.clear();
        }
        return count;
    }

    private static String coder(Path file) throws IOException {
        byte[] signature;
        try (InputStream stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            signature = stream.readNBytes(12);
        }
        if (signature.length >= 8 && signature[0] == (byte) 0x89 && signature[1] == 'P'
                && signature[2] == 'N' && signature[3] == 'G' && signature[4] == 13
                && signature[5] == 10 && signature[6] == 26 && signature[7] == 10) {
            return "PNG";
        }
        if (signature.length >= 3 && signature[0] == (byte) 0xff
                && signature[1] == (byte) 0xd8 && signature[2] == (byte) 0xff) {
            return "JPEG";
        }
        if (signature.length == 12 && new String(signature, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(signature, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) {
            return "WEBP";
        }
        throw new IllegalArgumentException("Only JPEG, PNG and WebP image contents are allowed.");
    }

    private List<String> command() {
        return new ArrayList<>(List.of(binary, "-limit", "thread", "1", "-limit", "memory", "128MiB",
                "-limit", "map", "0", "-limit", "disk", "256MiB", "-limit", "time", "30"));
    }

    private Dimensions probe(Path image, Path scratch, long deadline) throws IOException, InterruptedException {
        List<String> command = command();
        command.addAll(List.of("-ping", coder(image) + ":" + image, "-format", "%w %h %n", "info:"));
        String response = execute(command, scratch, deadline).strip();
        require(response.matches("[0-9]{1,5} [0-9]{1,5} 1"),
                "Input must contain exactly one decodable still image.");
        String[] dimensions = response.split(" ");
        int width = Integer.parseInt(dimensions[0]), height = Integer.parseInt(dimensions[1]);
        require(width > 0 && height > 0 && width <= MAX_DIMENSION && height <= MAX_DIMENSION
                && (long) width * height <= MAX_PIXELS, "Image dimensions exceed the allowed limit.");
        return new Dimensions(width, height);
    }

    private String execute(List<String> command, Path scratch, long deadline)
            throws IOException, InterruptedException {
        long remaining = deadline - System.nanoTime();
        require(remaining > 0, "Batch exceeded its 90 second runtime limit.");
        NativeResult result = runner.run(List.copyOf(command), scratch, Duration.ofNanos(remaining));
        require(!result.timedOut, "Image processing exceeded its runtime limit.");
        require(result.exitCode == 0, "ImageMagick could not process the image (exit " + result.exitCode + ").");
        return result.output;
    }

    private static String publish(Path workspace, Path relative, Object expectedKey, String prefix,
                                  String format, List<Path> images) throws IOException {
        List<Path> created = new ArrayList<>();
        try (DirectoryHandle output = openDirectory(workspace, relative)) {
            Object actualKey = output.stream.getFileAttributeView(BasicFileAttributeView.class)
                    .readAttributes().fileKey();
            require(expectedKey != null && expectedKey.equals(actualKey),
                    "Output directory changed during processing.");
            try {
                for (int i = 0; i < images.size(); i++) {
                    Path name = Path.of(prefix + "-" + String.format(Locale.ROOT, "%02d", i + 1) + "." + format);
                    try (SeekableByteChannel target = output.stream.newByteChannel(name, CREATE_OPTIONS)) {
                        created.add(name);
                        try (SeekableByteChannel source = Files.newByteChannel(images.get(i), READ_OPTIONS)) {
                            copy(source, target);
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                boolean rollbackFailed = false;
                for (Path name : created) {
                    try {
                        output.stream.deleteFile(name);
                    } catch (IOException rollback) {
                        rollbackFailed = true;
                        e.addSuppressed(rollback);
                    }
                }
                if (rollbackFailed) {
                    throw new IOException("Publication failed; some files created by this batch could not be removed.", e);
                }
                throw e;
            }
        }
        return "Image processing completed: " + String.join(", ", created.stream()
                .map(relative::resolve).map(Path::toString).toList());
    }

    // Directory-relative, no-follow operations prevent symlink swaps during input copying/publication.
    // Refuse unsupported filesystems instead of silently weakening the confinement boundary.
    private static DirectoryHandle openDirectory(Path workspace, Path relative) throws IOException {
        List<SecureDirectoryStream<Path>> opened = new ArrayList<>();
        try {
            DirectoryStream<Path> root = Files.newDirectoryStream(workspace);
            if (!(root instanceof SecureDirectoryStream<Path> secure)) {
                root.close();
                throw new IOException("Secure directory operations are unavailable on this filesystem.");
            }
            opened.add(secure);
            if (relative != null) {
                for (Path part : relative) {
                    secure = secure.newDirectoryStream(part, LinkOption.NOFOLLOW_LINKS);
                    opened.add(secure);
                }
            }
            return new DirectoryHandle(secure, opened);
        } catch (IOException | RuntimeException e) {
            for (SecureDirectoryStream<Path> stream : opened.reversed()) {
                try {
                    stream.close();
                } catch (IOException close) {
                    e.addSuppressed(close);
                }
            }
            throw e;
        }
    }

    private record DirectoryHandle(SecureDirectoryStream<Path> stream,
                                   List<SecureDirectoryStream<Path>> opened) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (SecureDirectoryStream<Path> directory : opened.reversed()) {
                try {
                    directory.close();
                } catch (IOException e) {
                    failure = e;
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static boolean cleanup(Path directory) {
        if (directory == null) {
            return true;
        }
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void require(boolean condition, String error) {
        if (!condition) {
            throw new IllegalArgumentException(error);
        }
    }

    private static String locateBinary() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        for (String name : windows ? List.of("magick.exe") : List.of("magick", "convert")) {
            for (String directory : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
                Path candidate = Path.of(directory).resolve(name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toAbsolutePath().toString();
                }
            }
        }
        return null;
    }

    private record Dimensions(int width, int height) { }

    record NativeResult(int exitCode, String output, boolean timedOut) { }

    @FunctionalInterface
    interface NativeRunner {
        NativeResult run(List<String> command, Path scratch, Duration timeout)
                throws IOException, InterruptedException;
    }

    static final class ProcessRunner implements NativeRunner {
        @Override
        public NativeResult run(List<String> command, Path scratch, Duration timeout)
                throws IOException, InterruptedException {
            ProcessBuilder builder = new ProcessBuilder(command).directory(scratch.toFile()).redirectErrorStream(true);
            Map<String, String> environment = builder.environment();
            environment.keySet().removeIf(key -> key.startsWith("MAGICK_"));
            environment.put("MAGICK_CONFIGURE_PATH", scratch.toString());
            environment.put("MAGICK_TEMPORARY_PATH", scratch.toString());
            environment.put("HOME", scratch.toString());
            Process process = builder.start();
            try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
                var output = readers.submit(() -> capture(process.getInputStream()));
                try {
                    process.getOutputStream().close();
                    if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                        return new NativeResult(-1, "", true);
                    }
                    return new NativeResult(process.exitValue(), output.get(2, TimeUnit.SECONDS), false);
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                    throw new IOException("Unable to drain native process output.", e);
                } finally {
                    process.descendants().forEach(child -> child.destroyForcibly());
                    // Let the parent reap terminated children before forcing its own exit.
                    try {
                        if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        process.destroyForcibly();
                    } finally {
                        process.destroyForcibly();
                        try {
                            process.getInputStream().close();
                        } finally {
                            process.getErrorStream().close();
                            readers.shutdownNow();
                        }
                    }
                }
            }
        }

        private static String capture(InputStream input) throws IOException {
            try (input) {
                byte[] bytes = new byte[4096];
                var captured = new java.io.ByteArrayOutputStream();
                int count;
                while ((count = input.read(bytes)) != -1) {
                    captured.write(bytes, 0, Math.min(count, 8192 - captured.size()));
                }
                return captured.toString(StandardCharsets.UTF_8);
            }
        }
    }
}
