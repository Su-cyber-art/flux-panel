# shadcn-vue 转发管理样板验收

final result: passed

## 范围与视觉依据

用户选择了 shadcn-vue 的黑白极简方向。本次完成导航外壳、转发管理、创建/编辑、
导入/导出、地址、诊断和账户弹层；其他业务页面继续使用中性主题下的 Naive UI。

参考是组件体系与视觉语言，并非对财务仪表盘内容的逐像素复制。
参考页面：https://www.shadcn-vue.com/view/dashboard-01

截图保存在本地验收会话中，不随发布产物打包。以下记录对应的证据文件名：

- source visual truth path: reference-desktop.png
- implementation screenshot path: forward-desktop-dark.png（最终复核：forward-desktop-final.png）
- full-view comparison evidence: comparison-desktop.png
- focused region comparison evidence: comparison-table.png

桌面实现 CSS viewport 为 1440×1000，devicePixelRatio=1，截图 1440×1000。
参考原生截图为 1425×990；对照将实现裁至同样尺寸，不拉伸截图。源图的精确 DPR
未保留，因此不宣称逐像素一致。源图为英文财务示例，实现为中文转发业务，
表格字段、行高、导航分组和主色的调整属于本次目标。

检查状态：深色/浅色、桌面/平板/手机、正常/暂停/同步失败、创建与诊断弹层、
空搜索结果。数据来源是只绑定 127.0.0.1 的可重置预览服务。

## 五项视觉检查

- 字体与排版：Geist Variable 随应用打包，中文使用系统字体回退；标题、说明、
  表格名称和等宽地址有清楚层级；长地址截断并提供完整复制入口。
- 间距与布局：桌面侧栏 232px，可收至 72px；转发以表格为中心；创建抽屉桌面
  480px、手机 390px；表单底部按钮固定在可见区域。
- 颜色与视觉变量：使用黑白灰主色和细边框，移除悬浮卡片位移。绿/红/琥珀
  仅用于状态，状态同时提供文字及图标，不依赖颜色区分。
- 图像与资源：此控制台没有需要生成的照片或插画；使用官方 shadcn-vue
  组件源码和 @lucide/vue 图标，字体本地加载，无占位插图。
- 文案：启用状态和节点同步状态分开展示；删除说明体现异步删除；
  诊断明确提示 TCP 检查不能证明 UDP 可达。

## 比较与修正历史

1. 手机端统计区原为两行，筛选工具换行较多，列表进入视野太晚（P2）。
   改为四格紧凑单行，缩短手机视图切换文案与筛选宽度。
   - before: mobile-before-density-fix.png
   - after: forward-mobile-dark.png
   - comparison: comparison-mobile.png
   两图的滚动位置、模拟数据数量不同；比较统计区和筛选区结构，不以整体垂直位移
   判断一致性。最终 390×844、scrollY=0 时表格位于约 526px。
2. 820px 平板保留固定侧栏时，表格约 540px，容器约 512px，右侧操作入口受到挤压（P2）。
   将固定侧栏切换点调整为 1024px；平板使用抽屉导航。
   - before: tablet-before-sidebar-fix.png
   - after: tablet-after-sidebar-fix.png
   修正后表格与容器均为 744px，文档宽度为 820px，没有页面横向溢出。
3. 创建抽屉的官方 data-side 宽度规则覆盖了普通 width 类。使用对应 data-side
   变体设置宽度，已检查桌面 480px、手机全宽 390px，底部操作完整可见。
   - evidence: create-drawer.png、create-mobile-light.png

以上截图均保存在同一次本地验收会话中。
最终全图及表格局部对照中，没有未处理的 P0/P1/P2 问题。

## 交互验证

已在浏览器操作验证：

- 登录本地演示账户，导航进入转发页面。
- 名称/地址搜索、用户/隧道分组。
- 新建转发、自动分配端口；占用端口阻止提交，可用端口恢复提交。
- 保存后显示等待同步，再自动更新为已同步。
- 暂停与恢复；同步失败记录的重试。
- 模拟接口失败一次后，错误提示出现，随后自动刷新清除错误并显示同步完成。
- 诊断结果、TCP/UDP 边界提示、多个目标地址和复制。
- 导出规则；批量导入的成功/错误逐行反馈。
- 删除本地测试记录，节点同步后移除。
- 11 条模拟规则的第二页展示，以及上移/下移排序。
- 手机导航打开/关闭、390×844 表单、深浅色切换。
- 820×1000 平板布局与 1440×1000 桌面布局。

早期开发服务在浏览器连接中断后有旧的 HMR 错误。已以 3001 端口重新启动最新预览，
3001 预览没有新出现的浏览器控制台错误。浏览器网络离线模拟未作为通过依据；
恢复测试使用隔离模拟接口的一次性错误。

## 静态与构建验证

- npm run type-check：通过。
- npm run build：通过。
- npm audit --omit=dev --audit-level=high：0 vulnerabilities。
- git diff --check：通过。

## 后续范围

- 真实 Java 后端与真实节点的联调尚未执行，本次行为验证使用模拟接口。
- 其他页面尚未完成 shadcn-vue 组件迁移，两套组件体系在此阶段并存。
- 原生手机 WebView 的地址桥和真机行为保留接口，仍需真机验证。
- Toast 的部分辅助文字可在后续统一本地化（P3）。

本地验收完成后已停止预览服务。复现方式见 README 的 dev:preview 说明。
本报告记录界面验收结果，发布范围见 docs/releases/1.5.11.md。
