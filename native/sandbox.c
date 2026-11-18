/* SPDX-License-Identifier: Apache-2.0 */
/* Derived from Sound Software, revision 9b94052 (Happenmass). */
#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <linux/landlock.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <linux/audit.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

static void fail(const char* reason) {
    perror(reason);
    exit(125);
}

static void denied_probe_syscall(int signal, siginfo_t* info, void* context) {
    (void)signal;
    (void)context;
    _exit(info->si_syscall == __NR_landlock_create_ruleset ? 126 : 127);
}

static volatile sig_atomic_t process_group;

static void stop_worker(int signal) {
    (void)signal;
    if (process_group > 0) kill(-process_group, SIGKILL);
}

static void reap_orphaned_children(void) {
    for (;;) {
        const pid_t finished = waitpid(-1, NULL, WNOHANG);
        if (finished > 0) continue;
        if (finished < 0 && errno == ECHILD) return;
        if (finished < 0 && errno == EINTR) continue;
        if (finished < 0) fail("check orphaned agent children");
        // Some Android kernels omit /proc/<tid>/children. Use only accessible stat entries
        // whose kernel-reported parent is this launcher. Never signal by executable name.
        DIR* proc = opendir("/proc");
        if (!proc) fail("list orphaned agent children");
        const int owner = getpid();
        struct dirent* entry;
        int stopped = 0;
        while ((entry = readdir(proc))) {
            char* end;
            long pid = strtol(entry->d_name, &end, 10);
            if (pid <= 0 || *end) continue;
            char path[64];
            snprintf(path, sizeof(path), "/proc/%ld/stat", pid);
            int stat = open(path, O_RDONLY | O_CLOEXEC);
            if (stat < 0) continue;
            char line[1024];
            ssize_t size = read(stat, line, sizeof(line) - 1);
            close(stat);
            if (size <= 0) continue;
            line[size] = '\0';
            char* rest = strrchr(line, ')');
            int parent;
            if (!rest || sscanf(rest + 1, " %*c %d", &parent) != 1 || parent != owner) continue;
            kill((pid_t)pid, SIGKILL);
            stopped = 1;
        }
        closedir(proc);
        if (!stopped) fail("enumerate orphaned agent children");
        while (waitpid(-1, NULL, 0) < 0 && errno == EINTR) {}
    }
}

static void supervise(void) {
    sigset_t signals, previous;
    sigemptyset(&signals);
    sigaddset(&signals, SIGTERM);
    sigaddset(&signals, SIGHUP);
    if (sigprocmask(SIG_BLOCK, &signals, &previous) < 0) fail("block stop signals");
    struct sigaction stop = {.sa_handler = stop_worker};
    sigemptyset(&stop.sa_mask);
    if (sigaction(SIGTERM, &stop, NULL) < 0 || sigaction(SIGHUP, &stop, NULL) < 0) fail("install stop handler");
    pid_t launcher_parent = getppid();
    if (prctl(PR_SET_PDEATHSIG, SIGTERM) < 0) fail("launcher parent-death signal");
    if (getppid() != launcher_parent) exit(125);

    // Codex creates a separate process group for each MCP server. The launcher adopts
    // those descendants when the backend exits and terminates them by pid.
    if (prctl(PR_SET_CHILD_SUBREAPER, 1) < 0) fail("install child supervisor");

    pid_t launcher = getpid();
    pid_t child = fork();
    if (child < 0) fail("fork agent process");
    if (child == 0) {
        if (setpgid(0, 0) < 0) fail("set agent process group");
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) < 0) fail("worker parent-death signal");
        if (getppid() != launcher) _exit(125);
        struct sigaction normal = {.sa_handler = SIG_DFL};
        sigemptyset(&normal.sa_mask);
        if (sigaction(SIGTERM, &normal, NULL) < 0 || sigaction(SIGHUP, &normal, NULL) < 0)
            fail("reset worker stop handlers");
        if (sigprocmask(SIG_SETMASK, &previous, NULL) < 0) fail("unblock worker signals");
        return;
    }

    process_group = child;
    if (setpgid(child, child) < 0 && errno != EACCES && errno != ESRCH) {
        kill(child, SIGKILL);
        fail("initialize agent process group");
    }
    if (sigprocmask(SIG_SETMASK, &previous, NULL) < 0) fail("unblock launcher signals");
    siginfo_t completed;
    int result;
    do {
        result = waitid(P_PID, child, &completed, WEXITED | WNOWAIT);
    } while (result < 0 && errno == EINTR);
    // Keep the group leader's pid reserved until the remaining descendants are stopped.
    kill(-child, SIGKILL);
    process_group = 0;
    if (result < 0) fail("observe agent exit");
    int status;
    do {
        result = waitpid(child, &status, 0);
    } while (result < 0 && errno == EINTR);
    if (result < 0) fail("wait for agent");
    reap_orphaned_children();
    exit(WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status));
}

static void allow(int ruleset, const char* path, uint64_t rights) {
    int path_fd = open(path, O_PATH | O_CLOEXEC);
    if (path_fd < 0) fail(path);
    struct landlock_path_beneath_attr rule = {.allowed_access = rights, .parent_fd = path_fd};
    if (syscall(__NR_landlock_add_rule, ruleset, LANDLOCK_RULE_PATH_BENEATH, &rule, 0) < 0) fail(path);
    close(path_fd);
}

// Model-authored Node scripts have no relay or socket access.
static void restrict_script(void) {
    const unsigned deny = SECCOMP_RET_ERRNO | EPERM;
    struct sock_filter instructions[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_socket, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, deny),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_io_uring_setup, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, deny),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = {
        .len = sizeof(instructions) / sizeof(instructions[0]),
        .filter = instructions,
    };
    const struct rlimit file_size = {16 * 1024 * 1024, 16 * 1024 * 1024};
    if (setrlimit(RLIMIT_FSIZE, &file_size) < 0) fail("RLIMIT_FSIZE");
    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) < 0) fail("PR_SET_SECCOMP");
}

int main(int argc, char** argv) {
    if (argc == 2 && (!strcmp(argv[1], "--check") || !strcmp(argv[1], "--abi"))) {
        // Android's app seccomp filter can trap the capability query before the kernel handles it.
        struct sigaction action = {.sa_sigaction = denied_probe_syscall, .sa_flags = SA_SIGINFO};
        if (sigemptyset(&action.sa_mask) < 0 || sigaction(SIGSYS, &action, NULL) < 0) return 127;
        const long abi = syscall(__NR_landlock_create_ruleset, NULL, 0, LANDLOCK_CREATE_RULESET_VERSION);
        if (!strcmp(argv[1], "--abi")) {
            if (abi < 0) fail("landlock_create_ruleset version");
            printf("%ld\n", abi);
        }
        return abi >= 6 ? 0 : 125;
    }
    if (argc < 5) {
        fprintf(stderr, "usage: sandbox TASK_DIR NATIVE_LIB_DIR EXECUTABLE [ARG...]\n");
        return 125;
    }
    supervise();
    long abi = syscall(__NR_landlock_create_ruleset, NULL, 0, LANDLOCK_CREATE_RULESET_VERSION);
    if (abi < 6) fail("Landlock ABI >= 6 required");
    const uint64_t read_exec = LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_EXECUTE;
    const uint64_t all_fs = (LANDLOCK_ACCESS_FS_IOCTL_DEV << 1) - 1;
    const uint64_t job_fs = LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_READ_DIR
        | LANDLOCK_ACCESS_FS_WRITE_FILE | LANDLOCK_ACCESS_FS_MAKE_REG | LANDLOCK_ACCESS_FS_MAKE_DIR
        | LANDLOCK_ACCESS_FS_REMOVE_FILE | LANDLOCK_ACCESS_FS_REMOVE_DIR | LANDLOCK_ACCESS_FS_REFER
        | LANDLOCK_ACCESS_FS_TRUNCATE | LANDLOCK_ACCESS_FS_MAKE_SYM;
    struct landlock_ruleset_attr policy = {
        .handled_access_fs = all_fs,
        .handled_access_net = LANDLOCK_ACCESS_NET_BIND_TCP | LANDLOCK_ACCESS_NET_CONNECT_TCP,
        .scoped = LANDLOCK_SCOPE_ABSTRACT_UNIX_SOCKET | LANDLOCK_SCOPE_SIGNAL,
    };
    int ruleset = syscall(__NR_landlock_create_ruleset, &policy, sizeof(policy), 0);
    if (ruleset < 0) fail("landlock_create_ruleset");
    const int script = getenv("ANDROID_AGENT_SCRIPT") != NULL;
    const char* port_variables[] = {"ANDROID_AGENT_MODEL_PORT", "ANDROID_AGENT_MCP_PORT"};
    for (size_t i = 0; i < sizeof(port_variables) / sizeof(port_variables[0]); i++) {
        const char* allowed_port = getenv(port_variables[i]);
        if (!allowed_port) continue;
        if (script) return 125;
        char* end;
        unsigned long port = strtoul(allowed_port, &end, 10);
        if (!port || port > 65535 || *end) return 125;
        struct landlock_net_port_attr rule = {
            .allowed_access = LANDLOCK_ACCESS_NET_CONNECT_TCP, .port = port,
        };
        if (syscall(__NR_landlock_add_rule, ruleset, LANDLOCK_RULE_NET_PORT, &rule, 0) < 0)
            fail("landlock_add_rule connect port");
        unsetenv(port_variables[i]);
    }
    allow(ruleset, argv[1], job_fs);
    const char* workspace = getenv("ANDROID_AGENT_WORKSPACE");
    if (workspace) {
        if (workspace[0] != '/') return 125;
        allow(ruleset, workspace, job_fs);
    }
    const char* session = getenv("ANDROID_AGENT_SESSION_HOME");
    if (session) allow(ruleset, session, job_fs);
    const char* runtime = getenv("ANDROID_AGENT_RUNTIME");
    if (runtime) allow(ruleset, runtime, LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_READ_DIR);
    allow(ruleset, argv[2], read_exec | LANDLOCK_ACCESS_FS_READ_DIR);
    allow(ruleset, "/apex/com.android.runtime", read_exec | LANDLOCK_ACCESS_FS_READ_DIR);
    allow(ruleset, "/system/bin/sh", read_exec);
    allow(ruleset, "/system/lib64", read_exec | LANDLOCK_ACCESS_FS_READ_DIR);
    allow(ruleset, "/linkerconfig/ld.config.txt", LANDLOCK_ACCESS_FS_READ_FILE);
    allow(ruleset, "/dev/null", LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_WRITE_FILE);
    allow(ruleset, "/dev/urandom", LANDLOCK_ACCESS_FS_READ_FILE);
    if (script) {
        allow(ruleset, "/proc/self/stat", LANDLOCK_ACCESS_FS_READ_FILE);
        allow(ruleset, "/proc/self/maps", LANDLOCK_ACCESS_FS_READ_FILE);
    } else {
        allow(ruleset, "/proc", LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_READ_DIR);
    }
    if (chdir(argv[1]) < 0) fail("chdir");
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) < 0) fail("PR_SET_NO_NEW_PRIVS");
    if (syscall(__NR_landlock_restrict_self, ruleset, 0) < 0) fail("landlock_restrict_self");
    close(ruleset);
    if (script) restrict_script();
    fprintf(stderr, "Landlock ABI=%ld uid=%d\n", abi, getuid());
    execv(argv[3], &argv[3]);
    fail("execv");
}
