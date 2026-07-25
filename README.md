# flux-panel

`flux-panel` 是一个基于 GOST 的流量转发管理面板，由 Web 面板、后端服务和节点端程序组成，支持从单节点端口转发到任意多跳链路的统一管理。

## 特性

- 支持 TCP、UDP 协议转发
- 支持端口转发与隧道转发两种模式
- 支持按顺序配置任意数量的多跳中转节点
- 为多跳链路的每一跳维护独立端口映射，并统一管理启停状态
- 支持用户、隧道、节点和转发规则管理
- 支持用户与隧道级别的转发数量、流量、速率和到期时间限制
- 支持单向或双向流量计费及倍率配置
- 支持 IPv4、IPv6 和多种转发策略

## 快速安装

面板端需要 Linux、Docker 和 Docker Compose。节点端支持 Linux AMD64 与 ARM64。

### 安装面板

```bash
curl -fsSL https://github.com/Su-cyber-art/flux-panel/releases/latest/download/panel_install.sh -o panel_install.sh \
  && chmod +x panel_install.sh \
  && sudo ./panel_install.sh
```

运行后选择 `1. 安装面板`。脚本会自动选择 IPv4 或 IPv6 的 Compose 配置，默认端口如下：

- Web 面板：`6366`
- 后端服务：`6365`

默认管理员账号和密码均为 `admin_user`。首次登录后请立即修改密码。

### 安装节点

先在面板中创建节点并取得节点密钥，然后在节点服务器执行：

```bash
curl -fsSL https://github.com/Su-cyber-art/flux-panel/releases/latest/download/install.sh -o install.sh \
  && chmod +x install.sh \
  && sudo ./install.sh
```

运行后选择 `1. 安装`，按提示填写后端服务地址和节点密钥。也可以使用参数直接安装：

```bash
sudo ./install.sh -a "面板服务器地址:6365" -s "节点密钥"
```

### 更新

节点端可直接执行以下命令。脚本会自动选择最新稳定版、校验下载
的二进制版本，并在新版本启动失败时恢复旧版本：

```bash
curl -fsSL https://raw.githubusercontent.com/Su-cyber-art/flux-panel/refs/heads/main/install.sh -o install.sh \
  && chmod +x install.sh \
  && sudo ./install.sh --update
```

面板端必须进入原部署目录（包含 `.env` 和 `docker-compose.yml`）后更新：

```bash
curl -fsSL https://github.com/Su-cyber-art/flux-panel/releases/latest/download/panel_install.sh -o panel_install.sh \
  && chmod +x panel_install.sh \
  && sudo ./panel_install.sh
```

运行面板脚本后选择 `2. 更新面板`。更新前应先通过菜单中的
`4. 导出备份` 备份数据库，并另外保存 `.env`。MySQL 5.7 不能直接使用
MySQL 8.4 数据目录；升级前请先阅读对应 Release 中的
`mysql-5.7-to-8.4.md`。面板更新完成后，所有节点也应更新到相同版本。
