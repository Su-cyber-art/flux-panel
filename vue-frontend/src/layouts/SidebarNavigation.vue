<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowRightLeft, Gauge, LayoutDashboard, Network, Server, Settings2, Users, Waypoints } from '@lucide/vue'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { useAuthStore } from '@/stores/auth'
defineProps<{ compact?: boolean }>()
const emit = defineEmits<{ (e: 'navigate'): void }>()
const auth = useAuthStore()
const route = useRoute()
const groups = computed(() => [
  { label: '工作台', items: [
    { path: '/dashboard', label: '概览', icon: LayoutDashboard },
    { path: '/forward', label: '转发管理', icon: ArrowRightLeft },
  ] },
  ...(auth.isAdmin ? [
    { label: '网络资源', items: [
      { path: '/tunnel', label: '隧道管理', icon: Waypoints },
      { path: '/node', label: '节点监控', icon: Server },
    ] },
    { label: '管理', items: [
      { path: '/user', label: '用户管理', icon: Users },
      { path: '/limit', label: '限速规则', icon: Gauge },
      { path: '/config', label: '网站配置', icon: Settings2 },
    ] },
  ] : []),
])
</script>
<template>
  <nav class="space-y-7" aria-label="主导航">
    <div v-for="group in groups" :key="group.label">
      <div v-if="!compact" class="mb-2 px-3 text-[11px] font-medium tracking-wide text-muted-foreground/75">{{ group.label }}</div>
      <div v-else class="mx-2 mb-3 border-t" />
      <div class="space-y-1">
        <Tooltip v-for="item in group.items" :key="item.path">
          <TooltipTrigger as-child>
            <RouterLink :to="item.path" class="flex h-10 items-center gap-3 rounded-lg px-3 text-[13px] font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              :class="[route.path === item.path ? 'bg-sidebar-accent text-sidebar-accent-foreground' : 'text-muted-foreground hover:bg-sidebar-accent/70 hover:text-foreground', compact ? 'justify-center px-0' : '']"
              :aria-label="item.label" :aria-current="route.path === item.path ? 'page' : undefined" @click="emit('navigate')">
              <component :is="item.icon" class="size-[17px] shrink-0" />
              <span v-if="!compact">{{ item.label }}</span>
              <span v-if="!compact && route.path === item.path" class="ml-auto size-1.5 rounded-full bg-foreground/60" />
            </RouterLink>
          </TooltipTrigger>
          <TooltipContent v-if="compact" side="right">{{ item.label }}</TooltipContent>
        </Tooltip>
      </div>
    </div>
  </nav>
</template>
