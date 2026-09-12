/*
 * Minimal jni_md.h for macOS (x86_64, arm64).
 * Mirrors the OpenJDK definition; vendored so native builds need no JDK.
 */
#ifndef _JAVASOFT_JNI_MD_H_
#define _JAVASOFT_JNI_MD_H_

#ifndef __has_attribute
  #define __has_attribute(x) 0
#endif

#if (defined(__GNUC__) && ((__GNUC__ > 4) || (__GNUC__ == 4) && (__GNUC_MINOR__ > 2))) || __has_attribute(visibility)
  #define JNIEXPORT __attribute__((visibility("default")))
#else
  #define JNIEXPORT
#endif

#define JNIIMPORT
#define JNICALL

typedef int jint;
#ifdef _LP64
typedef long jlong;
#else
typedef long long jlong;
#endif
typedef signed char jbyte;

#endif /* !_JAVASOFT_JNI_MD_H_ */
