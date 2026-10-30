/* ABOUTME: Genuine ImageMagick pixels, format, EXIF and concurrent batch checks. */
package ai.ultimate.tools.builtin;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ImageProcessingToolNativeTest {
    private static final String BINARY = Files.isExecutable(Path.of("/usr/bin/magick"))
            ? "/usr/bin/magick" : "/usr/bin/convert";
    @TempDir Path temp;
    Path workspace;
    ImageProcessingTool tool;
    final List<Path> scratch = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll static void requireNative() {
        boolean available = Files.isExecutable(Path.of(BINARY))
                && System.getProperty("os.name").toLowerCase().contains("linux");
        if (Boolean.parseBoolean(System.getenv("ULTIMATE_REQUIRE_IMAGE_MAGICK"))) {
            assertThat(available).as("Required Linux ImageMagick runtime must be installed").isTrue();
        }
        assumeTrue(available, "Native image tests require Linux ImageMagick; CI requires it explicitly.");
    }

    @BeforeEach void setup() throws Exception {
        Path root = Files.createDirectory(temp.resolve("managed"));
        workspace = Files.createDirectory(root.resolve("job"));
        Files.createDirectory(workspace.resolve("results"));
        var runner = new ImageProcessingTool.ProcessRunner();
        tool = new ImageProcessingTool(root, (command, directory, timeout) -> {
            scratch.add(directory);
            return runner.run(command, directory, timeout);
        }, BINARY);
        image(workspace.resolve("source.png"), 16, 12, Color.RED, "png");
        image(workspace.resolve("source.jpg"), 16, 12, Color.RED, "jpeg");
        image(workspace.resolve("mark.png"), 4, 3, Color.BLUE, "png");
    }

    private String run(List<String> inputs, String prefix, String format, int width, int height,
                       String crop, double saturation, String mark, boolean strip) {
        String result = tool.processImages(workspace.toString(), inputs, "results", prefix,
                format, width, height, crop, 100, saturation, 100, mark, strip);
        for (Path path : scratch) assertThat(path).doesNotExist();
        return result;
    }

    @Test void convertsBothJpegAndPngToRealWebp() throws Exception {
        byte[] original = Files.readAllBytes(workspace.resolve("source.jpg"));
        assertThat(run(List.of("source.jpg", "source.png"), "web", "webp", 0, 0, "", 100, "", true))
                .contains("completed", "web-01.webp", "web-02.webp");
        for (String file : List.of("web-01.webp", "web-02.webp")) {
            Path path = workspace.resolve("results").resolve(file);
            byte[] bytes = Files.readAllBytes(path);
            assertThat(new String(bytes, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
            assertThat(new String(bytes, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WEBP");
            BufferedImage decoded = decode(path);
            assertThat(decoded.getWidth()).isEqualTo(16);
            assertThat(decoded.getHeight()).isEqualTo(12);
            assertThat(new Color(decoded.getRGB(3, 3)).getRed()).isGreaterThan(240);
        }
        assertThat(Files.readAllBytes(workspace.resolve("source.jpg"))).isEqualTo(original);
    }

    @Test void cropsResizesAndChangesActualColors() throws Exception {
        BufferedImage source = new BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 12; y++) for (int x = 0; x < 16; x++) {
            source.setRGB(x, y, (x < 8 ? Color.RED : Color.GREEN).getRGB());
        }
        ImageIO.write(source, "png", workspace.resolve("split.png").toFile());
        assertThat(run(List.of("split.png"), "crop", "png", 4, 6, "8x12+8+0", 0, "", true))
                .contains("completed");
        BufferedImage output = decode(workspace.resolve("results/crop-01.png"));
        assertThat(output.getWidth()).isEqualTo(4);
        assertThat(output.getHeight()).isEqualTo(6);
        Color color = new Color(output.getRGB(2, 3));
        assertThat(color.getRed()).isEqualTo(color.getGreen()).isEqualTo(color.getBlue());
    }

    @Test void placesImageWatermarkAtBottomRight() throws Exception {
        assertThat(run(List.of("source.png"), "mark", "png", 0, 0, "", 100, "mark.png", true))
                .contains("completed");
        BufferedImage output = decode(workspace.resolve("results/mark-01.png"));
        assertThat(output.getRGB(1, 1)).isEqualTo(Color.RED.getRGB());
        assertThat(output.getRGB(15, 11)).isEqualTo(Color.BLUE.getRGB());
    }

    @Test void adjustsBrightnessHueAndPreservesResizeAspectRatio() throws Exception {
        assertThat(tool.processImages(workspace.toString(), List.of("source.png"), "results", "dark", "png",
                8, 8, "", 50, 100, 100, "", true)).contains("completed");
        BufferedImage dark = decode(workspace.resolve("results/dark-01.png"));
        assertThat(dark.getWidth()).isEqualTo(8);
        assertThat(dark.getHeight()).isEqualTo(6);
        assertThat(new Color(dark.getRGB(2, 2)).getRed()).isBetween(100, 160);
        assertThat(tool.processImages(workspace.toString(), List.of("source.png"), "results", "hue", "png",
                0, 0, "", 100, 100, 150, "", true)).contains("completed");
        Color changed = new Color(decode(workspace.resolve("results/hue-01.png")).getRGB(2, 2));
        assertThat(changed.getGreen() + changed.getBlue()).isGreaterThan(200);
        for (Path path : scratch) assertThat(path).doesNotExist();
    }

    @Test void enforcesPerCallCoderPolicy() throws Exception {
        var runner = new ImageProcessingTool.ProcessRunner();
        var checked = new ImageProcessingTool(temp.resolve("managed"), (command, directory, timeout) -> {
            if (command.contains("-ping")) {
                String input = command.get(command.indexOf("-ping") + 1);
                var forbidden = runner.run(List.of(BINARY, input, "GIF:" + directory.resolve("blocked.gif")),
                        directory, Duration.ofSeconds(5));
                assertThat(forbidden.exitCode()).as("GIF encoder must be denied by per-call policy").isNotZero();
                assertThat(forbidden.output()).containsIgnoringCase("security policy");
            }
            return runner.run(command, directory, timeout);
        }, BINARY);
        assertThat(checked.processImages(workspace.toString(), List.of("source.png"), "results", "policy", "png",
                0, 0, "", 100, 100, 100, "", true)).contains("completed");
    }

    @Test void stripsRealExifProfileButKeepsItWhenRequested() throws Exception {
        Path jpeg = workspace.resolve("source.jpg");
        byte[] original = Files.readAllBytes(jpeg);
        byte[] artist = "FixtureArtist\0".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer tiff = ByteBuffer.allocate(26 + artist.length).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8).putShort((short) 1);
        tiff.putShort((short) 0x013b).putShort((short) 2).putInt(artist.length).putInt(26).putInt(0).put(artist);
        byte[] profile = tiff.array();
        ByteArrayOutputStream fixture = new ByteArrayOutputStream();
        fixture.write(original, 0, 2);
        fixture.write(new byte[]{(byte) 0xff, (byte) 0xe1, 0, (byte) (profile.length + 8)});
        fixture.write("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
        fixture.write(profile);
        fixture.write(original, 2, original.length - 2);
        Files.write(jpeg, fixture.toByteArray());
        assertThat(run(List.of("source.jpg"), "keep", "jpeg", 0, 0, "", 100, "", false)).contains("completed");
        assertThat(run(List.of("source.jpg"), "strip", "jpeg", 0, 0, "", 100, "", true)).contains("completed");
        assertThat(new String(Files.readAllBytes(workspace.resolve("results/keep-01.jpeg")),
                StandardCharsets.ISO_8859_1)).contains("FixtureArtist");
        assertThat(new String(Files.readAllBytes(workspace.resolve("results/strip-01.jpeg")),
                StandardCharsets.ISO_8859_1)).doesNotContain("FixtureArtist", "Exif");
        assertThat(decode(workspace.resolve("results/strip-01.jpeg")).getWidth()).isEqualTo(16);
    }

    @Test void rejectsDamagedImageAndLeavesNoPartialBatch() throws Exception {
        Files.write(workspace.resolve("damaged.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        assertThat(run(List.of("source.png", "damaged.png"), "bad", "png", 0, 0, "", 100, "", true))
                .startsWith("Image Processing Error:");
        try (var files = Files.list(workspace.resolve("results"))) { assertThat(files).isEmpty(); }
    }

    @Test void acceptsMaximumBatchAndConcurrentIndependentBatches() throws Exception {
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> tool.processImages(workspace.toString(),
                    Collections.nCopies(10, "source.png"), "results", "a", "webp",
                    8, 6, "", 100, 100, 100, "", true));
            var second = threads.submit(() -> tool.processImages(workspace.toString(),
                    Collections.nCopies(10, "source.jpg"), "results", "b", "png",
                    8, 6, "", 100, 100, 100, "mark.png", true));
            assertThat(first.get()).contains("completed");
            assertThat(second.get()).contains("completed");
        }
        try (var outputs = Files.list(workspace.resolve("results"))) { assertThat(outputs.count()).isEqualTo(20); }
        for (Path path : scratch) assertThat(path).doesNotExist();
        assertThat(scratch.stream().distinct()).hasSize(2);
    }

    private BufferedImage decode(Path path) throws Exception {
        if (!path.toString().endsWith("webp")) return ImageIO.read(path.toFile());
        Path png = temp.resolve(path.getFileName() + ".png");
        Process process = new ProcessBuilder(BINARY, path.toString(), png.toString()).start();
        assertThat(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        return ImageIO.read(png.toFile());
    }

    private static void image(Path path, int width, int height, Color color, String format) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setRGB(x, y, color.getRGB());
        assertThat(ImageIO.write(image, format, path.toFile())).isTrue();
    }
}
