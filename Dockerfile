FROM eclipse-temurin:17-jdk-jammy

# --------------------------------------------------
# Environment
# --------------------------------------------------
ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    CLAUDE_CONFIG_DIR=/root/.claude \
    PATH="/root/.local/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:${PATH}"

# --------------------------------------------------
# Basic tools
# --------------------------------------------------
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
      curl \
      unzip \
      ca-certificates \
      git \
      ripgrep \
      jq \
      less \
      bash && \
    rm -rf /var/lib/apt/lists/*

# --------------------------------------------------
# Android Command-line Tools
# --------------------------------------------------
ARG CMDLINE_TOOLS_VERSION=15859902
ARG CMDLINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583

RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" && \
    curl -fsSL \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
      -o /tmp/tools.zip && \
    echo "${CMDLINE_TOOLS_SHA256}  /tmp/tools.zip" | sha256sum -c - && \
    unzip -q /tmp/tools.zip -d /tmp && \
    mv /tmp/cmdline-tools \
      "${ANDROID_HOME}/cmdline-tools/latest" && \
    rm /tmp/tools.zip

# --------------------------------------------------
# Android CLI + Android 16 SDK
# --------------------------------------------------
RUN android --version

RUN yes | android sdk install \
    platforms/android-36 \
    build-tools/36.0.0 \
    platform-tools

# --------------------------------------------------
# Claude Code (native installer)
# --------------------------------------------------
RUN curl -fsSL https://claude.ai/install.sh | bash

# --------------------------------------------------
# Workspace
# --------------------------------------------------
WORKDIR /workspace/android-app

CMD ["/bin/bash"]
