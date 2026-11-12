# 官网部署到 GitHub Pages

官网地址：**https://nagnip-xu.github.io/FloatBit/**

官网源码、截图和安装包位于 `website/`，由 GitHub Actions 直接发布，不需要 Node.js 构建，也不需要另购域名或服务器。

## 首次配置

1. 将项目上传到 `Nagnip-xu/FloatBit`，默认分支设为 `main`。
2. 打开仓库 **Settings → Pages**。
3. 在 **Build and deployment → Source** 中选择 **GitHub Actions**。
4. 打开 **Actions → 部署 FloatBit 官网 → Run workflow**，选择 `main` 并运行。
5. 等待任务成功，访问上面的官网地址。部署记录也会出现在 `github-pages` 环境中。

工作流文件：[deploy-pages.yml](../.github/workflows/deploy-pages.yml)。工作流只将 `website/` 上传为 Pages 站点，源码目录和本地文件不会作为网站目录发布。

## 日常更新

修改 `website/` 中的 HTML、CSS、JavaScript、截图或安装包，然后提交并推送到 `main`。工作流会自动重新部署。

如果只是更新根目录 README 或应用源码，站点不会重复部署。需要立即重新发布时，可以在 Actions 页面手动运行工作流。

## 下载文件与截图

目前官网使用相对路径：

- Windows：`website/FloatBit-Setup-1.0.0.exe`
- Android：`website/FloatBit-Android.apk`
- 校验清单：`website/SHA256SUMS.txt`
- 截图：`website/shots/`

更新安装包时，同步更新 README、官网中的版本或文件名，以及 SHA-256 校验清单。相对资源路径支持 `/FloatBit/` 项目站点前缀；无需把 CSS、图片或脚本改成域名根目录路径。

## 排查

- **官网 404**：确认仓库已启用 Pages，发布源为 GitHub Actions，并且最近一次部署成功。
- **更新没有显示**：检查提交是否在 `main`，是否修改了工作流监听的路径，并查看 Actions 日志。部署完成后可刷新浏览器缓存。
- **图片或下载 404**：检查文件名和大小写，确认文件确实位于 `website/` 并已提交。GitHub 上的资源路径区分大小写。
- **网页打开但行情失败**：网页部署与币安接口可达性是两件事；检查浏览器网络环境以及公共行情接口的响应。
