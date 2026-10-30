/* ABOUTME: Spring discovery and model-visible image tool schema. */
package ai.ultimate.tools;

import ai.ultimate.tools.builtin.ImageProcessingTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class ImageProcessingToolRegistryTest {
    @Test void autoDiscoversToolAndExposesTypedModelSchema() {
        try (var context = new AnnotationConfigApplicationContext(ImageProcessingTool.class, ToolRegistry.class)) {
            ToolRegistry registry = context.getBean(ToolRegistry.class);
            assertThat(registry.getAll()).hasSize(1).first().isInstanceOf(ImageProcessingTool.class);
            var callbacks = ToolCallbacks.from(registry.asArray());
            assertThat(callbacks).hasSize(1);
            assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("processImages");
            assertThat(callbacks[0].getToolDefinition().inputSchema())
                    .contains("inputPaths", "array", "watermarkPath", "stripMetadata", "brightness");
        }
    }
}
