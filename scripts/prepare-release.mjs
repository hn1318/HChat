/**
 * 发布前把根目录的 LICENSE 复制到每个待发布包。
 *
 * npm 打包只会带上包目录内的 LICENSE / README，根目录那份不会被收录；
 * 与其在 6 个包里各存一份副本，不如在打包前生成。
 *
 *   node scripts/prepare-release.mjs           # 复制
 *   node scripts/prepare-release.mjs --clean   # 清理（内容与根 LICENSE 一致才删）
 */
import { copyFileSync, existsSync, readFileSync, unlinkSync } from 'node:fs';
import { join } from 'node:path';

import { publishablePackages, repoRoot } from './lib/workspace.mjs';

const clean = process.argv.includes('--clean');
const licenseSource = join(repoRoot, 'LICENSE');

if (!existsSync(licenseSource)) {
  console.error('✗ 找不到根目录 LICENSE');
  process.exit(1);
}

const licenseText = readFileSync(licenseSource, 'utf8');
let changed = 0;

for (const item of publishablePackages()) {
  const target = join(item.absDir, 'LICENSE');

  if (clean) {
    if (!existsSync(target)) {
      continue;
    }
    if (readFileSync(target, 'utf8') !== licenseText) {
      console.warn(`! ${item.dir}/LICENSE 与根目录不一致，跳过删除`);
      continue;
    }
    unlinkSync(target);
    console.info(`- 删除 ${item.dir}/LICENSE`);
    changed += 1;
    continue;
  }

  copyFileSync(licenseSource, target);
  console.info(`+ 写入 ${item.dir}/LICENSE`);
  changed += 1;
}

console.info(clean ? `已清理 ${changed} 个 LICENSE` : `已准备 ${changed} 个 LICENSE`);
