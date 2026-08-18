#!/usr/bin/env bash

set -euo pipefail

android=false
rust=false
go=false
contract=false

while IFS= read -r -d '' file; do
  case "$file" in
    .github/workflows/ci.yml|.github/scripts/detect-ci-changes.sh)
      android=true
      rust=true
      go=true
      contract=true
      ;;
    .java-version|apps/android/*)
      android=true
      ;;
    Cargo.toml|Cargo.lock|rust-toolchain.toml|crates/*)
      rust=true
      ;;
    go.work|services/vision-api/*)
      go=true
      ;;
    api/*|fixtures/*)
      contract=true
      ;;
  esac
done

printf 'android=%s\n' "$android"
printf 'rust=%s\n' "$rust"
printf 'go=%s\n' "$go"
printf 'contract=%s\n' "$contract"
