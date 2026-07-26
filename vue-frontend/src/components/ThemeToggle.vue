<script setup lang="ts">
import { NButton, NIcon } from 'naive-ui'
import { SunnyOutline, MoonOutline } from '@vicons/ionicons5'
import { useTheme } from '@/composables/useTheme'
const { isDark, toggle } = useTheme()
</script>

<template>
  <NButton quaternary circle :title="isDark ? '切换到浅色' : '切换到深色'" @click="toggle">
    <template #icon>
      <NIcon :component="isDark ? SunnyOutline : MoonOutline" />
    </template>
  </NButton>
</template>
