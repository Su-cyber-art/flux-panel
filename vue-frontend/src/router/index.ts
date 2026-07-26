import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { isLoggedIn } from '@/utils/auth'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'login',
    component: () => import('@/pages/Login.vue'),
    meta: { layout: 'blank', public: true },
  },
  {
    path: '/change-password',
    name: 'change-password',
    component: () => import('@/pages/ChangePassword.vue'),
    meta: { layout: 'blank', requiresAuth: true },
  },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('@/pages/Settings.vue'),
    meta: { layout: 'blank' },
  },
  {
    path: '/dashboard',
    name: 'dashboard',
    component: () => import('@/pages/Dashboard.vue'),
    meta: { layout: 'main', requiresAuth: true, title: '仪表板' },
  },
  {
    path: '/forward',
    name: 'forward',
    component: () => import('@/pages/Forward.vue'),
    meta: { layout: 'main', requiresAuth: true, title: '转发管理' },
  },
  {
    path: '/tunnel',
    name: 'tunnel',
    component: () => import('@/pages/Tunnel.vue'),
    meta: { layout: 'main', requiresAuth: true, title: '隧道管理' },
  },
  {
    path: '/node',
    name: 'node',
    component: () => import('@/pages/Node.vue'),
    meta: { layout: 'main', requiresAuth: true, title: '节点监控' },
  },
  {
    path: '/user',
    name: 'user',
    component: () => import('@/pages/User.vue'),
    meta: { layout: 'simple', requiresAuth: true, title: '用户管理' },
  },
  {
    path: '/limit',
    name: 'limit',
    component: () => import('@/pages/Limit.vue'),
    meta: { layout: 'simple', requiresAuth: true, title: '限速管理' },
  },
  {
    path: '/config',
    name: 'config',
    component: () => import('@/pages/Config.vue'),
    meta: { layout: 'simple', requiresAuth: true, title: '网站配置' },
  },
  {
    path: '/profile',
    name: 'profile',
    component: () => import('@/pages/Profile.vue'),
    meta: { layout: 'main', requiresAuth: true, title: '我的' },
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
]

const router = createRouter({
  history: createWebHistory('/'),
  routes,
  scrollBehavior() {
    return { top: 0 }
  },
})

router.beforeEach((to) => {
  const logged = isLoggedIn()
  if (to.meta.requiresAuth && !logged) {
    return { path: '/', replace: true }
  }
  if (to.path === '/' && logged) {
    return { path: '/dashboard', replace: true }
  }
  return true
})

export default router
