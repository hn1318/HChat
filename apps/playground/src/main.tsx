import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import 'hchat-react-chat/styles.css';
import './styles.css';
import { App } from './App';

const container = document.getElementById('root');
if (!container) {
  throw new Error('找不到 #root');
}

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
