package com.gregor.jprototerm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Reads the X11 pointer location directly via libX11 ({@code XQueryPointer}). Unlike AWT's
 * {@code MouseInfo}, this never calls {@code XSetErrorHandler}, so it doesn't trip GDK's
 * "XSetErrorHandler called with a GDK error trap pushed" warning when JavaFX's GTK backend is
 * already up. Returns {@code null} when not on X11 or libX11 can't be loaded.
 */
final class X11Pointer {
    private X11Pointer() {
    }

    /** {@code {x, y}} of the pointer in X root-window (virtual screen) space, or {@code null}. */
    static int[] query() {
        try (Arena arena = Arena.ofConfined()) {
            Linker linker = Linker.nativeLinker();
            SymbolLookup x11 = SymbolLookup.libraryLookup("libX11.so.6", arena);
            MethodHandle openDisplay = linker.downcallHandle(x11.find("XOpenDisplay").orElseThrow(),
                    FunctionDescriptor.of(ADDRESS, ADDRESS));
            MethodHandle defaultRootWindow = linker.downcallHandle(x11.find("XDefaultRootWindow").orElseThrow(),
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS));
            MethodHandle queryPointer = linker.downcallHandle(x11.find("XQueryPointer").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG,
                            ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            MethodHandle closeDisplay = linker.downcallHandle(x11.find("XCloseDisplay").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));

            MemorySegment display = (MemorySegment) openDisplay.invoke(MemorySegment.NULL);
            if (display.address() == 0) {
                return null;
            }
            try {
                long root = (long) defaultRootWindow.invoke(display);
                MemorySegment rootReturn = arena.allocate(JAVA_LONG);
                MemorySegment childReturn = arena.allocate(JAVA_LONG);
                MemorySegment rootX = arena.allocate(JAVA_INT);
                MemorySegment rootY = arena.allocate(JAVA_INT);
                MemorySegment winX = arena.allocate(JAVA_INT);
                MemorySegment winY = arena.allocate(JAVA_INT);
                MemorySegment mask = arena.allocate(JAVA_INT);
                int onSameScreen = (int) queryPointer.invoke(display, root,
                        rootReturn, childReturn, rootX, rootY, winX, winY, mask);
                if (onSameScreen == 0) {
                    return null;
                }
                return new int[] { rootX.get(JAVA_INT, 0), rootY.get(JAVA_INT, 0) };
            } finally {
                closeDisplay.invoke(display);
            }
        } catch (Throwable ignored) {
            // Not X11, libX11 missing, or the call failed — caller falls back to the primary screen.
            return null;
        }
    }
}
