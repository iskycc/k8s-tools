#!/usr/bin/env bash
set -euo pipefail

# 安装系统包、创建临时用户；仅在独立 GitHub Linux runner 上执行。
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_OS:-}" == Linux ]]
state_dir="${RUNNER_TEMP:?}/k8s-tools-e2e"
mkdir -p "$state_dir/bin"
chmod 700 "$state_dir"
printf 'E2E_STATE_DIR=%s\n' "$state_dir" >> "$GITHUB_ENV"

sudo apt-get update -qq
sudo apt-get install -y -qq openssh-server redis-server

# kind release 提供的 binary SHA 与 node image digest，升级时一起核对。
curl --fail --silent --show-error --location --retry 3 \
  https://github.com/kubernetes-sigs/kind/releases/download/v0.33.0/kind-linux-amd64 \
  -o "$state_dir/bin/kind"
printf '%s  %s\n' aee6151561422756b764a4ae28e7f44cda5af5a9eead3cc9985112b1de8d8e0d "$state_dir/bin/kind" | sha256sum --check
chmod +x "$state_dir/bin/kind"
export PATH="$state_dir/bin:$PATH"
printf '%s\n' "$state_dir/bin" >> "$GITHUB_PATH"
export KUBECONFIG="$state_dir/kubeconfig"
kind create cluster --name k8s-tools-e2e --wait 180s --kubeconfig "$KUBECONFIG" \
  --image kindest/node:v1.37.0@sha256:a1ed56cfb0e7b93589bdf97c8cd566405a265939e3620fc4f5de89adff580ae5
# 使用同一固定 node image 内的 kubectl，与 API Server 版本一致。
docker cp k8s-tools-e2e-control-plane:/usr/bin/kubectl "$state_dir/bin/kubectl"
sudo install -m 755 "$state_dir/bin/kubectl" /usr/local/bin/kubectl
kubectl wait --for=condition=Ready nodes --all --timeout=120s

# 预加载测试工作负载镜像；避免每次创建 Pod 时依赖外网拉取。
docker pull registry.k8s.io/pause:3.10
kind load docker-image registry.k8s.io/pause:3.10 --name k8s-tools-e2e

ssh_password=$(openssl rand -hex 24)
redis_password=$(openssl rand -hex 24)
printf '::add-mask::%s\n' "$ssh_password" "$redis_password"
sudo useradd --create-home --user-group --shell /bin/bash k8se2e
printf 'k8se2e:%s\n' "$ssh_password" | sudo chpasswd
sudo install -d -m 700 -o k8se2e -g k8se2e /home/k8se2e/.kube
sudo install -m 600 -o k8se2e -g k8se2e "$KUBECONFIG" /home/k8se2e/.kube/config
kubectl config view --raw --minify -o 'jsonpath={.clusters[0].cluster.certificate-authority-data}' \
  | base64 --decode > "$state_dir/ca.crt"
sudo install -m 600 -o k8se2e -g k8se2e "$state_dir/ca.crt" /home/k8se2e/.kube/ca.crt
# RSA 主机密钥也可由不携带测试加密 provider 的 Java 8 CLI 校验。
ssh-keygen -q -t rsa -b 3072 -N '' -f "$state_dir/ssh_host_key"
cat > "$state_dir/sshd_config" <<CONFIG
ListenAddress 127.0.0.1
Port 22222
HostKey $state_dir/ssh_host_key
PidFile $state_dir/sshd.pid
PasswordAuthentication yes
KbdInteractiveAuthentication no
PubkeyAuthentication no
PermitRootLogin no
UsePAM no
AllowUsers k8se2e
LogLevel ERROR
CONFIG
sudo mkdir -p /run/sshd
sudo /usr/sbin/sshd -t -f "$state_dir/sshd_config"
sudo /usr/sbin/sshd -f "$state_dir/sshd_config" -E "$state_dir/sshd.log"

cat > "$state_dir/redis.conf" <<CONFIG
bind 127.0.0.1
port 16379
protected-mode yes
requirepass $redis_password
daemonize yes
pidfile $state_dir/redis.pid
logfile $state_dir/redis.log
dir $state_dir
save ""
appendonly no
CONFIG
chmod 600 "$state_dir/redis.conf"
redis-server "$state_dir/redis.conf"
REDISCLI_AUTH="$redis_password" redis-cli -h 127.0.0.1 -p 16379 ping
redis-server --version

{
  printf 'KUBECONFIG=%s\n' "$KUBECONFIG"
  printf 'E2E_SSH_PASSWORD=%s\n' "$ssh_password"
  printf 'E2E_REDIS_URL=redis://:%s@127.0.0.1:16379/0\n' "$redis_password"
  printf 'E2E_EXPECTED_API_SERVER=%s\n' "$(kubectl config view --minify -o 'jsonpath={.clusters[0].cluster.server}')"
} >> "$GITHUB_ENV"
