package net.vulkanmod.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;
import org.lwjgl.system.SharedLibrary;

import java.nio.ByteBuffer;

import static org.lwjgl.system.APIUtil.apiCreateLibrary;
import static org.lwjgl.system.JNI.callPI;
import static org.lwjgl.system.JNI.callPPI;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAddress;

/**
 * Sets a variable in the <em>native</em> process environment (Java's {@code System.getenv} map is read-only), so that
 * native libraries such as the Vulkan loader see it. Used only for the development option {@code validationLayerPath}.
 */
final class ProcessEnvironment {
    private ProcessEnvironment() {}

    /** @return true if the variable was set */
    static boolean set(String name, String value) {
        switch (Platform.get()) {
            case WINDOWS -> {
                try (SharedLibrary kernel32 = apiCreateLibrary("kernel32");
                     MemoryStack stack = MemoryStack.stackPush()) {
                    long fn = kernel32.getFunctionAddress("SetEnvironmentVariableW");
                    if (fn == NULL)
                        return false;
                    return callPPI(memAddress(stack.UTF16(name)), memAddress(stack.UTF16(value)), fn) != 0;
                }
            }
            case LINUX -> {
                try (SharedLibrary libc = apiCreateLibrary("libc.so.6")) {
                    long fn = libc.getFunctionAddress("putenv");
                    if (fn == NULL)
                        return false;
                    // putenv keeps the pointer: the string must stay allocated for the life of the process.
                    ByteBuffer entry = MemoryUtil.memUTF8(name + "=" + value);
                    return callPI(memAddress(entry), fn) == 0;
                }
            }
            default -> {
                return false;
            }
        }
    }
}
