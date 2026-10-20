import { HChatWidgetElement } from './widget-element';

export { HChatWidgetElement };

/** 注册 <hchat-widget> 自定义元素（重复注册不会报错）。 */
export function defineHChatWidget(tagName = 'hchat-widget'): void {
  if (typeof customElements === 'undefined') {
    return;
  }
  if (!customElements.get(tagName)) {
    customElements.define(tagName, HChatWidgetElement);
  }
}

defineHChatWidget();

declare global {
  interface HTMLElementTagNameMap {
    'hchat-widget': HChatWidgetElement;
  }
}
