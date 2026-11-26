#!/usr/bin/env bash
# Stop the JVMs left behind by an Android/Gradle build.
#
# `./gradlew --stop` shuts the Gradle daemons down. The Kotlin compile daemon
# is a separate JVM that Gradle does not manage, so it is matched by its main
# class through /proc and signalled directly.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -x "$ROOT/android/gradlew" ]]; then
  (cd "$ROOT/android" && ./gradlew --stop >/dev/null 2>&1) || true
fi

stopped=0
for pid_dir in /proc/[0-9]*; do
  pid="${pid_dir#/proc/}"
  [[ "$(cat "$pid_dir/comm" 2>/dev/null)" == "java" ]] || continue
  if tr '\0' '\n' < "$pid_dir/cmdline" 2>/dev/null | grep -q 'kotlin\.daemon\.KotlinCompileDaemon'; then
    kill "$pid" 2>/dev/null && stopped=$((stopped + 1))
  fi
done

# Report whatever JVMs are still resident so a stuck daemon is visible.
remaining=()
for pid_dir in /proc/[0-9]*; do
  pid="${pid_dir#/proc/}"
  [[ "$(cat "$pid_dir/comm" 2>/dev/null)" == "java" ]] || continue
  remaining+=("$pid")
done

if (( ${#remaining[@]} > 0 )); then
  echo "Java still running: ${remaining[*]}" >&2
else
  echo "Stopped Gradle/Kotlin daemons ($stopped Kotlin daemon(s) killed); no java remains."
fi
