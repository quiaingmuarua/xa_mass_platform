import { defineConfig } from "vitepress";
import { createRequire } from "node:module";
import path from "node:path";
import {
  buildIdentity,
  developmentWatchExcludes,
  documents,
  frontendRoot,
  githubSlug,
  homeSource,
  ignoredSourceExcludes,
  repositoryRoot,
  rewriteLink,
  sourceExcludes,
  tokenize
} from "../site.mjs";

const identity = buildIdentity();
const published = new Set(documents());
const page = (name: string) => `/doc/guide/${name}.html`;
const require = createRequire(import.meta.url);

export default defineConfig({
  lang: "zh-CN",
  title: "XA Mass",
  description: "当前构建的使用、开发、机制与能力证据指南",
  base: "/docs/",
  cleanUrls: false,
  srcDir: repositoryRoot,
  srcExclude: [...sourceExcludes, ...ignoredSourceExcludes()],
  rewrites: { [homeSource]: "index.md" },
  outDir: path.join(frontendRoot, ".vitepress-dist"),
  cacheDir: path.join(frontendRoot, ".vitepress-cache"),
  head: [
    ["meta", { name: "xa-mass-docs-commit", content: identity.commit }],
    ["meta", { name: "xa-mass-docs-worktree", content: String(identity.dirty) }]
  ],
  sitemap: undefined,
  vite: {
    publicDir: false,
    plugins: [
      {
        name: "xa-mass-docs-dependency-root",
        apply: "serve",
        async configResolved(config) {
          // Vite's nested include resolver starts at srcDir. The packages belong
          // to frontend's VitePress installation, not the repository root.
          const resolve = config.createResolver({ asSrc: false, scan: true });
          const aliases = [];
          const includes = [];
          for (const id of config.optimizeDeps.include ?? []) {
            if (!id.startsWith("vitepress > ")) {
              includes.push(id);
              continue;
            }
            const dependency = id.slice("vitepress > ".length);
            const resolved = await resolve(
              dependency,
              require.resolve("vitepress/package.json")
            );
            if (!resolved)
              throw new Error(`Cannot resolve VitePress dependency: ${id}`);
            aliases.push({
              find: new RegExp(
                `^${dependency.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}$`
              ),
              replacement: resolved
            });
            includes.push(dependency);
          }
          config.resolve.alias.push(...aliases);
          config.optimizeDeps.include = includes;
        }
      }
    ],
    server: {
      host: "127.0.0.1",
      port: 5174,
      strictPort: true,
      hmr: { path: "/__vite_hmr" },
      watch: { ignored: developmentWatchExcludes() }
    },
    resolve: {
      // Source pages live outside frontend; resolve their runtime from this lockfile.
      alias: [
        {
          find: "vue/server-renderer",
          replacement: require.resolve("vue/server-renderer")
        },
        {
          find: /^vue$/,
          replacement: require.resolve("vue/dist/vue.runtime.esm-bundler.js")
        }
      ]
    }
  },
  markdown: {
    anchor: { slugify: githubSlug },
    config(md) {
      const link = md.renderer.rules.link_open!;
      md.renderer.rules.link_open = (tokens, index, options, env, renderer) => {
        const token = tokens[index];
        const source =
          env.relativePath === "index.md"
            ? homeSource
            : (env.relativePath || path.relative(repositoryRoot, env.path)).replaceAll(
                "\\",
                "/"
              );
        const original = token.attrGet("href");
        if (original) {
          if (original.startsWith("/")) token.attrSet("target", "_self");
          token.attrSet(
            "href",
            rewriteLink(source, original, identity.commit, published)
          );
        }
        return link(tokens, index, options, env, renderer);
      };
      const fence = md.renderer.rules.fence!;
      md.renderer.rules.fence = (tokens, index, options, env, renderer) =>
        tokens[index].info.trim() === "mermaid"
          ? `<MermaidDiagram encoded="${Buffer.from(tokens[index].content).toString("base64")}" />`
          : fence(tokens, index, options, env, renderer);
    }
  },
  themeConfig: {
    siteTitle: "XA Mass · 项目文档",
    nav: [
      { text: "开始使用", link: page("quick-start") },
      { text: "开发指南", link: page("development/index") },
      { text: "架构", link: page("architecture") },
      { text: "能力与验证", link: page("capabilities") }
    ],
    sidebar: [
      {
        text: "快速开始",
        items: [
          { text: "项目用途与入口", link: "/index.html" },
          { text: "运行本地环境", link: page("quick-start") },
          { text: "完成第一个任务", link: page("first-task") }
        ]
      },
      {
        text: "功能使用",
        items: [
          { text: "批量检查", link: page("usage/batch-checks") },
          { text: "Messages", link: page("usage/messages") },
          { text: "SMS", link: page("usage/sms") },
          { text: "原始任务与定向调试", link: page("usage/workbench") }
        ]
      },
      {
        text: "部署与排障",
        items: [
          { text: "运行与交付形态", link: page("operations/deploy") },
          { text: "配置、生效与数据", link: page("operations/configuration") },
          { text: "按症状定位", link: page("operations/troubleshooting") }
        ]
      },
      {
        text: "开发指南",
        items: [
          { text: "从需求定位实现", link: page("development/index") },
          { text: "跟读 MD5 检查链路", link: page("development/walkthrough") },
          { text: "仓库修改契约", link: "/AGENTS.html" }
        ]
      },
      {
        text: "架构与机制",
        collapsed: true,
        items: [
          { text: "架构总览", link: page("architecture") },
          { text: "完整行为主线", link: "/doc/kernel/scheduling-overview.html" },
          { text: "Owner 文档目录", link: "/doc/README.html" },
          { text: "Kernel 与 Pacer 索引", link: "/doc/kernel/README.html" },
          { text: "事件目录", link: "/transport/EVENTS.html" }
        ]
      },
      {
        text: "能力与验证",
        items: [
          { text: "怎样阅读 proof", link: page("verification") },
          { text: "能力与实测记录", link: page("capabilities") },
          { text: "Proof Registry", link: "/doc/testing/proof-registry.html" },
          { text: "验证命令与选择", link: "/TESTING.html" }
        ]
      }
    ],
    outline: { label: "本页内容", level: [2, 3] },
    docFooter: { prev: "上一页", next: "下一页" },
    darkModeSwitchLabel: "切换主题",
    sidebarMenuLabel: "目录",
    returnToTopLabel: "返回顶部",
    footer: {
      message: `当前构建 ${identity.commit.slice(0, 9)}${identity.dirty ? " · 包含工作区修改" : ""} · API 持续演进`,
      copyright: "内容与本构建对应；历史通过 Git 与发行记录追溯"
    },
    search: {
      provider: "local",
      options: {
        miniSearch: { options: { tokenize } },
        translations: {
          button: { buttonText: "搜索文档", buttonAriaLabel: "搜索文档" },
          modal: {
            noResultsText: "没有找到结果",
            resetButtonTitle: "清除搜索",
            displayDetails: "显示详情",
            footer: { selectText: "选择", navigateText: "切换", closeText: "关闭" }
          }
        }
      }
    }
  }
});
