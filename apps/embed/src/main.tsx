import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './embed.css';
import { EmbedApp } from './EmbedApp';

const container = document.getElementById('root');
if (!container) {
  throw new Error('找不到 #root');
}

createRoot(container).render(
  <StrictMode>
    <EmbedApp />
  </StrictMode>,
);
