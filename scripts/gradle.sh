#!/bin/sh
# Use an installed JDK, including IntelliJ's bundled JDK on macOS.
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -z "${JAVA_HOME:-}" ]; then
    if [ "$(uname -s)" = Darwin ]; then
        detected_java=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
        if [ -n "$detected_java" ]; then
            export JAVA_HOME="$detected_java"
        else
            for candidate in "/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" "/Applications/IntelliJ IDEA CE.app/Contents/jbr/Contents/Home"; do
                if [ -x "$candidate/bin/javac" ]; then
                    export JAVA_HOME="$candidate"
                    break
                fi
            done
        fi
    fi
fi
if [ -n "${JAVA_HOME:-}" ]; then
    "$JAVA_HOME/bin/java" -version >/dev/null 2>&1 || { echo 'Укажите установленную Java 21 в JAVA_HOME.' >&2; exit 1; }
else
    java -version >/dev/null 2>&1 || { echo 'Нужна Java 21: установите JDK или выберите его в IntelliJ IDEA.' >&2; exit 1; }
fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$project_dir/.gradle-user-home}"
cd "$project_dir"
exec ./gradlew "$@"
