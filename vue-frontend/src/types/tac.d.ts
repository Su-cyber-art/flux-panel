export {}
declare global {
  interface Window {
    TAC?: any
    currentCaptcha?: any
    currentCaptchaRes?: any
    setPanelAddresses?: (list: any[]) => void
    setAddresses?: (list: any[]) => void
    JsInterface?: any
    webkit?: any
    $message?: any
  }
}
