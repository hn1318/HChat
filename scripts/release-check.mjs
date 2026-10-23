/**
 * 发布前的一致性检查：包元信息、版本、依赖协议、导出产物、tag 对齐。
 *
 *   node scripts/release-check.mjs
 *   node scripts/release-check.mjs --tag v0.1.0     # 校验 tag 与版本一致（CI 用）
 *   GITHUB_REF_NAME=v0.1.0 node scripts/release-check.mjs
 *
 * 任何一项不满足都会以非 0 退出，避免「构建能过、发布才发现元信息缺失」。
 */
import { existsSync } from 'node:fs';
import { join } from 'node:path';

import { DEP_FIELDS, publishablePackages, repoRoot, serverVersion } from './lib/workspace.mjs';

const errors = [];
const warnings = [];

function fail(message) {
  errors.push(message);
}

function warn(message) {
  warnings.push(message);
}

const packages = publishablePackages();
if (packages.length === 0) {
  fail('没有找到任何待发布的包（packages/* 且未标记 private）');
}

const versions = new Set(packages.map((item) => item.pkg.version));
if (versions.size > 1) {
  fail(`待发布包版本不一致：${[...versions].map((v) => `${v}`).join(' / ')}`);
}

const repositoryUrls = new Set(
  packages.map((item) => (typeof item.pkg.repository === 'string' ? item.pkg.repository : item.pkg.repository?.url)),
);
if (repositoryUrls.size > 1) {
  fail(`repository.url 不一致：${[...repositoryUrls].join(' / ')}`);
}

const version = [...versions][0];
const pomVersion = serverVersion();
if (pomVersion !== version) {
  fail(`server/pom.xml 版本 ${pomVersion} 与前端包版本 ${version} 不一致（发布前请统一）`);
}

const publishableNames = new Set(packages.map((item) => item.pkg.name));

for (const item of packages) {
  const { pkg, dir, absDir } = item;
  const at = (field) => `${dir}: ${field}`;

  if (typeof pkg.name !== 'string' || !pkg.name.startsWith('hchat-')) {
    warn(at(`包名 ${pkg.name} 未以 hchat- 开头，请确认是否要发布到公共 registry`));
  }
  if (!pkg.description) {
    fail(at('缺少 description（npm 页面会显示空白）'));
  }
  if (pkg.license !== 'Apache-2.0') {
    fail(at(`license 应为 Apache-2.0，当前为 ${pkg.license}`));
  }
  if (!pkg.author) {
    warn(at('缺少 author'));
  }
  if (!pkg.homepage) {
    warn(at('缺少 homepage'));
  }
  if (!pkg.bugs?.url) {
    warn(at('缺少 bugs.url'));
  }
  if (!Array.isArray(pkg.keywords) || pkg.keywords.length === 0) {
    warn(at('缺少 keywords（影响 npm 搜索）'));
  }
  if (!pkg.engines?.node) {
    fail(at('缺少 engines.node'));
  }
  if (pkg.private === true) {
    fail(at('标记了 private，无法发布'));
  }

  if (typeof pkg.repository !== 'object' || !pkg.repository.url) {
    fail(at('缺少 repository.url（Maven Central / npm 都要求源码地址）'));
  } else if (pkg.repository.directory !== dir) {
    fail(at(`repository.directory 应为 "${dir}"，当前为 "${pkg.repository.directory}"`));
  }

  if (!Array.isArray(pkg.files) || !pkg.files.includes('dist')) {
    fail(at('files 必须包含 "dist"，否则发布包里没有产物'));
  }
  if (!pkg.exports || !pkg.exports['.']) {
    fail(at('缺少 exports["."]'));
  }
  if (!pkg.types) {
    fail(at('缺少 types（TS 使用方拿不到类型）'));
  }

  if (pkg.publishConfig?.access !== 'public') {
    fail(at('publishConfig.access 应为 public'));
  }
  if (!String(pkg.publishConfig?.registry ?? '').includes('registry.npmjs.org')) {
    fail(at('publishConfig.registry 应指向 https://registry.npmjs.org/（否则会发布到镜像源）'));
  }

  if (!existsSync(join(absDir, 'README.md'))) {
    fail(at('缺少 README.md（npm 页面需要）'));
  }
  if (!existsSync(join(absDir, 'LICENSE'))) {
    warn(at('缺少 LICENSE，发布会执行 prepare-release 复制，本地打包前请先运行 pnpm release:prepare'));
  }

  for (const field of DEP_FIELDS) {
    for (const [name, range] of Object.entries(pkg[field] ?? {})) {
      if (!publishableNames.has(name)) {
        continue;
      }
      if (!String(range).startsWith('workspace:')) {
        fail(at(`${field}.${name} 指向内部包，必须使用 workspace:* 协议（当前 "${range}"）`));
      }
    }
  }

  // 产物检查只在已经构建过时执行，避免把「没 build」误报成配置错误
  const distDir = join(absDir, 'dist');
  if (!existsSync(distDir)) {
    warn(at('尚未构建（dist 不存在），发布前需先 pnpm build'));
    continue;
  }
  for (const [key, target] of Object.entries(pkg.exports ?? {})) {
    const entries = typeof target === 'string' ? [target] : Object.values(target ?? {});
    for (const entry of entries) {
      if (typeof entry !== 'string' || !entry.startsWith('./')) {
        continue;
      }
      const file = join(absDir, entry);
      if (!existsSync(file)) {
        fail(at(`exports["${key}"] 指向的文件不存在：${entry}`));
      }
    }
  }
}

const tagIndex = process.argv.indexOf('--tag');
const tag = tagIndex > 0 ? process.argv[tagIndex + 1] : process.env.GITHUB_REF_NAME;
if (tag && /^v\d/.test(tag) && tag !== `v${version}` && tag !== `server-v${version}`) {
  fail(`tag ${tag} 与版本 ${version} 不一致（应为 v${version}）`);
}

for (const message of warnings) {
  console.warn(`! ${message}`);
}
for (const message of errors) {
  console.error(`✗ ${message}`);
}

console.info(
  `\n${errors.length === 0 ? '✓' : '✗'} 发布检查：${packages.length} 个包，版本 ${version}，` +
    `${errors.length} 个错误 / ${warnings.length} 个警告（仓库 ${repoRoot}）`,
);

process.exit(errors.length === 0 ? 0 : 1);
