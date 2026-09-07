import { toast } from 'vue-sonner'

export function useToast() {
  return {
    success: (content: string) => toast.success(content, { duration: 2500 }),
    error: (content: string) => toast.error(content, { duration: 3500 }),
    info: (content: string) => toast.info(content, { duration: 2500 }),
    warning: (content: string) => toast.warning(content, { duration: 3500 }),
    loading: (content: string) => toast.loading(content),
    raw: toast,
  }
}
