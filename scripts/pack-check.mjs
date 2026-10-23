/**
 * 打包验证：真正跑一次 `pnpm pack`，解开发布出去的 tarball，
 * 断言里面确实有产物、元信息，并且 workspace 协议已被替换成具体版本。
 *
 *   pnpm release:pack          # = prepare-release + 本脚本
 *   node scripts/pack-check.mjs
 *
 * 这是发布前最接近真相的一步：`pnpm publish` 打的包和这里完全一致。
 */
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { gunzipSync } from 'node:zlib';

import { DEP_FIELDS, publishablePackages, repoRoot, topoSort } from './lib/workspace.mjs';

const outDir = join(repoRoot, '.data', 'pack');
const problems = [];
let passed = 0;

/**
 * 定位 pnpm：优先 PATH 里的 pnpm，其次 corepack（本机没装 pnpm 时的兜底）。
 * 用 shell 执行，避免 Windows 上 .cmd 后缀与 corepack 包装层的差异。
 */
function resolvePnpm() {
  for (const candidate of ['pnpm', 'corepack pnpm']) {
    const probe = spawnSync(`${candidate} --version`, { shell: true, encoding: 'utf8' });
    if (probe.status === 0) {
      return candidate;
    }
  }
  return null;
}

/** 解析 tar（ustar + GNU/PAX 长文件名），返回条目名与内容。 */
function readTar(buffer) {
  const entries = [];
  let offset = 0;
  let longName = null;

  while (offset + 512 <= buffer.length) {
    const header = buffer.subarray(offset, offset + 512);
    if (header.every((byte) => byte === 0)) {
      break;
    }
    const rawName = header.subarray(0, 100).toString('utf8').replace(/\0.*$/, '');
    const sizeField = header.subarray(124, 136).toString('utf8').replace(/\0.*$/, '').trim();
    const size = Number.parseInt(sizeField || '0', 8);
    const flag = String.fromCharCode(header[156] || 48);
    const prefix = header.subarray(345, 500).toString('utf8').replace(/\0.*$/, '');

    const dataStart = offset + 512;
    const dataEnd = dataStart + size;
    const data = buffer.subarray(dataStart, dataEnd);
    const name = prefix ? `${prefix}/${rawName}` : rawName;

    if (flag === 'L') {
      longName = data.toString('utf8').replace(/\0.*$/, '');
    } else if (flag !== 'x' && flag !== 'g' && flag !== '5') {
      entries.push({ name: longName ?? name, size, data });
      longName = null;
    }
    offset = dataStart + Math.ceil(size / 512) * 512;
  }
  return entries;
}

const pnpm = resolvePnpm();
if (!pnpm) {
  console.error('✗ 找不到 pnpm（既不在 PATH，也不在 corepack 里）');
  process.exit(1);
}
if (existsSync(outDir)) {
  rmSync(outDir, { recursive: true, force: true });
}
mkdirSync(outDir, { recursive: true });

const packages = topoSort(publishablePackages());

for (const item of packages) {
  const { pkg, dir, absDir } = item;
  const label = `${pkg.name}@${pkg.version}`;

  if (!existsSync(join(absDir, 'dist'))) {
    problems.push(`${label}: dist 不存在，请先运行 pnpm build`);
    console.error(`✗ ${label} 未构建`);
    continue;
  }

  const result = spawnSync(`${pnpm} pack --pack-destination "${outDir}"`, {
    cwd: absDir,
    encoding: 'utf8',
    shell: true,
  });
  if (result.status !== 0) {
    const detail = (result.stderr || result.stdout || '未知错误').trim().split('\n').slice(-2).join(' ');
    problems.push(`${label}: pnpm pack 失败 -> ${detail}`);
    console.error(`✗ ${label} 打包失败：${detail}`);
    continue;
  }

  const tarball = join(outDir, `${pkg.name}-${pkg.version}.tgz`);
  if (!existsSync(tarball)) {
    problems.push(`${label}: 未生成 ${pkg.name}-${pkg.version}.tgz`);
    console.error(`✗ ${label} 未找到 tarball`);
    continue;
  }

  const files = readTar(gunzipSync(readFileSync(tarball)));
  const byName = new Map(files.map((file) => [file.name, file]));
  const localProblems = [];

  const manifestEntry = byName.get('package/package.json');
  if (!manifestEntry) {
    localProblems.push('缺少 package/package.json');
  } else {
    const published = JSON.parse(manifestEntry.data.toString('utf8'));
    if (published.name !== pkg.name || published.version !== pkg.version) {
      localProblems.push(`包内 name/version 为 ${published.name}@${published.version}`);
    }
    for (const field of DEP_FIELDS) {
      for (const [name, range] of Object.entries(published[field] ?? {})) {
        if (String(range).includes('workspace:')) {
          localProblems.push(`${field}.${name} 仍为 "${range}"，发布前必须替换成具体版本`);
        }
      }
    }
    for (const [key, target] of Object.entries(pkg.exports ?? {})) {
      const targets = typeof target === 'string' ? [target] : Object.values(target ?? {});
      for (const entry of targets) {
        if (typeof entry !== 'string' || !entry.startsWith('./')) {
          continue;
        }
        if (!byName.has(`package/${entry.slice(2)}`)) {
          localProblems.push(`exports["${key}"] 的产物 ${entry} 不在包内`);
        }
      }
    }
    if (!published.license) {
      localProblems.push('包内 package.json 缺少 license');
    }
  }

  if (!byName.has('package/README.md')) {
    localProblems.push('包内缺少 README.md');
  }
  if (!byName.has('package/LICENSE')) {
    localProblems.push('包内缺少 LICENSE（先运行 pnpm release:prepare）');
  }
  const distFiles = files.filter((file) => file.name.startsWith('package/dist/') && !file.name.endsWith('/'));
  if (distFiles.length === 0) {
    localProblems.push('包内没有 dist 产物');
  }

  if (localProblems.length > 0) {
    problems.push(...localProblems.map((problem) => `${label}: ${problem}`));
    console.error(`✗ ${label}（${files.length} 个文件）`);
    for (const problem of localProblems) {
      console.error(`    - ${problem}`);
    }
    continue;
  }

  const sizeKb = (readFileSync(tarball).length / 1024).toFixed(1);
  passed += 1;
  console.info(`✓ ${label}  ${files.length} 个文件 / ${sizeKb} KB / ${distFiles.length} 个产物 (${dir})`);
}

console.info(`\n${problems.length === 0 ? '✓' : '✗'} 打包验证：${passed}/${packages.length} 个包通过，产物在 ${outDir}`);
if (problems.length > 0) {
  console.error(`共 ${problems.length} 个问题`);
}

process.exit(problems.length === 0 ? 0 : 1);
