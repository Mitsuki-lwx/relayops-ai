import { defineConfig, loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig(({ mode }) => {
  const apiBase =
    loadEnv(mode, ".", "RELAYOPS_").RELAYOPS_API_BASE ?? "http://127.0.0.1:18080";
  return {
    plugins: [vue()],
    server: {
      proxy: {
        "/api": apiBase,
        "/auth": apiBase,
      },
    },
  };
});
