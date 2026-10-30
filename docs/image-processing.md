# Managed image processing

`ImageProcessingTool` is a Spring component implementing `UltimateTool` and is
discovered by the existing registry. Its `processImages` method is available to
Spring AI tools without changes to the provider or orchestrator.

Install a maintained ImageMagick package with JPEG, PNG and WebP support. The
tool looks for `magick`, then Unix `convert`, on the service's `PATH`. It never
uses Windows' unrelated `convert.exe`. No installation or download is performed
by the tool. A missing executable produces an error String.

## Workspace and parameters

An administrator must prepare a directory below
`~/ultimate-managed-workspaces`, with inputs and an existing output subdirectory,
owned by the service account. For example:

```text
~/ultimate-managed-workspaces/catalog/
  photo.jpg
  logo.png
  results/
```

Example tool arguments:

```json
{
  "workspacePath": "/home/ultimate/ultimate-managed-workspaces/catalog",
  "inputPaths": ["photo.jpg"],
  "outputDirectory": "results",
  "outputPrefix": "catalog",
  "outputFormat": "webp",
  "width": 800,
  "height": 600,
  "crop": "",
  "brightness": 100,
  "saturation": 100,
  "hue": 100,
  "watermarkPath": "logo.png",
  "stripMetadata": true
}
```

The result is `results/catalog-01.webp`. A batch produces sequential names in
input order. Existing names are never overwritten. Inputs are unchanged.
Conversion accepts JPEG, PNG or WebP contents, independently of filename suffix.
Only single still images are accepted. The output format is `jpeg`, `png` or
`webp`; `jpeg` outputs use `.jpeg`.

Operations run in this order: crop, resize, color adjustment, image watermark,
metadata stripping, encoding. Crop uses `widthxheight+x+y` coordinates before
resizing and must fit every input. Both resize dimensions may be zero to retain
the size; otherwise both are required, and the image fits inside the requested
box while preserving aspect ratio. Brightness, saturation and hue are finite
percentages from 0 to 200, with 100 unchanged. An empty watermark path disables
watermarking; otherwise the watermark's native size and alpha are composited at
the bottom right. `stripMetadata=true` removes EXIF and other profiles.

## Bounds and failure behavior

- At most ten inputs and two concurrent batches per tool instance; excess calls
  receive a retryable error immediately.
- At most 20 MiB per file, 100 MiB total staged input including the watermark,
  8192 pixels on either axis and 40 million pixels per image.
- A batch has a 90 second deadline. Native processes additionally use one thread,
  128 MiB memory, no mapped cache, at most 256 MiB disk cache, and a 30 second
  ImageMagick time limit. Output capture is drained and capped at 8192 bytes.
- Native commands use an argument list, fixed operation names and staged file
  names with explicit format coders. Per-call policy disables delegates, filters
  and all image coders except JPEG, PNG and WebP (INFO is write-only for probing).
- Canonical paths must remain within the managed workspace. Dot components,
  symlink inputs and symlink directory components are rejected. Directory-relative
  no-follow filesystem operations protect copying and publication. Filesystems
  without Java `SecureDirectoryStream` are refused, including typical Windows
  filesystems; Linux is the supported deployment and native test environment.
- All transformations complete before final publication. If publication fails,
  the tool attempts to remove only files created by that batch. Preexisting
  output files are preserved. Private temporary files are removed in `finally`
  on success, failure, timeout or interruption. Cleanup failure is reported.

The managed root is a service-level boundary, matching the existing audio tool;
it is not tenant authorization. Administrators must isolate mutually untrusted
users with separate service accounts/containers and workspace roots. Processes
with the same operating-system identity must not mutate active workspaces.
ImageMagick must remain patched; these controls do not replace OS isolation.

The method is synchronous and rejects calls on Reactor nonblocking threads before
starting work. Call it through a blocking-capable worker. Spring AI's typed tool
callback is the invocation contract. The existing agent executor's single-String
reflection dispatcher does not support multi-argument tools, including the existing
audio tool, and is not changed by this feature.

## Verification

From `server/`, run `./mvnw test` and `./mvnw compile` using Java 21. Unit tests
cover path confinement, overwrite/rollback, cleanup, malformed inputs, bounded
execution, interruption, concurrency and the model-visible Spring schema. Native
tests check decoded image pixels, formats, dimensions, EXIF removal, damaged
images and two concurrent maximum-size batches.

On Linux with ImageMagick installed, native tests run as part of `test`. Set
`ULTIMATE_REQUIRE_IMAGE_MAGICK=true` to make a missing native runtime fail the
suite; CI installs the runtime and sets this flag. Without Linux ImageMagick,
the native suite explicitly skips rather than claiming real transformation proof.
The filesystem contract suite runs on Linux. Model availability or a mocked
tool response does not demonstrate a real local Ollama round trip.

For the opt-in local model round trip, have an already installed tool-capable
model running in Ollama, then run `ULTIMATE_IMAGE_OLLAMA_TEST=true ./mvnw
-Dtest=ImageProcessingToolOllamaTest test`. The default model is `qwen3:4b`;
`ULTIMATE_IMAGE_OLLAMA_MODEL` and `ULTIMATE_IMAGE_OLLAMA_URL` can select an
existing local model and loopback endpoint (`host.docker.internal` is also
accepted for container testing). The test requests a real tool call, executes
the model's arguments through Spring's callback, decodes the resulting image
and sends the tool result back to the model. A second test exercises the existing
streaming `OllamaProvider` and checks its actual native image output. These tests
download no models.

ImageMagick references: [security policy](https://imagemagick.org/security-policy/),
[command options](https://imagemagick.org/command-line-options/),
[filename processing](https://imagemagick.org/command-line-processing/).
