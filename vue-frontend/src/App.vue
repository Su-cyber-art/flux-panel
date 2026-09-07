<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { NConfigProvider, NDialogProvider, NGlobalStyle, zhCN, dateZhCN } from 'naive-ui'
import { TooltipProvider } from '@/components/ui/tooltip'
import { Toaster } from '@/components/ui/sonner'
import { useTheme } from '@/composables/useTheme'
import { useConfigStore } from '@/stores/config'
import { lightThemeOverrides, darkThemeOverrides } from '@/theme'
import AdminLayout from '@/layouts/AdminLayout.vue'
import BlankLayout from '@/layouts/BlankLayout.vue'
const route = useRoute()
const { naiveTheme, isDark } = useTheme()
const config = useConfigStore()
const themeOverrides = computed(() => isDark.value ? darkThemeOverrides : lightThemeOverrides)
const layout = computed(() => route.meta.layout === 'blank' ? BlankLayout : AdminLayout)
onMounted(() => { config.applyTitle(); config.syncAppName() })
</script>
<template>
  <NConfigProvider :theme="naiveTheme" :theme-overrides="themeOverrides" :locale="zhCN" :date-locale="dateZhCN">
    <NGlobalStyle />
    <NDialogProvider><TooltipProvider :delay-duration="200">
      <component :is="layout"><RouterView /></component>
      <Toaster :theme="isDark ? 'dark' : 'light'" position="bottom-right" :close-button="true" :toast-options="{ classes: { toast: 'rounded-lg' } }" />
    </TooltipProvider></NDialogProvider>
  </NConfigProvider>
</template>
