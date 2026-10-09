package net.vulkanmod.vulkan.shader;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.NativeResource;
import org.lwjgl.util.shaderc.ShadercIncludeResolveI;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultReleaseI;
import org.lwjgl.vulkan.VK12;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memASCII;
import static org.lwjgl.util.shaderc.Shaderc.*;

public class SPIRVUtils {
    private static final boolean DEBUG = false;
    private static final boolean OPTIMIZATIONS = true;

    private static long compiler;
    private static long options;

    //The dedicated Includer and Releaser Inner Classes used to Initialise #include Support for ShaderC
    private static final ShaderIncluder SHADER_INCLUDER = new ShaderIncluder();
    private static final ShaderReleaser SHADER_RELEASER = new ShaderReleaser();
    private static final long pUserData = 0;

    private static ObjectArrayList<String> includePaths;

    private static float time = 0.0f;

    static {
        initCompiler();
    }

    private static void initCompiler() {
        compiler = shaderc_compiler_initialize();

        if(compiler == NULL) {
            throw new RuntimeException("Failed to create shader compiler");
        }

        options = shaderc_compile_options_initialize();

        if(options == NULL) {
            throw new RuntimeException("Failed to create compiler options");
        }

        if(OPTIMIZATIONS)
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);

        if(DEBUG)
            shaderc_compile_options_set_generate_debug_info(options);

        shaderc_compile_options_set_target_env(options, shaderc_env_version_vulkan_1_2, VK12.VK_API_VERSION_1_2);
        shaderc_compile_options_set_include_callbacks(options, SHADER_INCLUDER, SHADER_RELEASER, pUserData);

        includePaths = new ObjectArrayList<>();
        addIncludePath("/assets/vulkanmod/shaders/include/");
    }

    /**
     * Adds a classpath directory (e.g. {@code /assets/vulkanmod/shaders/include/}) searched by {@code #include}.
     * <p>
     * Forge port: resources are addressed by classpath path and read with {@code getResourceAsStream}. The Fabric
     * code converted resource URLs into {@code java.nio.file.Path}s, which does not work for Forge's
     * {@code union:} jar file system URLs.
     */
    public static void addIncludePath(String path) {
        if (!path.endsWith("/"))
            path = path + "/";
        if (!includePaths.contains(path))
            includePaths.add(path);
    }

    /** Reads a classpath resource as bytes, or returns null if it does not exist. */
    static byte[] readResource(String path) {
        try (InputStream stream = SPIRVUtils.class.getResourceAsStream(path)) {
            return stream != null ? stream.readAllBytes() : null;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read shader resource " + path, e);
        }
    }

    /** Compiles a GLSL source that lives in the mod jar, e.g. {@code /assets/vulkanmod/shaders/basic/blit/blit.vsh}. */
    public static SPIRV compileShaderResource(String resourcePath, ShaderKind shaderKind) {
        byte[] bytes = readResource(resourcePath);
        if (bytes == null)
            throw new IllegalStateException("Shader resource not found: " + resourcePath);
        return compileShader(resourcePath, new String(bytes, StandardCharsets.UTF_8), shaderKind);
    }

    public static SPIRV compileShader(String filename, String source, ShaderKind shaderKind) {
        long startTime = System.nanoTime();

        // Heap buffers: the String overload encodes onto LWJGL's MemoryStack (64 KiB), which shader-pack sources exceed.
        ByteBuffer sourceText = MemoryUtil.memUTF8(source, false);
        ByteBuffer inputFileName = MemoryUtil.memUTF8(filename);
        ByteBuffer entryPoint = MemoryUtil.memUTF8("main");
        long result;
        try {
            result = shaderc_compile_into_spv(compiler, sourceText, shaderKind.kind, inputFileName, entryPoint, options);
        } finally {
            MemoryUtil.memFree(sourceText);
            MemoryUtil.memFree(inputFileName);
            MemoryUtil.memFree(entryPoint);
        }

        if(result == NULL) {
            throw new RuntimeException("Failed to compile shader " + filename + " into SPIR-V");
        }

        if(shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
            throw new RuntimeException("Failed to compile shader " + filename + " into SPIR-V:\n" + shaderc_result_get_error_message(result));
        }

        time += (System.nanoTime() - startTime) / 1000000.0f;

        return new SPIRV(result, shaderc_result_get_bytes(result));
    }

    private static SPIRV readFromStream(InputStream inputStream) {
        try {
            byte[] bytes = inputStream.readAllBytes();
            ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
            buffer.put(bytes);
            buffer.position(0);

            return new SPIRV(MemoryUtil.memAddress(buffer), buffer);
        } catch (Exception e) {
            e.printStackTrace();
        }
        throw new RuntimeException("unable to read inputStream");
    }

    public enum ShaderKind {
        VERTEX_SHADER(shaderc_glsl_vertex_shader),
        GEOMETRY_SHADER(shaderc_glsl_geometry_shader),
        TESS_CONTROL_SHADER(shaderc_glsl_tess_control_shader),
        TESS_EVALUATION_SHADER(shaderc_glsl_tess_evaluation_shader),
        FRAGMENT_SHADER(shaderc_glsl_fragment_shader),
        COMPUTE_SHADER(shaderc_glsl_compute_shader);

        private final int kind;

        ShaderKind(int kind) {
            this.kind = kind;
        }
    }

    private static class ShaderIncluder implements ShadercIncludeResolveI {

        private static final int MAX_PATH_LENGTH = 4096; //Maximum Linux/Unix Path Length

        @Override
        public long invoke(long user_data, long requested_source, int type, long requesting_source, long include_depth) {
            var requested = memASCII(requested_source);

            for (String includePath : includePaths) {
                byte[] bytes = readResource(includePath + requested);
                if (bytes == null)
                    continue;

                // Heap-allocated: shaderc reads the result after this callback returns and hands it back to
                // ShaderReleaser. (The Fabric code returned memory from an already-popped MemoryStack frame.)
                // NUL-terminated in memory (length excludes it): shaderc passes this pointer back as
                // requesting_source for nested includes, and C-string reads of it must not run off the buffer.
                ByteBuffer nameNT = MemoryUtil.memASCII(requested, true);
                ByteBuffer name = nameNT.slice(0, nameNT.remaining() - 1);
                ByteBuffer content = MemoryUtil.memAlloc(bytes.length);
                content.put(bytes).flip();
                return ShadercIncludeResult.calloc()
                        .source_name(name)
                        .content(content)
                        .user_data(user_data).address();
            }

            // Never throw out of this native upcall: shaderc would dereference a NULL result and crash the JVM.
            // shaderc's contract for a failed include is an empty source_name with the error message as content.
            String error = String.format("%s: Unable to find %s in include paths %s", memASCII(requesting_source), requested, includePaths);
            ByteBuffer name = MemoryUtil.memAlloc(1).put(0, (byte) 0).limit(0);
            ByteBuffer content = MemoryUtil.memASCII(error, false);
            return ShadercIncludeResult.calloc()
                    .source_name(name)
                    .content(content)
                    .user_data(user_data).address();
        }
    }

    //TODO: Don't actually need the Releaser at all, (MemoryStack frees this for us)
    //But ShaderC won't let us create the Includer without a corresponding Releaser, (so we need it anyway)
    private static class ShaderReleaser implements ShadercIncludeResultReleaseI {

        @Override
        public void invoke(long user_data, long include_result) {
            ShadercIncludeResult result = ShadercIncludeResult.create(include_result);
            MemoryUtil.nmemFree(MemoryUtil.memAddress(result.source_name()));
            MemoryUtil.nmemFree(MemoryUtil.memAddress(result.content()));
            result.free();
        }
    }

    public static final class SPIRV implements NativeResource {

        private final long handle;
        private ByteBuffer bytecode;

        public SPIRV(long handle, ByteBuffer bytecode) {
            this.handle = handle;
            this.bytecode = bytecode;
        }

        public ByteBuffer bytecode() {
            return bytecode;
        }

        @Override
        public void free() {
//            shaderc_result_release(handle);
            bytecode = null; // Help the GC
        }
    }

}