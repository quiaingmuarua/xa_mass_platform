import { defineStore } from "pinia";

const STORAGE_KEY = "xa-mass-runtime-view-theme";

function initialDarkMode(): boolean {
  if (typeof window === "undefined") {
    return false;
  }
  const saved = window.localStorage.getItem(STORAGE_KEY);
  if (saved === "dark" || saved === "light") {
    return saved === "dark";
  }
  return window.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false;
}

export const useThemeStore = defineStore("theme", {
  state: () => ({
    dark: initialDarkMode()
  }),
  actions: {
    apply(): void {
      document.documentElement.classList.toggle("dark", this.dark);
      document.documentElement.dataset.theme = this.dark ? "dark" : "light";
      const themeMeta = document.querySelector<HTMLMetaElement>(
        'meta[name="theme-color"]'
      );
      themeMeta?.setAttribute("content", this.dark ? "#0c1020" : "#f4f6fb");
    },
    toggle(): void {
      this.dark = !this.dark;
      window.localStorage.setItem(STORAGE_KEY, this.dark ? "dark" : "light");
      this.apply();
    }
  }
});
