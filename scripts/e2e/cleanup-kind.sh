#!/usr/bin/env bash
set -uo pipefail
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_OS:-}" == Linux ]] || exit 1
state_dir="${RUNNER_TEMP:?}/k8s-tools-e2e"
# 只关闭本流程 PID 文件中的服务；临时 runner 最终也会被 GitHub 销毁。
for service in sshd redis; do
  if [[ -f "$state_dir/$service.pid" ]]; then
    service_pid=$(sudo cat "$state_dir/$service.pid")
    if [[ "$service_pid" =~ ^[0-9]+$ ]]; then sudo kill "$service_pid" || true; fi
  fi
done
if [[ -x "$state_dir/bin/kind" ]]; then
  "$state_dir/bin/kind" delete cluster --name k8s-tools-e2e
fi
if id k8se2e >/dev/null 2>&1; then sudo userdel --remove k8se2e; fi
rm -rf -- "$state_dir"
