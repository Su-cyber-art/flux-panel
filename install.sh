#!/bin/bash

REPOSITORY="Su-cyber-art/flux-panel"

resolve_latest_release_tag() {
    local latest_url
    local latest_tag

    if ! command -v curl >/dev/null 2>&1; then
        echo "错误：未找到 curl，无法查询最新版本。" >&2
        return 1
    fi

    latest_url=$(curl -fsSL \
        --retry 3 \
        --retry-delay 2 \
        --connect-timeout 10 \
        --max-time 30 \
        -o /dev/null \
        -w '%{url_effective}' \
        "https://github.com/${REPOSITORY}/releases/latest") || return 1
    latest_url="${latest_url%/}"
    latest_tag="${latest_url##*/}"

    if [[ ! "$latest_tag" =~ ^v?[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z][0-9A-Za-z.-]*)?$ ]]; then
        echo "错误：GitHub 返回了无效版本号：$latest_tag" >&2
        return 1
    fi

    printf '%s\n' "$latest_tag"
}

FLUX_RELEASE_TAG="${FLUX_VERSION:-__FLUX_VERSION__}"
if [[ "$FLUX_RELEASE_TAG" == __*__ || "$FLUX_RELEASE_TAG" == "latest" ]]; then
    if ! FLUX_RELEASE_TAG=$(resolve_latest_release_tag); then
        echo "错误：无法获取最新稳定版，可通过 FLUX_VERSION 指定版本。" >&2
        exit 1
    fi
    echo "ℹ️ 自动选择最新稳定版：$FLUX_RELEASE_TAG"
fi
EXPECTED_VERSION="${FLUX_RELEASE_TAG#v}"

download_file() {
    local url="$1"
    local output="$2"

    curl -fL \
        --retry 3 \
        --retry-delay 2 \
        --connect-timeout 15 \
        --max-time 300 \
        "$url" \
        -o "$output"
}

validate_gost_binary() {
    local binary="$1"
    local reported_version

    chmod +x "$binary"
    if ! reported_version=$("$binary" -V 2>&1); then
        echo "❌ 下载的文件无法执行，已取消操作。" >&2
        return 1
    fi

    if [[ "$reported_version" != "gost ${EXPECTED_VERSION}"* ]]; then
        echo "❌ 版本校验失败，期望 ${EXPECTED_VERSION}，实际：${reported_version}" >&2
        return 1
    fi

    echo "🔎 已验证版本：$reported_version"
}

# 获取系统架构
get_architecture() {
    local arch
    arch=$(uname -m)
    case $arch in
        x86_64)
            echo "amd64"
            ;;
        aarch64|arm64)
            echo "arm64"
            ;;
        *)
            echo "错误：不支持的系统架构：$arch" >&2
            return 1
            ;;
    esac
}

# 构建下载地址
build_download_url() {
    local arch
    arch=$(get_architecture) || return 1
    echo "https://github.com/${REPOSITORY}/releases/download/${FLUX_RELEASE_TAG}/gost-${arch}"
}

# 下载地址
DOWNLOAD_URL=$(build_download_url) || exit 1
INSTALL_DIR="${GOST_INSTALL_DIR:-/etc/gost}"
COUNTRY=$(curl -fsSL --connect-timeout 5 --max-time 10 https://ipinfo.io/country 2>/dev/null || true)
if [ "$COUNTRY" = "CN" ]; then
    # 拼接 URL
    DOWNLOAD_URL="https://ghfast.top/${DOWNLOAD_URL}"
fi



# 显示菜单
show_menu() {
  echo "==============================================="
  echo "              管理脚本"
  echo "==============================================="
  echo "请选择操作："
  echo "1. 安装"
  echo "2. 更新"  
  echo "3. 卸载"
  echo "4. 退出"
  echo "==============================================="
}

# 删除脚本自身
delete_self() {
  echo ""
  echo "🗑️ 操作已完成，正在清理脚本文件..."
  SCRIPT_PATH="$(readlink -f "$0" 2>/dev/null || realpath "$0" 2>/dev/null || echo "$0")"
  sleep 1
  rm -f "$SCRIPT_PATH" && echo "✅ 脚本文件已删除" || echo "❌ 删除脚本文件失败"
}

# 检查并安装 tcpkill
check_and_install_tcpkill() {
  # 检查 tcpkill 是否已安装
  if command -v tcpkill &> /dev/null; then
    return 0
  fi
  
  # 检测操作系统类型
  OS_TYPE=$(uname -s)
  
  # 检查是否需要 sudo
  if [[ $EUID -ne 0 ]]; then
    SUDO_CMD="sudo"
  else
    SUDO_CMD=""
  fi
  
  if [[ "$OS_TYPE" == "Darwin" ]]; then
    if command -v brew &> /dev/null; then
      brew install dsniff &> /dev/null
    fi
    return 0
  fi
  
  # 检测 Linux 发行版并安装对应的包
  if [ -f /etc/os-release ]; then
    . /etc/os-release
    DISTRO=$ID
  elif [ -f /etc/redhat-release ]; then
    DISTRO="rhel"
  elif [ -f /etc/debian_version ]; then
    DISTRO="debian"
  else
    return 0
  fi
  
  case $DISTRO in
    ubuntu|debian)
      $SUDO_CMD apt update &> /dev/null
      $SUDO_CMD apt install -y dsniff &> /dev/null
      ;;
    centos|rhel|fedora)
      if command -v dnf &> /dev/null; then
        $SUDO_CMD dnf install -y dsniff &> /dev/null
      elif command -v yum &> /dev/null; then
        $SUDO_CMD yum install -y dsniff &> /dev/null
      fi
      ;;
    alpine)
      $SUDO_CMD apk add --no-cache dsniff &> /dev/null
      ;;
    arch|manjaro)
      $SUDO_CMD pacman -S --noconfirm dsniff &> /dev/null
      ;;
    opensuse*|sles)
      $SUDO_CMD zypper install -y dsniff &> /dev/null
      ;;
    gentoo)
      $SUDO_CMD emerge --ask=n net-analyzer/dsniff &> /dev/null
      ;;
    void)
      $SUDO_CMD xbps-install -Sy dsniff &> /dev/null
      ;;
  esac
  
  return 0
}


# 获取用户输入的配置参数
get_config_params() {
  if [[ -z "$SERVER_ADDR" || -z "$SECRET" ]]; then
    echo "请输入配置参数："
    
    if [[ -z "$SERVER_ADDR" ]]; then
      read -p "服务器地址: " SERVER_ADDR
    fi
    
    if [[ -z "$SECRET" ]]; then
      read -p "密钥: " SECRET
    fi
    
    if [[ -z "$SERVER_ADDR" || -z "$SECRET" ]]; then
      echo "❌ 参数不完整，操作取消。"
      exit 1
    fi
  fi
}

# 解析命令行参数
ACTION=""
case "${1:-}" in
  --update|update)
    ACTION="update"
    shift
    ;;
  --help|-h)
    echo "用法：$0 [--update] [-a 面板地址] [-s 节点密钥]"
    exit 0
    ;;
esac

while getopts "a:s:" opt; do
  case $opt in
    a) SERVER_ADDR="$OPTARG" ;;
    s) SECRET="$OPTARG" ;;
    *) echo "❌ 无效参数"; exit 1 ;;
  esac
done
shift $((OPTIND - 1))
if [[ $# -gt 0 ]]; then
  echo "❌ 无效参数: $*" >&2
  exit 1
fi

# 安装功能
install_gost() {
  echo "🚀 开始安装 GOST..."
  get_config_params

    # 检查并安装 tcpkill
  check_and_install_tcpkill
  

  mkdir -p "$INSTALL_DIR"

  local new_binary
  new_binary=$(mktemp "$INSTALL_DIR/gost.new.XXXXXX") || return 1

  echo "⬇️ 下载 gost 中..."
  if ! download_file "$DOWNLOAD_URL" "$new_binary"; then
    echo "❌ 下载失败，请检查网络或下载链接。"
    rm -f "$new_binary"
    return 1
  fi
  if ! validate_gost_binary "$new_binary"; then
    rm -f "$new_binary"
    return 1
  fi

  # 停止并禁用已有服务
  if systemctl list-units --full -all | grep -Fq "gost.service"; then
    echo "🔍 检测到已存在的gost服务"
    systemctl stop gost 2>/dev/null && echo "🛑 停止服务"
    systemctl disable gost 2>/dev/null && echo "🚫 禁用自启"
  fi

  mv "$new_binary" "$INSTALL_DIR/gost"
  chmod +x "$INSTALL_DIR/gost"
  echo "✅ 下载完成"

  # 打印版本
  echo "🔎 gost 版本：$($INSTALL_DIR/gost -V)"

  # 写入 config.json (安装时总是创建新的)
  CONFIG_FILE="$INSTALL_DIR/config.json"
  echo "📄 创建新配置: config.json"
  cat > "$CONFIG_FILE" <<EOF
{
  "addr": "$SERVER_ADDR",
  "secret": "$SECRET"
}
EOF

  # 写入 gost.json
  GOST_CONFIG="$INSTALL_DIR/gost.json"
  if [[ -f "$GOST_CONFIG" ]]; then
    echo "⏭️ 跳过配置文件: gost.json (已存在)"
  else
    echo "📄 创建新配置: gost.json"
    cat > "$GOST_CONFIG" <<EOF
{}
EOF
  fi

  # 加强权限
  chmod 600 "$INSTALL_DIR"/*.json

  # 创建 systemd 服务
  SERVICE_FILE="/etc/systemd/system/gost.service"
  cat > "$SERVICE_FILE" <<EOF
[Unit]
Description=Gost Proxy Service
After=network.target

[Service]
WorkingDirectory=$INSTALL_DIR
ExecStart=$INSTALL_DIR/gost
Restart=on-failure

[Install]
WantedBy=multi-user.target
EOF

  # 启动服务
  systemctl daemon-reload
  systemctl enable gost
  systemctl start gost

  # 检查状态
  echo "🔄 检查服务状态..."
  if systemctl is-active --quiet gost; then
    echo "✅ 安装完成，gost服务已启动并设置为开机启动。"
    echo "📁 配置目录: $INSTALL_DIR"
    echo "🔧 服务状态: $(systemctl is-active gost)"
  else
    echo "❌ gost服务启动失败，请执行以下命令查看日志："
    echo "journalctl -u gost -f"
    return 1
  fi
}

# 更新功能
update_gost() {
  echo "🔄 开始更新 GOST..."
  
  if [[ ! -x "$INSTALL_DIR/gost" ]]; then
    echo "❌ GOST 未安装，请先选择安装。"
    return 1
  fi
  if ! systemctl cat gost.service >/dev/null 2>&1; then
    echo "❌ 未找到 gost.service，无法安全更新。"
    return 1
  fi
  
  echo "📥 使用下载地址: $DOWNLOAD_URL"
  
  # 检查并安装 tcpkill
  check_and_install_tcpkill
  
  # 先下载新版本
  echo "⬇️ 下载最新版本..."
  local new_binary
  local backup_binary="$INSTALL_DIR/gost.backup"
  new_binary=$(mktemp "$INSTALL_DIR/gost.new.XXXXXX") || return 1
  if ! download_file "$DOWNLOAD_URL" "$new_binary"; then
    echo "❌ 下载失败。"
    rm -f "$new_binary"
    return 1
  fi
  if ! validate_gost_binary "$new_binary"; then
    rm -f "$new_binary"
    return 1
  fi

  if ! cp -a "$INSTALL_DIR/gost" "$backup_binary"; then
    echo "❌ 无法备份旧版本，已取消更新。"
    rm -f "$new_binary"
    return 1
  fi

  # 停止服务
  echo "🛑 停止 gost 服务..."
  if ! systemctl stop gost; then
    echo "❌ 无法停止 gost 服务，已取消更新。"
    rm -f "$new_binary" "$backup_binary"
    return 1
  fi

  # 替换文件
  if ! mv "$new_binary" "$INSTALL_DIR/gost" || ! chmod +x "$INSTALL_DIR/gost"; then
    echo "❌ 无法替换二进制，正在恢复旧版本..."
    mv "$backup_binary" "$INSTALL_DIR/gost"
    chmod +x "$INSTALL_DIR/gost"
    systemctl start gost || true
    return 1
  fi
  
  # 重启服务
  echo "🔄 重启服务..."
  if systemctl start gost; then
    sleep 2
    for _ in {1..15}; do
      if systemctl is-active --quiet gost; then
        rm -f "$backup_binary"
        echo "🔎 新版本：$($INSTALL_DIR/gost -V)"
        echo "✅ 更新完成，服务已重新启动。"
        return 0
      fi
      sleep 1
    done
  fi
  
  echo "❌ 新版本启动失败，正在回滚..."
  mv "$backup_binary" "$INSTALL_DIR/gost"
  chmod +x "$INSTALL_DIR/gost"
  if systemctl start gost && systemctl is-active --quiet gost; then
    echo "✅ 已恢复旧版本：$($INSTALL_DIR/gost -V)"
  else
    echo "❌ 旧版本恢复后仍无法启动，请执行 journalctl -u gost -n 100 查看日志。" >&2
  fi
  return 1
}

# 卸载功能
uninstall_gost() {
  echo "🗑️ 开始卸载 GOST..."
  
  read -p "确认卸载 GOST 吗？此操作将删除所有相关文件 (y/N): " confirm
  if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
    echo "❌ 取消卸载"
    return 0
  fi

  # 停止并禁用服务
  if systemctl list-units --full -all | grep -Fq "gost.service"; then
    echo "🛑 停止并禁用服务..."
    systemctl stop gost 2>/dev/null
    systemctl disable gost 2>/dev/null
  fi

  # 删除服务文件
  if [[ -f "/etc/systemd/system/gost.service" ]]; then
    rm -f "/etc/systemd/system/gost.service"
    echo "🧹 删除服务文件"
  fi

  # 删除安装目录
  if [[ -d "$INSTALL_DIR" ]]; then
    rm -rf "$INSTALL_DIR"
    echo "🧹 删除安装目录: $INSTALL_DIR"
  fi

  # 重载 systemd
  systemctl daemon-reload

  echo "✅ 卸载完成"
}

# 主逻辑
main() {
  if [[ "$ACTION" == "update" ]]; then
    if update_gost; then
      delete_self
      exit 0
    fi
    echo "❌ 更新失败，脚本已保留，便于重试。" >&2
    exit 1
  fi

  # 如果提供了命令行参数，直接执行安装
  if [[ -n "$SERVER_ADDR" && -n "$SECRET" ]]; then
    if install_gost; then
      delete_self
      exit 0
    fi
    echo "❌ 安装失败，脚本已保留，便于重试。" >&2
    exit 1
  fi

  # 显示交互式菜单
  while true; do
    show_menu
    read -p "请输入选项 (1-4): " choice
    
    case $choice in
      1)
        if install_gost; then
          delete_self
          exit 0
        fi
        echo "❌ 安装失败，脚本已保留，便于重试。" >&2
        exit 1
        ;;
      2)
        if update_gost; then
          delete_self
          exit 0
        fi
        echo "❌ 更新失败，脚本已保留，便于重试。" >&2
        exit 1
        ;;
      3)
        uninstall_gost
        delete_self
        exit 0
        ;;
      4)
        echo "👋 退出脚本"
        delete_self
        exit 0
        ;;
      *)
        echo "❌ 无效选项，请输入 1-4"
        echo ""
        ;;
    esac
  done
}

# 执行主函数
main
