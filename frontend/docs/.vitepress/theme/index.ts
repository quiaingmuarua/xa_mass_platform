import DefaultTheme from "vitepress/theme";
import { useData, type Theme } from "vitepress";
import { defineComponent, h } from "vue";
import MermaidDiagram from "./MermaidDiagram.vue";

export default {
  extends: DefaultTheme,
  Layout: defineComponent({
    setup() {
      const { theme } = useData();
      return () =>
        h(DefaultTheme.Layout, null, {
          "nav-bar-content-after": () =>
            h("a", { href: "/", style: "margin-left:16px;font-size:14px" }, "控制台"),
          "doc-after": () =>
            h(
              "p",
              { style: "margin-top:32px;font-size:12px;color:var(--vp-c-text-2)" },
              [theme.value.footer.message, h("br"), theme.value.footer.copyright]
            )
        });
    }
  }),
  enhanceApp({ app }) {
    app.component("MermaidDiagram", MermaidDiagram);
  }
} satisfies Theme;
