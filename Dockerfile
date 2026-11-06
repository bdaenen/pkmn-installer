# Android build environment for the APKM installer.
#
# Google does not publish Linux/arm64 Android SDK build tools, so this image is
# built and run as linux/amd64 and executed through emulation on Apple Silicon.
#
# Base image already provides: JDK 17, Gradle 8.10.2, Android cmdline-tools and
# accepted SDK licenses. We only add the SDK packages the app needs.
FROM --platform=linux/amd64 docker.io/thyrlian/android-sdk:latest

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/tools/bin:${ANDROID_HOME}/platform-tools

# SDK packages required to compile the app (compileSdk 35).
RUN sdkmanager --install \
        "platform-tools" \
        "platforms;android-35" \
        "build-tools;35.0.0" \
    && sdkmanager --list_installed

# Gradle caches live here so they can be persisted via a named volume.
ENV GRADLE_USER_HOME=/gradle-cache

WORKDIR /workspace
