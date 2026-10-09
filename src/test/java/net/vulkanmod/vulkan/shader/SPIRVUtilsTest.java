package net.vulkanmod.vulkan.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SPIRVUtilsTest {
    private static final int SPIRV_MAGIC = 0x07230203;

    @Test
    void compilesSourcesLargerThanTheLwjglStack() {
        // shader packs (Complementary) produce sources far beyond LWJGL's MemoryStack; the tests' stack is 1 MiB
        String padding = "// padding\n".repeat(150_000);
        String source = "#version 450\n" + padding + "layout(location = 0) out vec4 color;\nvoid main() { color = vec4(1.0); }\n";
        try (var spirv = SPIRVUtils.compileShader("big.fsh", source, SPIRVUtils.ShaderKind.FRAGMENT_SHADER)) {
            assertEquals(SPIRV_MAGIC, spirv.bytecode().order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0));
        }
    }

    @Test
    void compilesTessellationStages() {
        String control = """
                #version 450
                layout(vertices = 3) out;
                void main() {
                    gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position;
                    gl_TessLevelOuter[0] = 1.0; gl_TessLevelOuter[1] = 1.0; gl_TessLevelOuter[2] = 1.0; gl_TessLevelInner[0] = 1.0;
                }
                """;
        String evaluation = """
                #version 450
                layout(triangles) in;
                void main() {
                    gl_Position = gl_TessCoord.x * gl_in[0].gl_Position + gl_TessCoord.y * gl_in[1].gl_Position + gl_TessCoord.z * gl_in[2].gl_Position;
                }
                """;
        try (var spirv = SPIRVUtils.compileShader("test.tcs", control, SPIRVUtils.ShaderKind.TESS_CONTROL_SHADER)) {
            assertEquals(SPIRV_MAGIC, spirv.bytecode().order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0));
        }
        try (var spirv = SPIRVUtils.compileShader("test.tes", evaluation, SPIRVUtils.ShaderKind.TESS_EVALUATION_SHADER)) {
            assertEquals(SPIRV_MAGIC, spirv.bytecode().order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0));
        }
    }
}
