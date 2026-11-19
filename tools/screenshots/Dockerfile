# The reference environment of the screenshot tests (docs/plans/ci-screenshots.md): the workflow
# Screenshots runs in this image, and tools/screenshots/run.sh runs it on any computer with Docker.
# A change here changes the references: record them all again in the same pull request.
#
# x86_64 only: the raster of Skia may take other paths on arm64. On Apple silicon Docker emulates it.

# Ubuntu 24.04 of 2026-11-16, by digest so that an update of the image is a change of this file
FROM --platform=linux/amd64 ubuntu:24.04@sha256:73b999393d07f2bf65a40373778a44dc9a6d54ad7718d29247d8ff9413c4d6a8

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    TZ=UTC

# - the fonts the system families resolve to (FontFamily.Default, Monospace, Serif): DejaVu only;
# - the JDK with AWT (some tests open windows) and a virtual display for them;
# - the toolchain of the JNI libraries (native/scripts/build_desktop.sh).
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates curl git unzip zip file xz-utils bash \
        fontconfig fonts-dejavu-core \
        openjdk-21-jdk xvfb xauth \
        build-essential cmake ninja-build pkg-config python3 perl \
    && rm -rf /var/lib/apt/lists/* \
    && fc-match sans-serif && fc-match monospace && fc-match serif

# Rust for the iroh bridge: the toolchain of native/iroh-bridge/rust-toolchain.toml, given by run.sh
# and by the workflow. The homes are writable by any user, as the container runs as the host's.
ARG RUST_TOOLCHAIN
ENV RUSTUP_HOME=/opt/rustup \
    CARGO_HOME=/opt/cargo \
    PATH=/opt/cargo/bin:$PATH
RUN test -n "$RUST_TOOLCHAIN" \
    && curl -fsSL https://sh.rustup.rs | sh -s -- -y --no-modify-path --profile minimal --default-toolchain "$RUST_TOOLCHAIN" \
    && chmod -R a+rwX /opt/rustup /opt/cargo

# The Gradle home (a volume, or the cache of the runner) and a home for the user of the host
RUN mkdir -p /gradle /home/screenshots && chmod 777 /gradle /home/screenshots
ENV GRADLE_USER_HOME=/gradle \
    HOME=/home/screenshots \
    DRAWVIEW_SCREENSHOTS=reference

WORKDIR /work
