import { copyFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

/**
 * 把 CSS 之类的静态资源复制到 dist（tsc 只处理 TS）。
 * 用法：node scripts/copy-assets.mjs <from> <to>
 */
const [, , from, to] = process.argv;
if (!from || !to) {
  console.error('用法: node scripts/copy-assets.mjs <from> <to>');
  process.exit(1);
}

const source = resolve(process.cwd(), from);
const target = resolve(process.cwd(), to);
mkdirSync(dirname(target), { recursive: true });
copyFileSync(source, target);
console.log(`copied ${from} -> ${to}`);
