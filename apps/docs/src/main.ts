import { marked } from 'marked';
import readmeRaw from '../../../README.md?raw';
import decisionsRaw from '../../../DECISIONS.md?raw';
import integrationRaw from '../../../docs/integration.md?raw';
import pluginRaw from '../../../docs/plugin-development.md?raw';
import protocolRaw from '../../../docs/protocol.md?raw';
import releaseRaw from '../../../docs/release.md?raw';
import securityRaw from '../../../docs/security.md?raw';
import selfHostingRaw from '../../../docs/self-hosting.md?raw';
import './style.css';

interface DocEntry {
  id: string;
  title: string;
  content: string;
}

const DOCS: DocEntry[] = [
  { id: 'readme', title: '项目介绍', content: readmeRaw },
  { id: 'protocol', title: '协议文档', content: protocolRaw },
  { id: 'integration', title: '接入指南', content: integrationRaw },
  { id: 'self-hosting', title: '自托管', content: selfHostingRaw },
  { id: 'security', title: '安全', content: securityRaw },
  { id: 'plugin', title: '插件开发', content: pluginRaw },
  { id: 'release', title: '发布', content: releaseRaw },
  { id: 'decisions', title: '技术决策', content: decisionsRaw },
];

marked.setOptions({ gfm: true, breaks: false });

const app = document.getElementById('app');
if (!app) {
  throw new Error('找不到 #app');
}

app.innerHTML = `
  <div class="docs">
    <aside class="docs__sidebar">
      <div class="docs__brand">HChat <span>docs</span></div>
      <nav class="docs__nav">
        ${DOCS.map((doc) => `<a href="#${doc.id}" data-doc="${doc.id}">${doc.title}</a>`).join('')}
      </nav>
      <div class="docs__footer">Apache-2.0 · v0.1.0</div>
    </aside>
    <main class="docs__content markup" id="content"></main>
  </div>
`;

const content = document.getElementById('content') as HTMLElement;

function render(id: string): void {
  const doc = DOCS.find((item) => item.id === id) ?? DOCS[0];
  if (!doc) return;
  content.innerHTML = marked.parse(doc.content) as string;
  content.scrollTop = 0;
  document.title = `${doc.title} · HChat`;
  document.querySelectorAll<HTMLAnchorElement>('.docs__nav a').forEach((link) => {
    link.classList.toggle('is-active', link.dataset.doc === doc.id);
  });
}

function currentId(): string {
  return window.location.hash.replace(/^#/, '') || 'readme';
}

window.addEventListener('hashchange', () => render(currentId()));
render(currentId());
