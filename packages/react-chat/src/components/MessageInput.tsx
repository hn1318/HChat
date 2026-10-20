import { useEffect, useRef, useState } from 'react';
import { useChat } from '../context';

export interface MessageInputProps {
  className?: string;
  placeholder?: string;
  disabled?: boolean;
  /** 是否走 AI 流式回答；默认跟随会话类型（c2ai） */
  aiMode?: boolean;
  maxLength?: number;
  allowFile?: boolean;
}

/** 消息输入框：Enter 发送、Shift+Enter 换行、图片/文件上传、正在输入提示。 */
export function MessageInput({
  className = '',
  placeholder = '输入消息，Enter 发送',
  disabled = false,
  aiMode,
  maxLength = 4000,
  allowFile = true,
}: MessageInputProps) {
  const { activeId, activeConversation, sendMessage, sendAiMessage, setTyping, connectionState, streamingText } = useChat();
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const typingTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const isAi = aiMode ?? activeConversation?.type === 'c2ai';
  const offline = connectionState !== 'authenticated';
  const cannotSend = disabled || !activeId || offline || busy || streamingText !== null;

  useEffect(
    () => () => {
      if (typingTimer.current) clearTimeout(typingTimer.current);
    },
    [],
  );

  const triggerTyping = () => {
    setTyping(true);
    if (typingTimer.current) clearTimeout(typingTimer.current);
    typingTimer.current = setTimeout(() => setTyping(false), 1500);
  };

  const submit = async () => {
    const value = text.trim();
    if (!value || cannotSend) return;
    setBusy(true);
    setText('');
    setTyping(false);
    try {
      if (isAi) {
        await sendAiMessage(value);
      } else {
        await sendMessage({ text: value });
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className={`hchat-input ${className}`}>
      {allowFile ? (
        <>
          <button
            type="button"
            className="hchat-input__icon"
            title="发送图片或文件"
            aria-label="发送图片或文件"
            disabled={cannotSend}
            onClick={() => fileRef.current?.click()}
          >
            +
          </button>
          <input
            ref={fileRef}
            type="file"
            hidden
            onChange={async (event) => {
              const file = event.target.files?.[0];
              event.target.value = '';
              if (!file) return;
              setBusy(true);
              try {
                await sendMessage({ file });
              } finally {
                setBusy(false);
              }
            }}
          />
        </>
      ) : null}

      <textarea
        className="hchat-input__textarea"
        value={text}
        rows={1}
        maxLength={maxLength}
        placeholder={offline ? '连接已断开，正在重连…' : placeholder}
        disabled={cannotSend}
        onChange={(event) => {
          setText(event.target.value);
          triggerTyping();
        }}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
            event.preventDefault();
            void submit();
          }
        }}
      />

      <button
        type="button"
        className="hchat-input__send"
        disabled={cannotSend || text.trim().length === 0}
        onClick={() => void submit()}
      >
        {streamingText !== null ? '生成中' : '发送'}
      </button>
    </div>
  );
}
