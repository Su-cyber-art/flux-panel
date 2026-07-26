import { onMounted, onUnmounted, ref } from 'vue'

function detectH5(): boolean {
  const isMobile = window.innerWidth <= 768
  const isMobileBrowser = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(
    navigator.userAgent,
  )
  const isH5Param = new URLSearchParams(location.search).get('h5') === 'true'
  return isMobile || isMobileBrowser || isH5Param
}

/** 响应式 H5 模式检测（跟随窗口尺寸变化） */
export function useH5() {
  const isH5 = ref(detectH5())
  const onResize = () => {
    isH5.value = detectH5()
  }
  onMounted(() => window.addEventListener('resize', onResize))
  onUnmounted(() => window.removeEventListener('resize', onResize))
  return { isH5 }
}
