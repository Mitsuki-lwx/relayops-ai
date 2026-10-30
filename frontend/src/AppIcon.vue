<script setup lang="ts">
defineProps<{ name: string }>();
const paths: Record<string, string> = {
  tasks: "M4 5h16v14H4z M8 9h8 M8 13h6",
  plus: "M12 5v14 M5 12h14",
  team: "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M16 3a4 4 0 0 1 0 8 M22 21v-2a4 4 0 0 0-3-3.87 M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0",
  search: "M21 21l-5-5 M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0",
  menu: "M4 6h16 M4 12h16 M4 18h16",
  arrow: "M7 12h10 M13 8l4 4-4 4",
  back: "M19 12H5 M10 7l-5 5 5 5",
  refresh:
    "M20 7v5h-5 M4 17v-5h5 M6 7a7 7 0 0 1 12-1l2 3 M18 17A7 7 0 0 1 6 18l-2-3",
  logout: "M9 5H4v14h5 M13 8l4 4-4 4 M8 12h12",
};
</script>
<template>
  <svg
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.6"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path :d="paths[name] ?? paths.tasks" />
  </svg>
</template>
