package com.gregor.jprototerm;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeForeignAccess;

import java.lang.foreign.Linker;

/**
 * Registers the FFM downcall descriptors used by {@link LinuxPty} for GraalVM releases that
 * do not consume {@code foreign.downcalls} from reachability-metadata.json. Mirrors
 * jlibghostty's {@code GhosttyForeignRegistrationFeature}.
 *
 * <p>Wired in via {@code --features=com.gregor.jprototerm.PtyForeignRegistrationFeature}
 * in the gluonfx compiler args.
 */
public final class PtyForeignRegistrationFeature implements Feature {
    @Override
    public void duringSetup(DuringSetupAccess access) {
        downcall(LinuxPty.FD_INT_INT);          // posix_openpt / grantpt / unlockpt / close
        downcall(LinuxPty.FD_PTSNAME_R);        // ptsname_r
        downcall(LinuxPty.FD_RW);               // read / write
        downcall(LinuxPty.FD_KILL);             // kill
        downcall(LinuxPty.FD_WAITPID);          // waitpid
        downcall(LinuxPty.FD_SPAWN);            // posix_spawnp
        downcall(LinuxPty.FD_FA_INIT);          // *_init / *_destroy
        downcall(LinuxPty.FD_FA_ADDCLOSE);      // posix_spawn_file_actions_addclose
        downcall(LinuxPty.FD_FA_ADDDUP2);       // posix_spawn_file_actions_adddup2
        downcall(LinuxPty.FD_FA_ADDOPEN);       // posix_spawn_file_actions_addopen
        downcall(LinuxPty.FD_FA_ADDCHDIR);      // posix_spawn_file_actions_addchdir_np
        downcall(LinuxPty.FD_ATTR_SETFLAGS);    // posix_spawnattr_setflags

        // ioctl(int, unsigned long, ...) is variadic; register with the same linker option.
        RuntimeForeignAccess.registerForDowncall(LinuxPty.FD_IOCTL, Linker.Option.firstVariadicArg(2));
    }

    private static void downcall(java.lang.foreign.FunctionDescriptor descriptor) {
        RuntimeForeignAccess.registerForDowncall(descriptor);
    }
}
