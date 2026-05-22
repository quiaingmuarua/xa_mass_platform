#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 1 ]; then
  echo "usage: $0 <surefire-report-dir> [<surefire-report-dir>...]" >&2
  exit 2
fi

total_reports=0
total_testcases=0

for report_dir in "$@"; do
  if [ ! -d "$report_dir" ]; then
    echo "Surefire report directory not found: $report_dir" >&2
    exit 1
  fi

  report_files=()
  while IFS= read -r -d '' report_file; do
    report_files+=("$report_file")
  done < <(find "$report_dir" -type f -name 'TEST-*.xml' -print0)
  report_count=${#report_files[@]}
  if [ "$report_count" -eq 0 ]; then
    echo "No surefire XML files found under $report_dir" >&2
    exit 1
  fi

  testcase_count=$(
    (
      grep -h -o '<testcase[[:space:]>]' "${report_files[@]}" || true
    ) | wc -l | tr -d '[:space:]'
  )

  if [ "${testcase_count:-0}" -le 0 ]; then
    echo "Surefire reports under $report_dir did not record any executed testcases" >&2
    exit 1
  fi

  echo "Verified $testcase_count executed testcases across $report_count surefire XML files in $report_dir"
  total_reports=$((total_reports + report_count))
  total_testcases=$((total_testcases + testcase_count))
done

echo "Verified $total_testcases executed testcases across $total_reports surefire XML files"
