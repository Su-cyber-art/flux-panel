import type { GlobalThemeOverrides } from 'naive-ui'

// Keep the remaining Naive UI screens aligned while the shadcn migration proceeds.
const common = {
  borderRadius: '8px',
  fontFamily: '"Geist Variable", "PingFang SC", "Microsoft YaHei", system-ui, sans-serif',
  successColor: '#16a34a',
  warningColor: '#d97706',
  errorColor: '#dc2626',
  infoColor: '#71717a',
}
export const lightThemeOverrides: GlobalThemeOverrides = {
  common: {
    ...common,
    primaryColor: '#18181b', primaryColorHover: '#3f3f46', primaryColorPressed: '#09090b', primaryColorSuppl: '#18181b',
    bodyColor: '#ffffff', cardColor: '#ffffff', modalColor: '#ffffff', popoverColor: '#ffffff',
    borderColor: '#e4e4e7', textColorBase: '#18181b',
  },
  Card: { borderRadius: '12px' },
  Button: { borderRadiusMedium: '8px' },
}
export const darkThemeOverrides: GlobalThemeOverrides = {
  common: {
    ...common,
    primaryColor: '#e4e4e7', primaryColorHover: '#fafafa', primaryColorPressed: '#d4d4d8', primaryColorSuppl: '#e4e4e7',
    bodyColor: '#0f0f10', cardColor: '#18181b', modalColor: '#18181b', popoverColor: '#18181b',
    borderColor: '#2c2c30', textColorBase: '#f4f4f5',
  },
  Card: { borderRadius: '12px', color: '#18181b' },
  Button: { borderRadiusMedium: '8px', textColorPrimary: '#18181b', textColorHoverPrimary: '#18181b', textColorPressedPrimary: '#18181b' },
}
