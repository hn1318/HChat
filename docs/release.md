# 发布（npm / Maven Central / GHCR）

发布流水线在 [`.github/workflows/release.yml`](../.github/workflows/release.yml)，由 tag `v*` 触发：

```text
check（版本/元信息/tag 对齐） ──┬─► npm     6 个前端包 → registry.npmjs.org
                              ├─► maven   server → Maven Central
                              └─► images  server / playground → GHCR
```

## 一、版本策略

**一个版本号，三处必须一致**：

| 位置      | 文件                                            |
| ------- | --------------------------------------------- |
| 前端包     | `packages/*/package.json` 的 `version`（6 个包统一） |
| 服务端     | `server/pom.xml` 的 `<version>`                |
| Git tag | `v<version>`，例如 `v0.1.1`                      |

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

1. 注册 <https://central.sonatype.com>，验证 namespace **`top.hn3.hchat`**  
   （DNS TXT 或 GitHub 仓库归属二选一）。  
   `server/pom.xml` 的 `<groupId>` 必须和这个 namespace 完全一致，否则 portal 会拒收 deployment。  
   Java 包名仍是 `com.hchat.server.*`，与 Maven 坐标互不绑定，不需要跟着改。
2. 在同一页面生成 **User Token**，得到 username / password 一对。
3. 准备 GPG 密钥（Central 要求每个 artifact 都有 `.asc` 签名）。仓库里已生成一把专用发布密钥：

   | 项 | 值 |
   | --- | --- |
   | 指纹 | `F44BB3F80D6D16035DD7C55D78D401EC89992ABC` |
   | Key ID | `78D401EC89992ABC` |
   | UID | `HChat Release <release@hn3.top>` |
   | 算法 / 用途 | RSA 3072，仅签名（SC），不过期 |
   | 口令 | **空**（`MAVEN_GPG_PASSPHRASE` 留空） |
   | 私钥（填 Secret 用） | `.data/gpg/private.asc` |
   | 公钥（传 keyserver 用） | `.data/gpg/public.asc` |
   | 私钥本体 | `.data/gpg/home/private-keys-v1.d/*.key` |

   > `.data/` 已被 `.gitignore` 忽略，**切勿把私钥提交进仓库**；两个 `.asc` 是纯文本，直接复制全文即可。  
   > 换成自己的密钥（组织级密钥更推荐）：
   >
   > ```bash
   > gpg --full-generate-key                  # RSA 3072/4096，仅签名
   > gpg --list-secret-keys --keyid-format=long
   > gpg --armor --export-secret-keys <FPR> > private.asc
   > gpg --armor --export <FPR> > public.asc
   > ```

   本机（Windows + PortableGit 自带 GnuPG 2.4.9）的默认钥匙环曾因 `common.conf` 里开了
   `use-keyboxd` 但缺 `keyboxd` 二进制而卡死；已改为经典模式，且残留的 keyboxd 锁文件会让
   `~/.gnupg` 首次调用仍慢。上面的 `.asc` 是可移植形式，在 Linux / CI 里 `gpg --import private.asc`
   即可使用，不依赖本机钥匙环。

4. **把公钥传到公共 keyserver**（Central 校验签名时会去查，不传会报找不到公钥）：

   ```bash
   gpg --keyserver hkps://keyserver.ubuntu.com --send-keys F44BB3F80D6D16035DD7C55D78D401EC89992ABC
   # 或在 https://keys.openpgp.org 手动上传（该站需要验证邮箱 UID）
   ```

5. 仓库 Secrets：

   | Secret                   | 说明                              |
   | ------------------------ | ------------------------------- |
   | `NPM_TOKEN`              | npm 发布令牌                        |
   | `MAVEN_CENTRAL_USERNAME` | Central User Token 的 username   |
   | `MAVEN_CENTRAL_PASSWORD` | Central User Token 的 password   |
   | `MAVEN_GPG_PRIVATE_KEY`  | `private.asc` 全文（含 BEGIN/END 行） |
   | `MAVEN_GPG_PASSPHRASE`   | GPG 私钥口令；当前密钥无口令，留空     |

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

# 3. 提交并打 tag（仓库默认分支是 master）
git commit -am "chore: release v0.1.1"
git tag v0.1.1
git push origin master
git push origin refs/tags/v0.1.1
```

也可以把分支和 tag 一起推：`git push origin master --tags`（会把本地所有 tag 一并推上去）。

### tag 已经推过、后面又补了提交时

`release-check` 只校验 **tag 名**与版本号一致，不校验 tag 指向哪个提交。若 tag 指向旧提交
（例如版本对齐提交晚于打 tag 的时间），CI 会 checkout 那份旧代码，版本校验必然失败。
此时要把 tag 移到最新提交并覆盖远端：

```bash
git tag -f v0.1.1                              # 指向当前 HEAD
git push origin master
git push --force origin refs/tags/v0.1.1       # 覆盖远端同名 tag，重新触发 Release
```

> 强推 tag 会覆盖远端已有的 `v0.1.1` 并再跑一次 Release workflow。若上一次已经成功发布到
> npm / Maven Central，**同一版本号不能重发**，应升补丁版本（例如 `v0.1.2`）后把三处版本
> 全量对齐再走一遍；只是 CI 报错、没真正发出去时，才用上面的强推。

也可以在 Actions → **Release** → Run workflow 手动触发，并勾选只发 npm / 只发 Maven / 只推镜像。

## 四、本地自检（不发包）

| 命令                                                   | 作用                                                 |
| ---------------------------------------------------- | -------------------------------------------------- |
| `pnpm release:check`                                 | 元信息、版本、依赖协议、exports 产物是否存在                         |
| `pnpm release:prepare`                               | 把根目录 `LICENSE` 复制进 6 个包（npm 只收包目录内的 LICENSE）       |
| `pnpm release:pack`                                  | 真跑 `pnpm pack`，解开 tarball 校验内容；产物在 `.data/pack/`   |
| `pnpm release:pack` 之后                               | `tar -tzf .data/pack/hchat-core-sdk-0.1.1.tgz` 肉眼看 |
| `mvn -f server/pom.xml verify -P release -Dgpg.skip` | 验证 sources / javadoc 附件能生成（跳过签名）                  |

想连签名一起验证（Linux / CI 环境）：

```bash
export GNUPGHOME="$PWD/.data/gpg/home"
gpg --batch --import .data/gpg/private.asc
mvn -f server/pom.xml verify -P release -DskipTests
# 检查 server/target/*.asc 是否生成
```

> Windows + Git Bash：MSYS 会把 `/tmp/...` 这类 POSIX 路径转成 `C:/...` 再传给 Maven，
> gpg 又会把它当相对路径拼上工作目录，导致找不到钥匙环。加
> `MSYS2_ENV_CONV_EXCL=GNUPGHOME` 禁止转换即可（CI 的 Linux 无此问题）。

`release:pack` 会断言这些容易踩的点：

- 包内有 `dist/` 产物，且 `exports` 指向的每个文件都真实存在
- 包内有 `README.md`、`LICENSE`
- `package.json` 里的 `hchat-*` 依赖已经从 `workspace:*` 替换成具体版本
- 包内 `name` / `version` 与仓库一致

## 五、失败处理

| 情况              | 处理                                                                                                                   |
| --------------- | -------------------------------------------------------------------------------------------------------------------- |
| npm 部分包已发布、部分失败 | 已发布的版本不能再覆盖。补发剩余包：`pnpm --filter hchat-core-sdk publish --access public --no-git-checks`                             |
| Maven 校验失败      | 到 <https://central.sonatype.com/publishing/deployments> 看具体报错（通常是缺 sources/javadoc、签名或 POM 元信息）；同一版本不能重发，修好后升补丁版本    |
| 找不到 GPG 公钥       | 公钥没传到 keyserver 或还在同步，见上文第 4 步                                                                                   |
| 想先人工确认再上线       | `mvn deploy -P release -Dcentral.autoPublish=false`，然后在 portal 点 **Publish**；或在 CI 里把 `central.autoPublish` 改成 false |
| 只想看 CI 会不会过     | 手动触发 Release，只勾 `publish_*` 中要试的那一项；`check` job 永远会跑                                                                 |

## 六、已知限制

- **版本号手工维护**：没有引入 changesets，6 个包 + pom 需要一起改（`release:check` 会兜住）
- **服务端发布的是可执行 fat jar**：`spring-boot-maven-plugin` 已 `repackage`，适合当应用分发，  
  不适合被别的工程当依赖引入。要做库分发需先把 `repackage` 拆到 `exec` classifier
- **镜像未签名**：GHCR 只推 tag，没有 cosign / SBOM（见 `DECISIONS.md`）
