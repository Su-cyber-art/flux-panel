import { useMessage } from 'naive-ui'

/**
 * 统一的轻提示封装，语义与旧版 react-hot-toast 对齐。
 * 必须在组件 setup 中调用（位于 NMessageProvider 之内）。
 */
export function useToast() {
  const message = useMessage()
  return {
    success: (content: string) => message.success(content, { duration: 2000 }),
    error: (content: string) => message.error(content, { duration: 2500 }),
    info: (content: string) => message.info(content, { duration: 2000 }),
    warning: (content: string) => message.warning(content, { duration: 3000 }),
    loading: (content: string) => message.loading(content, { duration: 0 }),
    raw: message,
  }
}
