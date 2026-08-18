#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <android/log.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/wait.h>
#include <unistd.h>
#include <string.h>

static char *copy_jstring(JNIEnv *env, jstring value) {
    const char *source = (*env)->GetStringUTFChars(env, value, NULL);
    if (source == NULL) return NULL;

    size_t length = strlen(source);
    char *copy = malloc(length + 1);
    if (copy != NULL) memcpy(copy, source, length + 1);
    (*env)->ReleaseStringUTFChars(env, value, source);
    return copy;
}

static void free_strings(char **values, jsize count) {
    if (values == NULL) return;
    for (jsize i = 0; i < count; i++) free(values[i]);
    free(values);
}

JNIEXPORT jint JNICALL
Java_com_winlator_core_RootFSProcess_launch(JNIEnv *env, jclass clazz, jstring rootDir,
                                            jobjectArray command, jobjectArray environment,
                                            jstring workingDir) {
    (void)clazz;
    jsize commandCount = (*env)->GetArrayLength(env, command);
    jsize environmentCount = (*env)->GetArrayLength(env, environment);
    char **argv = calloc((size_t)commandCount + 1, sizeof(char *));
    char **envp = calloc((size_t)environmentCount + 1, sizeof(char *));
    char *rootDirValue = copy_jstring(env, rootDir);
    char *workingDirValue = copy_jstring(env, workingDir);

    if (argv == NULL || envp == NULL || rootDirValue == NULL || workingDirValue == NULL) {
        free_strings(argv, commandCount);
        free_strings(envp, environmentCount);
        free(rootDirValue);
        free(workingDirValue);
        return -1;
    }

    for (jsize i = 0; i < commandCount; i++) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, command, i);
        argv[i] = copy_jstring(env, item);
        (*env)->DeleteLocalRef(env, item);
        if (argv[i] == NULL) {
            free_strings(argv, commandCount);
            free_strings(envp, environmentCount);
            free(rootDirValue);
            free(workingDirValue);
            return -1;
        }
    }

    for (jsize i = 0; i < environmentCount; i++) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, environment, i);
        envp[i] = copy_jstring(env, item);
        (*env)->DeleteLocalRef(env, item);
        if (envp[i] == NULL) {
            free_strings(argv, commandCount);
            free_strings(envp, environmentCount);
            free(rootDirValue);
            free(workingDirValue);
            return -1;
        }
    }

    pid_t pid = fork();
    if (pid == 0) {
        int rootFd = open(rootDirValue, O_RDONLY | O_DIRECTORY);
        if (rootFd < 0) {
            __android_log_print(ANDROID_LOG_ERROR, "WinlatorProcess", "rootfs open failed: %s", strerror(errno));
            _exit(126);
        }
        if (rootFd != 3 && dup2(rootFd, 3) < 0) {
            __android_log_print(ANDROID_LOG_ERROR, "WinlatorProcess", "rootfs fd duplication failed: %s", strerror(errno));
            _exit(126);
        }
        if (rootFd != 3) close(rootFd);
        if (fcntl(3, F_SETFD, 0) < 0) {
            __android_log_print(ANDROID_LOG_ERROR, "WinlatorProcess", "rootfs fd setup failed: %s", strerror(errno));
            _exit(126);
        }
        if (chdir(workingDirValue) < 0) {
            __android_log_print(ANDROID_LOG_ERROR, "WinlatorProcess", "working directory failed: %s", strerror(errno));
            _exit(126);
        }
        execve(argv[0], argv, envp);
        __android_log_print(ANDROID_LOG_ERROR, "WinlatorProcess", "guest exec failed for %s: %s", argv[0], strerror(errno));
        _exit(126);
    }

    free_strings(argv, commandCount);
    free_strings(envp, environmentCount);
    free(rootDirValue);
    free(workingDirValue);
    return pid > 0 ? (jint)pid : -1;
}

JNIEXPORT jint JNICALL
Java_com_winlator_core_RootFSProcess_waitFor(JNIEnv *env, jclass clazz, jint pid) {
    (void)env;
    (void)clazz;
    int status = 126;
    if (waitpid((pid_t)pid, &status, 0) < 0) return 126;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return status;
}
