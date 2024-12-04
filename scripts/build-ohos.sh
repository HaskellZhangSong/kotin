#!/bin/bash -eu

# Color codes for logging
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging utility functions

log-info() {
    echo -e "${BLUE}[INFO]${NC} $*" >&2
}

log-success() {
    echo -e "${GREEN}[SUCCESS]${NC} $*" >&2
}

log-warning() {
    echo -e "${YELLOW}[WARNING]${NC} $*" >&2
}

log-error() {
    echo -e "${RED}[ERROR]${NC} $*" >&2
    exit 1
}

init() {
    cd "$(realpath "$(dirname "$0")"/..)"
    log-info "Project root dir: $PWD"
}

detect-sdk-home() {
    # Prefer environment variable
    if [ -n "${OHOS_SDK_HOME:-}" ]; then
        log-info "Using SDK home from OHOS_SDK_HOME env var: $OHOS_SDK_HOME"
        export OHOS_SDK_HOME
        return
    fi

    # Check DevEco Studio default location
    local deveco_default="/Applications/DevEco-Studio.app/Contents"
    if [ -d "$deveco_default" ]; then
        log-info "Using DevEco Studio default location: $deveco_default"
        export OHOS_SDK_HOME="$deveco_default"
        return
    fi

    # Fallback: search for aarch64-unknown-linux-ohos-clang
    local clang_path="$(command -v aarch64-unknown-linux-ohos-clang)"
    if [[ -n "$clang_path" ]]; then
        local sdk_dir="$(realpath "$(dirname "$clang_path")/../../../../../..")"
        log-info "Found SDK home via PATH: $sdk_dir"
        export OHOS_SDK_HOME="$sdk_dir"
        return
    fi

    log-error "Could not detect OHOS SDK home. Please set OHOS_SDK_HOME environment variable."
}

build-bootstrap() {
    # Clear local.properties
    if [ -f local.properties ]; then
        log-warning "Clearing local.properties"
        rm local.properties
    fi

    log-info "Publishing the bootstrap compiler"
    ./gradlew publish || log-error "Bootstrap publish failed"
    log-success "Bootstrap publishing completed"
}

set-konan-property() {
    local sdk_home="$1"
    local konan_props_file="./kotlin-native/konan/konan.properties"

    if [ ! -f "$konan_props_file" ]; then
        log-error "konan.properties file not found at $konan_props_file"
    fi

    sed -i -e "s|^ohosSdkHome\s*=.*$|ohosSdkHome = $sdk_home|" "$konan_props_file" ||
        log_error "Failed to update ohosSdkHome in konan.properties"

    log-success "Updated konan.properties with SDK home: $sdk_home"
}

build-compiler() {
    # Update local.properties
    log-info "Configuring local.properties for Kotlin Native build"
    echo "kotlin.native.enabled=true" > local.properties
    echo "bootstrap.local=true" >> local.properties

    # Set SDK home in konan.properties
    set-konan-property "$OHOS_SDK_HOME"

    # Build Kotlin Native compiler bundle
    log-info "Building Kotlin Native compiler bundle"
    ./gradlew :kotlin-native:bundle --info || log-error "Compiler bundle build failed"
    log-success "Kotlin Native compiler bundle build completed"
}

main() {
    init
    detect-sdk-home

    # Check if Kotlin compiler artifact already exists
    if [[ -d "./build/repo/org/jetbrains/kotlin/kotlin-compiler" ]]; then
        log-info "Kotlin compiler artifact already exists. Skipping bootstrap build."
    else
        build-bootstrap
    fi

    build-compiler
}

main
