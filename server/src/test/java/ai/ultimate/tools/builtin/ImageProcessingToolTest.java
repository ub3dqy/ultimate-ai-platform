/* ABOUTME: Filesystem, resource, lifecycle and process contracts for image batches. */
package ai.ultimate.tools.builtin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledOnOs(OS.LINUX)
class ImageProcessingToolTest {
    @TempDir Path temp;

    private Fixture fixture() throws IOException {
        Path root = Files.createDirectories(temp.resolve("managed"));
        Path workspace = Files.createDirectories(root.resolve("job"));
        Files.createDirectories(workspace.resolve("results"));
        Path image = workspace.resolve("input with spaces;$.png");
        ImageIO.write(new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
        Stub runner = new Stub(Files.readAllBytes(image));
        return new Fixture(workspace, runner, new ImageProcessingTool(root, runner, "convert"));
    }

    private static String run(Fixture f, List<String> inputs, String prefix) {
        return f.tool.processImages(f.workspace.toString(), inputs, "results", prefix, "png",
                0, 0, "", 100, 100, 100, "", true);
    }

    private static String run(Fixture f, String prefix) {
        return run(f, List.of("input with spaces;$.png"), prefix);
    }

    @Test
    void preservesInputsAndPublishesOnlyFinalFiles() throws Exception {
        Fixture f = fixture();
        byte[] original = Files.readAllBytes(f.workspace.resolve("input with spaces;$.png"));
        assertThat(run(f, "safe")).contains("Image processing completed", "results/safe-01.png");
        assertThat(Files.readAllBytes(f.workspace.resolve("input with spaces;$.png"))).isEqualTo(original);
        assertThat(Files.readAllBytes(f.workspace.resolve("results/safe-01.png"))).isEqualTo(original);
        assertThat(f.runner.commands).hasSize(3);
        assertThat(f.runner.commands.stream().flatMap(List::stream)).doesNotContain("sh", "bash", "-c");
        assertScratchGone(f.runner);
    }

    @Test
    void validatesBeforeStartingANativeProcess() throws Exception {
        Fixture f = fixture();
        assertThat(run(f, List.of(), "safe")).contains("1-10");
        assertThat(run(f, Collections.nCopies(11, "input with spaces;$.png"), "safe")).contains("1-10");
        assertThat(run(f, "../unsafe")).contains("Invalid output prefix");
        assertThat(f.tool.processImages(null, List.of("x"), "results", "safe", "png",
                0, 0, "", 100, Double.NaN, 100, "", true)).contains("finite");
        for (String format : List.of("pdf", "svg", "JPEG", "webp:extra", "")) {
            assertThat(f.tool.processImages(f.workspace.toString(), List.of("x"), "results", "safe", format,
                    0, 0, "", 100, 100, 100, "", true)).contains("format");
        }
        for (String crop : List.of("0x1+0+0", "8x6-1+0", "8x6+0+0 -write x", "99999x1+0+0")) {
            assertThat(f.tool.processImages(f.workspace.toString(), List.of("x"), "results", "safe", "png",
                    0, 0, crop, 100, 100, 100, "", true)).contains("Crop");
        }
        assertThat(f.runner.commands).isEmpty();
    }

    @Test
    void rejectsEscapesSymlinksDirectoriesAndAbsolutePaths() throws Exception {
        Fixture f = fixture();
        Path outside = Files.writeString(temp.resolve("outside.png"), "must not read");
        Files.createSymbolicLink(f.workspace.resolve("link.png"), outside);
        Files.createSymbolicLink(f.workspace.resolve("inner.png"), f.workspace.resolve("input with spaces;$.png"));
        Files.createSymbolicLink(f.workspace.resolve("outside-dir"), temp);
        for (String input : List.of("../outside.png", outside.toString(), "link.png", "inner.png", "results")) {
            assertThat(run(f, List.of(input), "blocked")).startsWith("Image Processing Error:");
        }
        assertThat(f.tool.processImages(f.workspace.toString(), List.of("input with spaces;$.png"),
                "outside-dir", "safe", "png", 0, 0, "", 100, 100, 100, "", true))
                .contains("escapes");
        assertThat(f.runner.commands).isEmpty();
        assertThat(outside).hasContent("must not read");
    }

    @Test
    void rejectsForeignWorkspacesUnknownContentsAndOversizedFiles() throws Exception {
        Fixture f = fixture();
        assertThat(f.tool.processImages(temp.toString(), List.of("outside.png"), "results", "safe", "png",
                0, 0, "", 100, 100, 100, "", true)).contains("managed root");
        Files.writeString(f.workspace.resolve("pretend.png"), "not a PNG");
        assertThat(run(f, List.of("pretend.png"), "safe")).contains("contents");
        try (var file = new java.io.RandomAccessFile(f.workspace.resolve("huge.png").toFile(), "rw")) {
            file.setLength(ImageProcessingTool.MAX_FILE_BYTES + 1);
        }
        assertThat(run(f, List.of("huge.png"), "safe")).contains("20 MiB");
        assertThat(f.runner.commands).isEmpty();
        assertScratchGone(f.runner);
    }

    @Test
    void refusesOverwriteAndRollsBackOnlyFilesFromThisBatch() throws Exception {
        Fixture f = fixture();
        Files.writeString(f.workspace.resolve("results/batch-02.png"), "existing");
        assertThat(run(f, Collections.nCopies(2, "input with spaces;$.png"), "batch"))
                .startsWith("Image Processing Error:");
        assertThat(f.workspace.resolve("results/batch-01.png")).doesNotExist();
        assertThat(f.workspace.resolve("results/batch-02.png")).hasContent("existing");
        assertScratchGone(f.runner);
    }

    @Test
    void rejectsOutputDirectorySwapDuringProcessing() throws Exception {
        Fixture f = fixture();
        f.runner.beforeTransform = () -> {
            Files.move(f.workspace.resolve("results"), f.workspace.resolve("previous-results"));
            Files.createSymbolicLink(f.workspace.resolve("results"), temp);
        };
        assertThat(run(f, "safe")).startsWith("Image Processing Error:");
        assertThat(temp.resolve("safe-01.png")).doesNotExist();
        assertScratchGone(f.runner);
    }

    @Test
    void discardsWholeBatchOnNativeFailureAndReleasesSlot() throws Exception {
        Fixture f = fixture();
        f.runner.failure = new ImageProcessingTool.NativeResult(2, "x".repeat(100_000), false);
        assertThat(run(f, "bad")).contains("exit 2").hasSizeLessThan(700);
        assertThat(f.workspace.resolve("results/bad-01.png")).doesNotExist();
        assertScratchGone(f.runner);
        f.runner.failure = new ImageProcessingTool.NativeResult(-1, "", true);
        assertThat(run(f, "timed")).contains("runtime limit");
        assertScratchGone(f.runner);
        f.runner.failure = null;
        assertThat(run(f, "retry")).contains("completed");
        assertScratchGone(f.runner);
    }

    @Test
    void rejectsHugeOrMultipleFramesAndOutOfBoundsCrop() throws Exception {
        Fixture f = fixture();
        for (String size : List.of("99999 1 1", "8192 8192 1", "8 6 2", "0 6 1")) {
            f.runner.dimensions = size;
            assertThat(run(f, "bad")).startsWith("Image Processing Error:");
        }
        f.runner.dimensions = "8 6 1";
        assertThat(f.tool.processImages(f.workspace.toString(), List.of("input with spaces;$.png"),
                "results", "bad", "png", 0, 0, "8x6+1+0", 100, 100, 100, "", true))
                .contains("fit inside");
        assertScratchGone(f.runner);
    }

    @Test
    void preservesInterruptionAndCleansScratch() throws Exception {
        Fixture f = fixture();
        ImageProcessingTool tool = new ImageProcessingTool(temp.resolve("managed"), (args, scratch, timeout) -> {
            f.runner.scratch.add(scratch);
            throw new InterruptedException("test");
        }, "convert");
        try {
            assertThat(tool.processImages(f.workspace.toString(), List.of("input with spaces;$.png"),
                    "results", "safe", "png", 0, 0, "", 100, 100, 100, "", true)).contains("Interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertScratchGone(f.runner);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void refusesToBlockReactorEventLoop() throws Exception {
        Fixture f = fixture();
        String result = Mono.fromCallable(() -> run(f, "safe"))
                .subscribeOn(Schedulers.parallel()).block(Duration.ofSeconds(5));
        assertThat(result).contains("blocking-capable worker");
        assertThat(f.runner.commands).isEmpty();
    }

    @Test
    void limitsConcurrentCallsWithoutSharingScratchOrBlockingQueue() throws Exception {
        Fixture f = fixture();
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        ImageProcessingTool tool = new ImageProcessingTool(temp.resolve("managed"), (args, scratch, timeout) -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test deadline");
            return f.runner.run(args, scratch, timeout);
        }, "convert");
        Fixture concurrent = new Fixture(f.workspace, f.runner, tool);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> run(concurrent, "first"));
            var second = threads.submit(() -> run(concurrent, "second"));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(run(concurrent, "third")).contains("already running");
            } finally {
                release.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS)).contains("completed");
            assertThat(second.get(10, TimeUnit.SECONDS)).contains("completed");
        }
        assertThat(f.runner.scratch.stream().distinct()).hasSize(2);
        assertScratchGone(f.runner);
    }

    @Test
    void drainsLargeProcessOutputAndKillsTimedOutChild() throws Exception {
        var runner = new ImageProcessingTool.ProcessRunner();
        var output = runner.run(List.of("/bin/sh", "-c", "head -c 100000 /dev/zero"), temp, Duration.ofSeconds(5));
        assertThat(output.exitCode()).isZero();
        assertThat(output.output()).hasSize(8192);
        Path pid = temp.resolve("child.pid");
        long start = System.nanoTime();
        var timed = runner.run(List.of("/bin/sh", "-c", "sleep 30 & echo $! > child.pid; wait"),
                temp, Duration.ofMillis(200));
        assertThat(timed.timedOut()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        long child = Long.parseLong(Files.readString(pid).strip());
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(() ->
                ProcessHandle.of(child).map(p -> !p.isAlive()).orElse(true));
    }

    private static void assertScratchGone(Stub runner) {
        for (Path path : runner.scratch) assertThat(path).doesNotExist();
    }

    private record Fixture(Path workspace, Stub runner, ImageProcessingTool tool) { }

    @FunctionalInterface private interface BeforeTransform { void run() throws IOException; }

    private static class Stub implements ImageProcessingTool.NativeRunner {
        final byte[] image;
        final List<List<String>> commands = Collections.synchronizedList(new ArrayList<>());
        final List<Path> scratch = Collections.synchronizedList(new ArrayList<>());
        volatile ImageProcessingTool.NativeResult failure;
        volatile String dimensions = "8 6 1";
        BeforeTransform beforeTransform;
        Stub(byte[] image) { this.image = image; }

        @Override public ImageProcessingTool.NativeResult run(List<String> command, Path directory, Duration timeout)
                throws IOException {
            commands.add(command);
            scratch.add(directory);
            assertThat(timeout).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(90));
            if (failure != null) return failure;
            if (command.contains("-ping")) return new ImageProcessingTool.NativeResult(0, dimensions, false);
            if (beforeTransform != null) beforeTransform.run();
            String output = command.getLast();
            Files.write(Path.of(output.substring(output.indexOf(':') + 1)), image);
            return new ImageProcessingTool.NativeResult(0, "", false);
        }
    }
}
