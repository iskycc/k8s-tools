#!/usr/bin/env bash
set -uo pipefail
mkdir -p target/e2e-diagnostics
# 只收集资源状态表；不导出 kubeconfig、Secret、Redis 数据或整个 kind 节点目录。
if [[ -n "${KUBECONFIG:-}" && -f "$KUBECONFIG" ]]; then
  kubectl --request-timeout=10s get nodes -o wide > target/e2e-diagnostics/nodes.txt 2>&1 || true
  kubectl --request-timeout=10s get pods -A -o wide > target/e2e-diagnostics/pods.txt 2>&1 || true
  kubectl --request-timeout=10s get deployments -A -o wide > target/e2e-diagnostics/deployments.txt 2>&1 || true
  kubectl --request-timeout=10s get events -A --sort-by=.lastTimestamp > target/e2e-diagnostics/events.txt 2>&1 || true
fi
python3 - <<'PY'
from pathlib import Path
import os, xml.etree.ElementTree as ET
lines = ['## 真实 Kubernetes E2E', '', '环境：kind / Kubernetes 1.37.0、OpenSSH、真实 Redis。', '']
reports = list(Path('target/failsafe-reports').glob('TEST-*.xml'))
if not reports:
    lines.append('未生成真实集群测试报告，请检查环境准备或构建步骤；不能视为 E2E 通过。')
for report in reports:
    suite = ET.parse(report).getroot()
    lines.append('测试 {tests}，失败 {failures}，错误 {errors}，跳过 {skipped}。'.format(**suite.attrib))
    lines.extend(['', '| 测试 | 结果 |', '| --- | --- |'])
    for case in suite.findall('testcase'):
        result = '失败' if case.find('failure') is not None or case.find('error') is not None else '跳过' if case.find('skipped') is not None else '通过'
        lines.append('| {} | {} |'.format(case.get('name'), result))
lines += ['', '覆盖代表性 REST 能力，不代表所有资源、Kubernetes 版本、云厂商或流式接口均已验证。']
summary = '\n'.join(lines) + '\n'
Path('target/e2e-diagnostics/summary.txt').write_text(summary)
with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as out:
    out.write(summary)
PY
