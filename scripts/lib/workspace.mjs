/**
 * 发布脚本共用的 workspace 工具。
 *
 * 只依赖 Node 内置模块，避免发布路径上再引入工具链依赖。
 */
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

export function readJson(path) {
  return JSON.parse(readFileSync(path, 'utf8'));
}

/** 列出 packages/* 与 apps/* 下的所有 workspace 包。 */
export function listWorkspacePackages() {
  const packages = [];
  for (const group of ['packages', 'apps']) {
    const dir = join(repoRoot, group);
    if (!existsSync(dir)) {
      continue;
    }
    const entries = readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name));
    for (const entry of entries) {
      if (!entry.isDirectory()) {
        continue;
      }
      const absDir = join(dir, entry.name);
      const manifest = join(absDir, 'package.json');
      if (!existsSync(manifest)) {
        continue;
      }
      packages.push({
        group,
        dir: `${group}/${entry.name}`,
        absDir,
        pkg: readJson(manifest),
      });
    }
  }
  return packages;
}

/** 真正会发布到 npm 的包（apps/* 与 private 包排除）。 */
export function publishablePackages() {
  return listWorkspacePackages().filter((item) => item.group === 'packages' && item.pkg.private !== true);
}

/** 除 dependencies 外，还会被 npm 解析进安装树的字段。 */
export const DEP_FIELDS = ['dependencies', 'peerDependencies', 'optionalDependencies'];

/** 按内部依赖做拓扑排序（被依赖者在前），同层按包名稳定排序。 */
export function topoSort(packages) {
  const byName = new Map(packages.map((item) => [item.pkg.name, item]));
  const pending = new Map();
  for (const item of packages) {
    const deps = new Set();
    for (const field of DEP_FIELDS) {
      for (const [name, range] of Object.entries(item.pkg[field] ?? {})) {
        if (byName.has(name) && String(range).startsWith('workspace:')) {
          deps.add(name);
        }
      }
    }
    pending.set(item.pkg.name, deps);
  }

  const sorted = [];
  const declared = new Set();
  while (sorted.length < packages.length) {
    const ready = [...pending.entries()]
      .filter(([name, deps]) => !declared.has(name) && [...deps].every((dep) => declared.has(dep)))
      .map(([name]) => name)
      .sort();
    if (ready.length === 0) {
      throw new Error(`内部依赖存在环，无法确定发布顺序：${[...pending.keys()].filter((n) => !declared.has(n)).join(', ')}`);
    }
    for (const name of ready) {
      sorted.push(byName.get(name));
      declared.add(name);
    }
  }
  return sorted;
}

/** 仓库唯一版本号来源：server/pom.xml 的 <version>。 */
export function serverVersion() {
  const pom = readFileSync(join(repoRoot, 'server/pom.xml'), 'utf8');
  const match = pom.match(/<artifactId>hchat-server<\/artifactId>\s*<version>([^<]+)<\/version>/);
  if (!match) {
    throw new Error('无法从 server/pom.xml 解析版本号');
  }
  return match[1].trim();
}
