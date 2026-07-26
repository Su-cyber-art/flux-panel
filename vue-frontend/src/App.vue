<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import {
  NConfigProvider,
  NMessageProvider,
  NDialogProvider,
  NNotificationProvider,
  NLoadingBarProvider,
  NGlobalStyle,
  zhCN,
  dateZhCN,
} from 'naive-ui'
import { useTheme } from '@/composables/useTheme'
import { useH5 } from '@/composables/useH5'
import { useConfigStore } from '@/stores/config'
import { lightThemeOverrides, darkThemeOverrides } from '@/theme'

import AdminLayout from '@/layouts/AdminLayout.vue'
import H5Layout from '@/layouts/H5Layout.vue'
import H5SimpleLayout from '@/layouts/H5SimpleLayout.vue'
import BlankLayout from '@/layouts/BlankLayout.vue'

const route = useRoute()
const { naiveTheme, isDark } = useTheme()
const { isH5 } = useH5()
const configStore = useConfigStore()

const themeOverrides = computed(() => (isDark.value ? darkThemeOverrides : lightThemeOverrides))

const layoutComponent = computed(() => {
  const layout = (route.meta.layout as string) || 'main'
  if (layout === 'blank') return BlankLayout
  if (layout === 'simple') return isH5.value ? H5SimpleLayout : AdminLayout
  return isH5.value ? H5Layout : AdminLayout
})

onMounted(() => {
  configStore.applyTitle()
  setTimeout(() => configStore.syncAppName(), 120)
})
</script>

<template>
  <NConfigProvider
    :theme="naiveTheme"
    :theme-overrides="themeOverrides"
    :locale="zhCN"
    :date-locale="dateZhCN"
  >
    <NGlobalStyle />
    <NLoadingBarProvider>
      <NMessageProvider placement="top" :max="4">
        <NDialogProvider>
          <NNotificationProvider>
            <component :is="layoutComponent">
              <router-view v-slot="{ Component }">
                <transition name="fade-slide" mode="out-in">
                  <component :is="Component" />
                </transition>
              </router-view>
            </component>
          </NNotificationProvider>
        </NDialogProvider>
      </NMessageProvider>
    </NLoadingBarProvider>
  </NConfigProvider>
</template>
