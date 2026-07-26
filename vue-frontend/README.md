# flux-panel · Vue 3 前端（重构版）

基于 **Vue 3 + Vite + TypeScript + Naive UI + Pinia + vue-router + ECharts** 全面重写的管理面板前端，替代原 `vite-frontend`（React 18 + HeroUI）。

界面更现代、动效更丝滑，暗色模式跟随系统并支持手动切换；桌面端为侧边栏布局、移动端（H5）为底部标签栏布局，自动切换。后端接口、鉴权方式、部署方式与原前端完全一致，可直接替换。

## 目录结构

```
src/
  api/            # http 封装（响应信封/鉴权/token 失效）+ 全部接口
  router/         # 路由与登录守卫
  stores/         # Pinia：auth（会话）、config（站点配置缓存）
  composables/    # useTheme / useH5 / useToast / useNodeSocket
  layouts/        # AdminLayout（桌面）/ H5Layout / H5SimpleLayout / BlankLayout
  components/     # Logo / PageContainer / EmptyState / ThemeToggle /
                  # AddressModal / ChangePasswordModal / DiagnosisDialog
  pages/          # 11 个页面：Login / ChangePassword / Dashboard / Forward /
                  # Tunnel / Node / User / Limit / Config / Profile / Settings
  utils/          # jwt / auth / format / clipboard / panel(WebView 桥) / tac(验证码 SDK)
  types/          # 全部 TS 类型
  styles/global.css   # 设计系统（CSS 变量、动效、响应式）
```

## 本地开发

需要 Node.js 24。

```bash
cd vue-frontend
npm ci
# 后端地址默认 http://127.0.0.1:6365（可用 VITE_DEV_BACKEND 覆盖）
npm run dev            # http://localhost:3000
```

开发服务器已内置代理：`/api` 与 `/system-info`（WebSocket）转发到后端，无需处理跨域。

## 构建

```bash
npm run build          # 产物在 dist/
npm run preview        # 本地预览产物
```

> 构建使用 esbuild 转译；CI 与发布流水线会先执行 `npm run type-check`。

## Docker / 部署（与原前端一致）

`Dockerfile` + `nginx.conf` 与原 `vite-frontend` 保持一致的对外行为：

- 监听 80，SPA history 回退到 `index.html`
- 反向代理 `/api/v1/`、`/flow/upload`、`/flow/config` 到 `backend:6365`
- `/system-info` 升级为 WebSocket 转发

因此 `docker-compose-v4.yml` / `docker-compose-v6.yml` 中把前端构建目录从 `vite-frontend` 换成 `vue-frontend` 即可（端口、环境变量不变）。

生产环境变量：`VITE_API_BASE` 留空表示同源（走 nginx 代理）；`VITE_APP_VERSION` 由构建参数注入。

## 与后端的契约要点

- 响应信封 `{ code, msg, data }`，`code === 0` 为成功；请求头 `Authorization` 直接携带原始 token（无 `Bearer` 前缀）。
- token 失效（401 + 指定 msg）自动清理并跳转登录页。
- 节点实时遥测通过 `/system-info?type=0&secret=<token>` 的 WebSocket，前端只读展示。
- 登录验证码沿用 tianai-captcha（`utils/tac.min.js` + `utils/tac.css`，逐字沿用，框架无关）。

## 相比原前端的增强

- 统一的品牌化设计系统与卡片悬浮/路由过渡动效，观感更精致。
- 明暗主题：跟随系统 + 顶栏一键切换（原版仅跟随系统）。
- 诊断结果弹窗（`DiagnosisDialog`）配合后端“真实链路诊断”，按 **入口监听 / 逐跳建连 / 目标可达 / 端到端数据回环** 分类展示真实延迟、抖动、丢包与字节级完整性校验结果。
