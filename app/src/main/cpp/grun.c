/*
 * grun — minimal glibc runner for the glibc-native Termux fork.
 *
 * Hybrid model:
 *   Android Host Engine (Bionic) -> PTY (termux.c) -> $PREFIX/bin/bash
 *     -> grun -> ld-linux-aarch64.so.1 -> $PREFIX/glibc userland binary
 *
 * What grun does:
 *  1. Derives $PREFIX (env PREFIX, else /proc/self/exe dirname x2).
 *  2. Strips Bionic hooks that break ld-linux, notably LD_PRELOAD
 *     (termux-exec path rewriter is purged in this fork).
 *  3. Exports the glibc execution environment:
 *       PATH            = $PREFIX/glibc/bin:$PREFIX/bin:<old PATH>
 *       LD_LIBRARY_PATH = $PREFIX/glibc/lib:$PREFIX/glibc/lib/<triplet>
 *       GCONV_PATH      = $PREFIX/glibc/lib/gconv
 *       GLIBC_PREFIX    = $PREFIX/glibc
 *  4. Hands off via execve() to the GNU dynamic loader so standard
 *     aarch64-linux-gnu binaries run unmodified under Android 15+.
 *
 * Usage:
 *   grun [--] <program> [args...]   run a glibc program
 *   grun                            run $PREFIX/glibc/bin/bash --login
 *   grun --help                     print help
 *
 * Built twice:
 *  - Host copy: ndk-build module "grun" (Bionic, $PREFIX/bin/grun).
 *  - glibc copy: Docker toolchain cross-compiles this same file with the
 *    glibc sysroot for the bootstrap tarball (scripts/build-glibc-bootstrap.sh).
 *
 * 16 KB page size: build with -Wl,-z,max-page-size=16384
 *                   -Wl,-z,common-page-size=16384 (see Android.mk).
 */

#define _GNU_SOURCE
#define _POSIX_C_SOURCE 200809L

#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/utsname.h>
#include <unistd.h>

#ifndef GRUN_PATH_MAX
#define GRUN_PATH_MAX 4096
#endif

static void usage(const char *argv0) {
    fprintf(stderr,
        "Usage: %s [--] <program> [args...]\n"
        "       %s                (run $PREFIX/glibc/bin/bash --login)\n"
        "\n"
        "Run a $PREFIX/glibc program via the GNU dynamic loader.\n"
        "Unsets LD_PRELOAD and exports PATH/LD_LIBRARY_PATH/GCONV_PATH.\n",
        argv0, argv0);
}

static int starts_with(const char *s, const char *prefix) {
    return strncmp(s, prefix, strlen(prefix)) == 0;
}

/* dirname x2 of /proc/self/exe: .../usr/bin/grun -> .../usr */
static int prefix_from_exe(char *out, size_t out_sz) {
    char exe[GRUN_PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (n <= 0 || (size_t)n >= sizeof(exe) - 1) return -1;
    exe[n] = '\0';
    /* strip basename */
    char *slash = strrchr(exe, '/');
    if (!slash) return -1;
    *slash = '\0';
    /* strip bin/ */
    slash = strrchr(exe, '/');
    if (!slash) return -1;
    *slash = '\0';
    if (snprintf(out, out_sz, "%s", exe) < 0) return -1;
    return 0;
}

static void prepend_path(const char *add, const char *old_path, char *out, size_t out_sz) {
    if (old_path && old_path[0] != '\0')
        snprintf(out, out_sz, "%s:%s", add, old_path);
    else
        snprintf(out, out_sz, "%s", add);
}

int main(int argc, char *argv[]) {
    const char *argv0 = argc > 0 ? argv[0] : "grun";

    if (argc == 2 && (strcmp(argv[1], "--help") == 0 || strcmp(argv[1], "-h") == 0)) {
        usage(argv0);
        return 0;
    }

    /* 1. Resolve $PREFIX. */
    char prefix[GRUN_PATH_MAX];
    const char *env_prefix = getenv("PREFIX");
    if (env_prefix && env_prefix[0] == '/' && strlen(env_prefix) < sizeof(prefix)) {
        snprintf(prefix, sizeof(prefix), "%s", env_prefix);
    } else if (prefix_from_exe(prefix, sizeof(prefix)) != 0) {
        fprintf(stderr, "grun: cannot determine $PREFIX (set PREFIX env var)\n");
        return 127;
    }

    /* 2. Detect arch triplet + loader name. */
    struct utsname uts;
    const char *triplet = "aarch64-linux-gnu";
    const char *ld_name = "ld-linux-aarch64.so.1";
    if (uname(&uts) == 0 && starts_with(uts.machine, "x86_64")) {
        triplet = "x86_64-linux-gnu";
        ld_name = "ld-linux-x86-64.so.2";
    }

    char glibc[GRUN_PATH_MAX], glibc_bin[GRUN_PATH_MAX];
    char glibc_lib[GRUN_PATH_MAX], glibc_triplet_lib[GRUN_PATH_MAX];
    char loader[GRUN_PATH_MAX], gconv[GRUN_PATH_MAX];
    snprintf(glibc, sizeof(glibc), "%s/glibc", prefix);
    snprintf(glibc_bin, sizeof(glibc_bin), "%s/bin", glibc);
    snprintf(glibc_lib, sizeof(glibc_lib), "%s/lib", glibc);
    snprintf(glibc_triplet_lib, sizeof(glibc_triplet_lib), "%s/%s", glibc_lib, triplet);
    snprintf(loader, sizeof(loader), "%s/%s", glibc_lib, ld_name);
    snprintf(gconv, sizeof(gconv), "%s/gconv", glibc_lib);

    /* 3. Strip Bionic hooks. termux-exec LD_PRELOAD rewrites absolute paths
       and breaks the GNU loader, so it must never propagate. */
    unsetenv("LD_PRELOAD");

    /* 4. Export glibc execution environment. */
    char host_bin[GRUN_PATH_MAX];
    snprintf(host_bin, sizeof(host_bin), "%s/bin", prefix);

    const char *old_path = getenv("PATH");
    char new_path[GRUN_PATH_MAX * 2];
    char glibc_first[GRUN_PATH_MAX * 2];
    snprintf(glibc_first, sizeof(glibc_first), "%s:%s", glibc_bin, host_bin);
    prepend_path(glibc_first, old_path, new_path, sizeof(new_path));
    setenv("PATH", new_path, 1);

    char lib_path[GRUN_PATH_MAX * 2];
    snprintf(lib_path, sizeof(lib_path), "%s:%s", glibc_lib, glibc_triplet_lib);
    setenv("LD_LIBRARY_PATH", lib_path, 1);
    setenv("GCONV_PATH", gconv, 1);
    setenv("GLIBC_PREFIX", glibc, 1);
    /* glibc DNS on Android: the Docker toolchain patches glibc to consult
       ANDROID_DNS_MODE / net.dns properties; default to the patched mode. */
    if (!getenv("ANDROID_DNS_MODE")) setenv("ANDROID_DNS_MODE", "getprop", 0);

    /* 4b. Housewife: mark this process a child subreaper so glibc tasks
       it execs keep their orphaned grandchildren under Android's Phantom
       Process Killer pressure. The flag survives execve(). Deliberately no
       setsid() here: grun runs under the PTY controlling terminal set up by
       termux.c, and a new session would detach job control. Best effort. */
    prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0);

    /* 5. Resolve target program. */
    int arg_start = 1;
    if (arg_start < argc && strcmp(argv[arg_start], "--") == 0) arg_start++;

    const char *program;
    char default_shell[GRUN_PATH_MAX];
    char resolved[GRUN_PATH_MAX];
    if (arg_start >= argc) {
        snprintf(default_shell, sizeof(default_shell), "%s/bash", glibc_bin);
        program = default_shell;
    } else if (strchr(argv[arg_start], '/') != NULL) {
        program = argv[arg_start];
    } else {
        snprintf(resolved, sizeof(resolved), "%s/%s", glibc_bin, argv[arg_start]);
        if (access(resolved, X_OK) == 0) {
            program = resolved;
        } else {
            /* Not in glibc/bin: let PATH/exec resolve it (host stub, etc.). */
            program = argv[arg_start];
        }
    }

    /* Build loader argv: ld-linux --library-path <lib> <program> [args...] */
    int user_args = argc - arg_start - (arg_start < argc ? 1 : 0);
    if (user_args < 0) user_args = 0;

    if (access(loader, X_OK) == 0 && access(program, X_OK) == 0) {
        int n = user_args + 5; /* loader, --library-path, lib, prog, args, NULL */
        char **largv = (char **)calloc((size_t)n, sizeof(char *));
        if (!largv) {
            perror("grun: calloc");
            return 127;
        }
        int i = 0;
        largv[i++] = loader;
        largv[i++] = (char *)"--library-path";
        largv[i++] = lib_path;
        largv[i++] = (char *)program;
        for (int k = 0; k < user_args; k++)
            largv[i++] = argv[arg_start + 1 + k];
        largv[i] = NULL;
        execv(loader, largv);
        int e = errno;
        fprintf(stderr, "grun: exec \"%s\" via \"%s\" failed: %s\n",
            program, loader, strerror(e));
        free(largv);
        /* Fall through to direct exec as last resort. */
    }

    if (arg_start >= argc) {
        /* Default interactive login shell. */
        char *sh_argv[] = {(char *)program, (char *)"--login", NULL};
        execv(program, sh_argv);
        fprintf(stderr, "grun: exec \"%s\" failed: %s\n", program, strerror(errno));
        return 127;
    }

    /* Direct exec: argv[arg_start..] with argv[0] = program basename. */
    int n = user_args + 2;
    char **dargv = (char **)calloc((size_t)n, sizeof(char *));
    if (!dargv) {
        perror("grun: calloc");
        return 127;
    }
    dargv[0] = (char *)program;
    for (int k = 0; k < user_args; k++)
        dargv[1 + k] = argv[arg_start + 1 + k];
    dargv[1 + user_args] = NULL;
    execv(program, dargv);
    fprintf(stderr, "grun: exec \"%s\" failed: %s\n", program, strerror(errno));
    free(dargv);
    return 127;
}
