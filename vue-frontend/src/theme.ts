import type { GlobalThemeOverrides } from 'naive-ui'

const common = {
  primaryColor: '#2563eb',
  primaryColorHover: '#3b82f6',
  primaryColorPressed: '#1d4ed8',
  primaryColorSuppl: '#3b82f6',
  successColor: '#18a058',
  warningColor: '#f0a020',
  errorColor: '#e04141',
  infoColor: '#2563eb',
  borderRadius: '10px',
  fontFamily:
    "Inter, 'Segoe UI', system-ui, -apple-system, 'PingFang SC', 'Microsoft YaHei', sans-serif",
}

export const lightThemeOverrides: GlobalThemeOverrides = {
  common: {
    ...common,
    bodyColor: '#f4f6fb',
    cardColor: '#ffffff',
    modalColor: '#ffffff',
    popoverColor: '#ffffff',
  },
  Card: {
    borderRadius: '16px',
  },
  Button: {
    borderRadiusMedium: '10px',
  },
}

export const darkThemeOverrides: GlobalThemeOverrides = {
  common: {
    ...common,
    bodyColor: '#0b1120',
    cardColor: '#131a2a',
    modalColor: '#131a2a',
    popoverColor: '#1b2436',
    borderColor: 'rgba(148, 163, 184, 0.16)',
  },
  Card: {
    borderRadius: '16px',
    color: '#131a2a',
  },
  Button: {
    borderRadiusMedium: '10px',
  },
}
