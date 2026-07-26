<script setup lang="ts">
defineProps<{ size?: number }>()
</script>

<template>
  <svg
    :width="size || 30"
    :height="size || 30"
    viewBox="0 0 32 32"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
  >
    <defs>
      <linearGradient id="fx-logo-g" x1="0" y1="0" x2="32" y2="32" gradientUnits="userSpaceOnUse">
        <stop stop-color="#3b82f6" />
        <stop offset="1" stop-color="#1d4ed8" />
      </linearGradient>
    </defs>
    <rect x="1.5" y="1.5" width="29" height="29" rx="8" fill="url(#fx-logo-g)" />
    <path
      d="M11 9h10l-3.2 5.2H22L12 24l2.2-7.2H10.4L11 9z"
      fill="#fff"
      fill-opacity="0.95"
    />
  </svg>
</template>
