#include <string.h>
#include <stddef.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>

int bcmp(const void *s1, const void *s2, size_t n) {
    return memcmp(s1, s2, n);
}

int __xpg_strerror_r(int errnum, char *buf, size_t buflen) {
    return strerror_r(errnum, buf, buflen);
}

int *__errno_location(void) {
    return __errno();
}

void __assert_fail(const char *assertion, const char *file, unsigned int line, const char *function) {
    fprintf(stderr, "Assertion failed: %s (%s: %s: %u)\n", assertion, file, function ? function : "?", line);
    abort();
}
