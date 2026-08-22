# Third-Party Notices

## Aliyun libheif

- Artifact: `com.aliyun:libheif:1.0.1`
- Project: https://github.com/aliyun/heif-decoder-lib
- Purpose: Android HEIF/HEIC software-decoding fallback
- License: GNU Lesser General Public License; see `LICENSES/LGPL-libheif.txt`

The library is consumed as an unmodified dynamically linked Android AAR. Its native
libraries are packaged for arm64-v8a, armeabi-v7a, x86, and x86_64. The application
prefers Android platform decoders and invokes this library only as a fallback.

## Other dependencies

AndroidX, Material Components, OkHttp, JUnit, and Robolectric retain their respective
upstream licenses. Gradle resolves their license metadata from the declared artifacts.
