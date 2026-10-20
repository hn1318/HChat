export const WIDGET_STYLES = /* css */ `
:host {
  --hchat-primary: #2f6fed;
  --hchat-primary-text: #ffffff;
  --hchat-bg: #ffffff;
  --hchat-panel: #f6f7f9;
  --hchat-text: #1c1f24;
  --hchat-muted: #6b7280;
  --hchat-border: #e5e7eb;
  --hchat-bubble-in: #f1f3f6;
  --hchat-bubble-out: #2f6fed;
  --hchat-badge: #e5484d;

  display: flex;
  flex-direction: column;
  height: 520px;
  min-height: 380px;
  border: 1px solid var(--hchat-border);
  border-radius: 16px;
  overflow: hidden;
  background: var(--hchat-bg);
  color: var(--hchat-text);
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  font-size: 14px;
  line-height: 1.5;
  box-shadow: 0 8px 28px rgba(15, 23, 42, 0.1);
  box-sizing: border-box;
}

:host([theme='dark']) {
  --hchat-primary: #4f8bff;
  --hchat-primary-text: #0b1020;
  --hchat-bg: #14161a;
  --hchat-panel: #1b1e24;
  --hchat-text: #e8eaed;
  --hchat-muted: #9aa3b0;
  --hchat-border: #2a2f38;
  --hchat-bubble-in: #23272f;
  --hchat-bubble-out: #3a6fd8;
}

* { box-sizing: border-box; }

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--hchat-border);
  flex: 0 0 auto;
}

.header__title {
  font-weight: 600;
  font-size: 15px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.header__meta {
  display: flex;
  align-items: center;
  gap: 5px;
  color: var(--hchat-muted);
  font-size: 12px;
  flex: 0 0 auto;
}

.dot { width: 7px; height: 7px; border-radius: 50%; background: var(--hchat-muted); }
.dot[data-state='authenticated'] { background: #22c55e; }
.dot[data-state='reconnecting'], .dot[data-state='connecting'], .dot[data-state='authenticating'] { background: #f59e0b; }

.messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 8px 16px 16px;
  background: var(--hchat-panel);
  scroll-behavior: smooth;
}

.empty {
  margin: auto;
  padding: 32px 16px;
  color: var(--hchat-muted);
  text-align: center;
  font-size: 13px;
}

.day { margin: 14px 0 10px; text-align: center; color: var(--hchat-muted); font-size: 12px; }

.row { display: flex; gap: 8px; align-items: flex-end; margin-bottom: 10px; }
.row--own { flex-direction: row-reverse; }
.row__body { max-width: 76%; display: flex; flex-direction: column; gap: 3px; }
.row--own .row__body { align-items: flex-end; }
.row__name { color: var(--hchat-muted); font-size: 11px; }
.row__footer { display: flex; gap: 6px; color: var(--hchat-muted); font-size: 11px; }

.avatar {
  width: 30px; height: 30px; border-radius: 50%; color: #fff;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 12px; font-weight: 600; flex: 0 0 auto;
}

.bubble {
  padding: 9px 12px;
  border-radius: 14px;
  background: var(--hchat-bubble-in);
  word-break: break-word;
  white-space: pre-wrap;
}

.bubble--own { background: var(--hchat-bubble-out); color: var(--hchat-primary-text); }
.bubble--recalled { background: transparent; border: 1px dashed var(--hchat-border); color: var(--hchat-muted); font-style: italic; font-size: 12px; }
.bubble--streaming { background: transparent; border: 1px dashed var(--hchat-primary); }

.bubble img { display: block; max-width: 220px; max-height: 220px; border-radius: 10px; object-fit: cover; }
.bubble a { color: inherit; }

.card {
  width: 230px; max-width: 100%; border: 1px solid var(--hchat-border);
  border-radius: 10px; overflow: hidden; background: var(--hchat-bg); color: var(--hchat-text);
}
.card__body { padding: 10px; display: flex; flex-direction: column; gap: 4px; }
.card__title { font-weight: 600; font-size: 13px; }
.card__subtitle { color: var(--hchat-muted); font-size: 12px; }
.card__price { color: #e5484d; font-weight: 600; font-size: 14px; }

.system { display: flex; justify-content: center; margin: 10px 0; }
.system span {
  padding: 3px 10px; border-radius: 999px; background: #6b72801f;
  color: var(--hchat-muted); font-size: 11px;
}

.link-button { border: 0; background: none; padding: 0 4px; color: var(--hchat-muted); font-size: 11px; cursor: pointer; }
.link-button:hover { color: var(--hchat-primary); }

.input {
  display: flex; align-items: flex-end; gap: 8px; padding: 10px 12px;
  border-top: 1px solid var(--hchat-border); background: var(--hchat-bg); flex: 0 0 auto;
}

.input textarea {
  flex: 1; resize: none; min-height: 36px; max-height: 120px; padding: 8px 12px;
  border: 1px solid var(--hchat-border); border-radius: 12px;
  background: var(--hchat-panel); color: var(--hchat-text); font: inherit; outline: none;
}

.input textarea:focus { border-color: var(--hchat-primary); }

.input button {
  height: 34px; padding: 0 16px; border: 0; border-radius: 10px;
  background: var(--hchat-primary); color: var(--hchat-primary-text);
  font-weight: 600; font-size: 13px; cursor: pointer;
}

.input button:disabled { opacity: 0.45; cursor: not-allowed; }

.input .attach {
  width: 34px; height: 34px; padding: 0; border-radius: 50%; font-size: 18px; line-height: 1;
  background: var(--hchat-panel); color: var(--hchat-muted); border: 1px solid var(--hchat-border);
}

.badge {
  min-width: 18px; height: 18px; padding: 0 5px; border-radius: 9px;
  background: var(--hchat-badge); color: #fff; font-size: 11px; line-height: 18px;
  text-align: center; font-weight: 600;
}

@media (max-width: 640px) {
  :host { height: 100dvh; border: 0; border-radius: 0; box-shadow: none; }
  .row__body { max-width: 82%; }
}
`;
