<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { BookOpen, Check, ChevronDown, ChevronRight, Command, ExternalLink, KeyRound, LogOut, Menu, Monitor, Moon, PanelLeftClose, PanelLeftOpen, Sun, UserRound } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import SidebarNavigation from './SidebarNavigation.vue'
import ChangePasswordModal from '@/components/ChangePasswordModal.vue'
import { useAuthStore } from '@/stores/auth'
import { useConfigStore } from '@/stores/config'
import { useTheme } from '@/composables/useTheme'
const auth = useAuthStore()
const config = useConfigStore()
const route = useRoute()
const router = useRouter()
const { isDark, mode, setMode } = useTheme()
const isPreview = import.meta.env.DEV && import.meta.env.VITE_UI_PREVIEW === 'true'
const compact = ref(localStorage.getItem('sidebar-compact') === 'true')
const drawerOpen = ref(false)
const passwordOpen = ref(false)
const pageTitle = computed(() => String(route.meta.title || '控制台'))
const brand = computed(() => /^(flux|flux-panel)$/i.test(config.name) ? 'flux' : config.name)
const version = computed(() => config.version === 'dev' ? '' : 'v' + config.version)
function toggleSidebar() { compact.value = !compact.value; localStorage.setItem('sidebar-compact', String(compact.value)) }
function logout() { auth.logout(); router.push('/') }
</script>

<template>
  <div class="min-h-dvh bg-background text-foreground">
    <aside class="fixed inset-y-0 left-0 z-30 hidden flex-col border-r bg-sidebar transition-[width] duration-200 lg:flex" :class="compact ? 'w-[72px]' : 'w-[232px]'">
      <RouterLink to="/dashboard" class="flex h-[72px] shrink-0 items-center gap-2.5 px-5" :aria-label="brand + '首页'">
        <div class="flex size-8 shrink-0 items-center justify-center rounded-lg bg-foreground text-background"><Command class="size-[19px]" /></div>
        <span v-if="!compact" class="truncate text-[17px] font-semibold tracking-tight">{{ brand }}<span v-if="brand === 'flux'" class="ml-1 font-normal text-muted-foreground">panel</span></span>
      </RouterLink>
      <div class="min-h-0 flex-1 overflow-y-auto px-3 py-3"><SidebarNavigation :compact="compact" /></div>
      <div class="space-y-3 p-3">
        <a v-if="!compact" href="https://github.com/Su-cyber-art/flux-panel" target="_blank" rel="noopener noreferrer" class="flex items-center gap-3 rounded-lg px-3 py-2.5 text-xs text-muted-foreground hover:bg-sidebar-accent hover:text-foreground"><BookOpen class="size-4" />帮助与文档<ExternalLink class="ml-auto size-3" /></a>
        <DropdownMenu>
          <DropdownMenuTrigger as-child>
            <button class="flex w-full items-center gap-2.5 rounded-lg border bg-background/70 p-2 text-left hover:bg-accent" :class="compact ? 'justify-center border-transparent' : ''" aria-label="账户菜单">
              <span class="flex size-8 shrink-0 items-center justify-center rounded-md bg-muted text-xs font-semibold">{{ (auth.name || 'A').slice(0, 1).toUpperCase() }}</span>
              <span v-if="!compact" class="min-w-0 flex-1"><span class="block truncate text-xs font-medium">{{ auth.name }}</span><span class="mt-0.5 block text-[11px] text-muted-foreground">{{ auth.isAdmin ? '管理员' : '成员' }}</span></span>
              <ChevronDown v-if="!compact" class="size-3.5 text-muted-foreground" />
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent side="right" align="end" class="w-48">
            <DropdownMenuLabel>{{ auth.name }}</DropdownMenuLabel><DropdownMenuSeparator />
            <DropdownMenuItem @select="router.push('/profile')"><UserRound class="size-4" />我的账户</DropdownMenuItem>
            <DropdownMenuItem @select="passwordOpen = true"><KeyRound class="size-4" />修改密码</DropdownMenuItem>
            <DropdownMenuSeparator /><DropdownMenuItem @select="logout"><LogOut class="size-4" />退出登录</DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
        <p v-if="!compact && version" class="px-3 text-[10px] text-muted-foreground/65">Flux Panel <span class="float-right">{{ version }}</span></p>
      </div>
    </aside>

    <div class="min-w-0 transition-[padding] duration-200" :class="compact ? 'lg:pl-[72px]' : 'lg:pl-[232px]'">
      <header class="sticky top-0 z-20 flex h-[60px] items-center gap-3 border-b bg-background/95 px-4 backdrop-blur-sm sm:px-6">
        <Button variant="ghost" size="icon" class="hidden size-8 text-muted-foreground lg:inline-flex" :aria-label="compact ? '展开侧栏' : '收起侧栏'" @click="toggleSidebar"><PanelLeftOpen v-if="compact" class="size-4" /><PanelLeftClose v-else class="size-4" /></Button>
        <Button variant="ghost" size="icon" class="size-8 lg:hidden" aria-label="打开导航" @click="drawerOpen = true"><Menu class="size-5" /></Button>
        <div class="mr-1 hidden h-4 border-l lg:block" />
        <div class="flex min-w-0 items-center gap-2 text-xs"><span class="hidden text-muted-foreground sm:inline">控制台</span><ChevronRight class="hidden size-3 text-muted-foreground/60 sm:block" /><span class="truncate font-medium">{{ pageTitle }}</span></div>
        <div class="ml-auto flex items-center gap-2"><span v-if="isPreview" class="mr-1 rounded-md border px-2 py-1 text-[10px] text-muted-foreground">预览数据</span>
          <DropdownMenu><DropdownMenuTrigger as-child><Button variant="ghost" size="icon" class="size-8 text-muted-foreground" aria-label="切换外观"><Moon v-if="isDark" class="size-4" /><Sun v-else class="size-4" /></Button></DropdownMenuTrigger>
            <DropdownMenuContent align="end" class="w-36"><DropdownMenuItem @select="setMode('light')"><Sun class="size-4" />浅色<Check v-if="mode === 'light'" class="ml-auto size-3" /></DropdownMenuItem><DropdownMenuItem @select="setMode('dark')"><Moon class="size-4" />深色<Check v-if="mode === 'dark'" class="ml-auto size-3" /></DropdownMenuItem><DropdownMenuItem @select="setMode('auto')"><Monitor class="size-4" />跟随系统<Check v-if="mode === 'auto'" class="ml-auto size-3" /></DropdownMenuItem></DropdownMenuContent>
          </DropdownMenu>
          <Button variant="ghost" size="icon" class="size-8 lg:hidden" aria-label="我的账户" @click="router.push('/profile')"><UserRound class="size-4" /></Button>
        </div>
      </header>
      <main class="min-h-[calc(100dvh-60px)]"><slot /></main>
    </div>

    <Sheet v-model:open="drawerOpen"><SheetContent side="left" class="gap-0 bg-sidebar p-0 data-[side=left]:w-[280px] data-[side=left]:sm:max-w-[280px]">
      <SheetHeader class="border-b p-6"><SheetTitle class="flex items-center gap-2 text-lg"><Command class="size-5" />{{ brand }}{{ brand === 'flux' ? ' panel' : '' }}</SheetTitle><SheetDescription>转发与网络管理</SheetDescription></SheetHeader>
      <div class="flex-1 overflow-y-auto p-4"><SidebarNavigation @navigate="drawerOpen = false" /></div>
      <div class="border-t p-4"><Button variant="ghost" class="w-full justify-start" @click="logout"><LogOut class="size-4" />退出登录</Button></div>
    </SheetContent></Sheet>
    <ChangePasswordModal v-model:show="passwordOpen" />
  </div>
</template>
