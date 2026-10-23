# 发布（npm / Maven Central / GHCR）

发布流水线在 [`.github/workflows/release.yml`](../.github/workflows/release.yml)，由 tag `v*` 触发：

```text
check（版本/元信息/tag 对齐） ──┬─► npm     6 个前端包 → registry.npmjs.org
                              ├─► maven   server → Maven Central
                              └─► images  server / playground → GHCR
```

## 一、版本策略

**一个版本号，三处必须一致**：

| 位置 | 文件 |
| --- | --- |
| 前端包 | `packages/*/package.json` 的 `version`（6 个包统一） |
| 服务端 | `server/pom.xml` 的 `<version>` |
| Git tag | `v<version>`，例如 `v0.1.0` |

`node scripts/release-check.mjs --tag "$TAG"` 会逐项校验，不一致直接失败（CI 第一步就是它）。
包之间用 `workspace:*` 引用，`pnpm publish` 时会自动替换成当前版本，仓库里不写死版本号。

发布顺序由依赖拓扑决定，`pnpm -r publish` 自动处理：

```text
hchat-shared-types → hchat-core-sdk → hchat-react-chat / hchat-vue-chat / hchat-web-component-chat
                                   → hchat-server-sdk-node
```

## 二、一次性配置

### 1. npm

1. 在 npmjs.com 开启 2FA
2. 生成 **Granular Access Token**（或 Automation Token），权限至少含 `Publish`
3. 仓库 Settings → Secrets → Actions 添加 `NPM_TOKEN`

> `publishConfig.registry` 固定为 `https://registry.npmjs.org/`，所以仓库 `.npmrc` 里的镜像源
> （`registry.npmmirror.com`）只影响安装，不会把包发到镜像。

要发布的 6 个包：`hchat-shared-types`、`hchat-core-sdk`、`hchat-react-chat`、`hchat-vue-chat`、
`hchat-web-component-chat`、`hchat-server-sdk-node`。

### 2. Maven Central

1. 注册 <https://central.sonatype.com>，验证 namespace `com.hchat`
   （DNS TXT 或 GitHub 仓库归属二选一）
2. 在同一页面生成 **User Token**，得到 username / password 一对
3. 准备 GPG 密钥（Central 要求每个 artifact 都有 `.asc` 签名）：

   ```bash
   gpg --full-generate-key              # RSA 4096，不过期（或按组织策略）
   gpg --list-secret-keys --keyid-format=long
   gpg --armor --export-secret-keys <KEY_ID> > private.asc
   gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>   # 公钥要能被校验方查到
   ```

4. 仓库 Secrets：

| Secret | 说明 |
| --- | --- |
| `NPM_TOKEN` | npm 发布令牌 |
| `MAVEN_CENTRAL_USERNAME` | Central User Token 的 username |
| `MAVEN_CENTRAL_PASSWORD` | Central User Token 的 password |
| `MAVEN_GPG_PRIVATE_KEY` | `private.asc` 全文（含 BEGIN/END 行） |
| `MAVEN_GPG_PASSPHRASE` | GPG 私钥口令；无口令则留空 |

`actions/setup-java` 会用 `server-id: central` 写 `settings.xml` 并导入私钥，
所以 Maven 侧不需要额外配置文件。

### 3. GHCR

用内置 `GITHUB_TOKEN`，无需配置；仓库 Settings → Actions → Workflow permissions 保证
`Read and write permissions` 即可（workflow 里已声明 `packages: write`）。

## 三、发布一次

```bash
# 1. 改版本号（6 个 package.json + server/pom.xml）
# 2. 本地自检
pnpm release:check
pnpm release:pack
mvn -f server/pom.xml verify -P release -Dgpg.skip

# 3. 提交并打 tag
git commit -am "chore: release v0.1.0"
git tag v0.1.0
git push origin main --tags
```

也可以在 Actions → **Release** → Run workflow 手动触发，并勾选只发 npm / 只发 Maven / 只推镜像。

## 四、本地自检（不发包）

| 命令 | 作用 |
| --- | --- |
| `pnpm release:check` | 元信息、版本、依赖协议、exports 产物是否存在 |
| `pnpm release:prepare` | 把根目录 `LICENSE` 复制进 6 个包（npm 只收包目录内的 LICENSE） |
| `pnpm release:pack` | 真跑 `pnpm pack`，解开 tarball 校验内容；产物在 `.data/pack/` |
| `pnpm release:pack` 之后 | `tar -tzf .data/pack/hchat-core-sdk-0.1.0.tgz` 肉眼看 |
| `mvn -f server/pom.xml verify -P release -Dgpg.skip` | 验证 sources / javadoc 附件能生成 |

`release:pack` 会断言这些容易踩的点：

- 包内有 `dist/` 产物，且 `exports` 指向的每个文件都真实存在
- 包内有 `README.md`、`LICENSE`
- `package.json` 里的 `hchat-*` 依赖已经从 `workspace:*` 替换成具体版本
- 包内 `name` / `version` 与仓库一致

## 五、失败处理

| 情况 | 处理 |
| --- | --- |
| npm 部分包已发布、部分失败 | 已发布的版本不能再覆盖。补发剩余包：`pnpm --filter hchat-core-sdk publish --access public --no-git-checks` |
| Maven 校验失败 | 到 <https://central.sonatype.com/publishing/deployments> 看具体报错（通常是缺 sources/javadoc、签名或 POM 元信息）；同一版本不能重发，修好后升补丁版本 |
| 想先人工确认再上线 | `mvn deploy -P release -Dcentral.autoPublish=false`，然后在 portal 点 **Publish**；或在 CI 里把 `central.autoPublish` 改成 false |
| 只想看 CI 会不会过 | 手动触发 Release，只勾 `publish_*` 中要试的那一项；`check` job 永远会跑 |

## 六、已知限制

- **版本号手工维护**：没有引入 changesets，6 个包 + pom 需要一起改（`release:check` 会兜住）
- **服务端发布的是可执行 fat jar**：`spring-boot-maven-plugin` 已 `repackage`，适合当应用分发，
  不适合被别的工程当依赖引入。要做库分发需先把 `repackage` 拆到 `exec` classifier
- **镜像未签名**：GHCR 只推 tag，没有 cosign / SBOM（见 `DECISIONS.md`）
