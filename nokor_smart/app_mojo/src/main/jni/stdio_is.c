#include <jni.h>
#include <sys/types.h>
#include <stdbool.h>
#include <unistd.h>
#include <pthread.h>
#include <stdio.h>
#include <fcntl.h>
#include <string.h>
#include <errno.h>
#include <stdlib.h>
#include <time.h>

#ifndef F_SETPIPE_SZ
#define F_SETPIPE_SZ 1031
#endif

// The log file is flushed to the storage at most this often. A sync after every message blocked
// the logging thread for long, the pipe filled up and the game threads that print to stdout hung.
#define LOG_SYNC_INTERVAL_MS 5000
#define LOG_PIPE_SIZE (1024 * 1024)

//
// Created by maks on 17.02.21.
//

static int pfd[2];
static pthread_t logger;
static jmethodID logger_onEventLogged;
static volatile jobject logListener = NULL;
static int latestlog_fd = -1;


static long long monotonic_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long) ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;
}

// buf has to be NUL-terminated
static bool recordBuffer(char* buf, ssize_t len) {
    static volatile long long last_sync_ms = 0;
    if(strstr(buf, "Session ID is")) return false;
    if(latestlog_fd != -1) {
        write(latestlog_fd, buf, len);
        // Data that was written is not lost when the process dies, only a power loss can lose it
        long long now = monotonic_ms();
        if(now - last_sync_ms >= LOG_SYNC_INTERVAL_MS) {
            last_sync_ms = now;
            fdatasync(latestlog_fd);
        }
    }
    return true;
}

static void *logger_thread(void* param) {
    JNIEnv *env;
    jstring writeString;
    JavaVM* dvm = (JavaVM*) param;
    (*dvm)->AttachCurrentThread(dvm, &env, NULL);
    ssize_t  rsize;
    char buf[2050];
    while((rsize = read(pfd[0], buf, sizeof(buf)-1)) > 0) {
        buf[rsize]=0x00; // terminate before the buffer is searched
        bool shouldRecordString = recordBuffer(buf, rsize); //record with newline int latestlog
        if(buf[rsize-1]=='\n') {
            rsize=rsize-1; //truncate
        }
        buf[rsize]=0x00;
        if(shouldRecordString && logListener != NULL) {
            writeString = (*env)->NewStringUTF(env, buf); //send to app without newline
            (*env)->CallVoidMethod(env, logListener, logger_onEventLogged, writeString);
            (*env)->DeleteLocalRef(env, writeString);
        }
    }
    (*dvm)->DetachCurrentThread(dvm);
    return NULL;
}


JNIEXPORT void JNICALL
Java_git_artdeell_mojo_Logger_begin(JNIEnv *env, __attribute((unused)) jclass clazz, jstring logPath) {
    if(latestlog_fd != -1) {
        int localfd = latestlog_fd;
        latestlog_fd = -1;
        close(localfd);
    }
    if(logger_onEventLogged == NULL) {
        jclass eventLogListener = (*env)->FindClass(env, "git/artdeell/mojo/Logger$eventLogListener");
        logger_onEventLogged = (*env)->GetMethodID(env, eventLogListener, "onEventLogged", "(Ljava/lang/String;)V");
    }
    jclass ioeClass = (*env)->FindClass(env, "java/io/IOException");


    setvbuf(stdout, 0, _IOLBF, 0); // make stdout line-buffered
    setvbuf(stderr, 0, _IONBF, 0); // make stderr unbuffered

    /* create the pipe and redirect stdout and stderr */
    pipe(pfd);
    // The default pipe holds 64 KB. A bigger one lets the game print a burst of messages without
    // waiting for the logging thread.
    fcntl(pfd[1], F_SETPIPE_SZ, LOG_PIPE_SIZE);
    dup2(pfd[1], 1);
    dup2(pfd[1], 2);

    /* open latestlog.txt for writing */
    const char* logFilePath = (*env)->GetStringUTFChars(env, logPath, NULL);
    latestlog_fd = open(logFilePath, O_WRONLY | O_TRUNC);
    if(latestlog_fd == -1) {
        latestlog_fd = 0;
        (*env)->ThrowNew(env, ioeClass, strerror(errno));
        return;
    }
    (*env)->ReleaseStringUTFChars(env, logPath, logFilePath);

    JavaVM* vm = NULL;
    (*env)->GetJavaVM(env, &vm);

    /* spawn the logging thread */
    int result = pthread_create(&logger, 0, logger_thread, vm);
    if(result != 0) {
        close(latestlog_fd);
        (*env)->ThrowNew(env, ioeClass, strerror(result));
    }
    pthread_detach(logger);
}

JNIEXPORT void JNICALL Java_git_artdeell_mojo_Logger_appendToLog(JNIEnv *env, __attribute((unused)) jclass clazz, jstring text) {
    jsize appendStringLength = (*env)->GetStringUTFLength(env, text);
    char newChars[appendStringLength+2];
    (*env)->GetStringUTFRegion(env, text, 0, (*env)->GetStringLength(env, text), newChars);
    newChars[appendStringLength] = '\n';
    newChars[appendStringLength+1] = 0;
    if(recordBuffer(newChars, appendStringLength+1) && logListener != NULL) {
        (*env)->CallVoidMethod(env, logListener, logger_onEventLogged, text);
    }
}

JNIEXPORT void JNICALL
Java_git_artdeell_mojo_Logger_setLogListener(JNIEnv *env, __attribute((unused)) jclass clazz, jobject log_listener) {
    jobject logListenerLocal = logListener;
    if(log_listener == NULL) {
        logListener = NULL;
    }else{
        logListener = (*env)->NewGlobalRef(env, log_listener);
    }
    if(logListenerLocal != NULL) (*env)->DeleteGlobalRef(env, logListenerLocal);
}