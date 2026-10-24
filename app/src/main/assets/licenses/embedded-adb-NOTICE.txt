# Embedded ADB and SPAKE2

LibADB Android 3.1.1, commit c849886ebc6d48e7b46d967e78a6bb65c90c3b74:
https://github.com/MuntashirAkon/libadb-android
Chosen license: Apache-2.0. Original source headers and LICENSES retained.

SPAKE2 Java/Android 2.2.1, commit 7615ddd680b990e14513ebb66eac4cb0dbf82464:
https://github.com/MuntashirAkon/spake2-java
Java JNI wrapper: LGPL-3.0-or-later. Full modified source provided for rebuilding.
The original pure Java implementation failed exchange tests and is retained only
under reference/, outside compiled source sets. Its EdDSA credits remain intact.

SPAKE2-C, commit 0d15933e5ba3e662cb01245a7ac0dc9fca3eac31:
https://github.com/MuntashirAkon/spake2-c
Derived from BoringSSL/Fiat/ref10; source-specific ISC/MIT/public-domain notices
remain in spake2.c, ed25519.h, curve25519_32.h. SHA512 is LGPL-2.1-or-later;
this distribution uses LGPL-3.0-or-later for that component. JNI wrapper sources
and the original upstream LGPL license are included. GPL-3.0 text accompanies LGPL.

Local changes: Java SecureRandom supplies all 64 entropy bytes; removed rand();
allocation checks and context erasure; bounded JNI inputs; idempotent destruction;
16 KB ELF alignment; Windows desktop test support; bounded ADB connection/read/open
timeouts; stream-open notification race fix; reusable pairing identity is not
destroyed by connection teardown; null-safe pairing cleanup.

Android platform TLS is used. Native SPAKE2 libraries are included for arm64-v8a
and x86_64. Only local loopback TLS ADB is used by the application. No custom
Conscrypt provider is bundled.
