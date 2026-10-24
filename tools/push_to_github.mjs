/**
 * 把本机工程推到 GitHub（走 Git Data API，不依赖 git 命令行/凭据助手）。
 *
 * 用法：
 *   PowerShell:  $env:GH_TOKEN='ghp_xxx'; node tools/push_to_github.mjs
 *   可选环境变量：GH_REPO（默认 BlueBigFish001/webForTomatoFix）、GH_ROOT（默认脚本上一级）、
 *                GH_MSG（提交信息）、GH_INCLUDE_DIST=1（把 dist/ 里的 APK 也推上去）
 *
 * 只上传源码与构建原料（内置内核、gradle wrapper），跳过 build/ .gradle/ 私钥与本机路径配置。
 */
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const token = process.env.GH_TOKEN || "";
const repo = process.env.GH_REPO || "BlueBigFish001/webForTomatoFix";
const branch = process.env.GH_BRANCH || "main";
const message = process.env.GH_MSG || "feat: 原生 Compose 界面 + 内置下载内核 + 手机适配 (3.2.2)";
const includeDist = process.env.GH_INCLUDE_DIST === "1";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.GH_ROOT || path.resolve(here, "..");

if (!token) {
  console.error("缺少 GH_TOKEN 环境变量（需要 Contents: Read and write 权限）");
  process.exit(1);
}

const EXCLUDE_DIRS = new Set([".git", "build", ".gradle", ".idea", "node_modules", "_api", "_preview", "_chk", "_chk2", "_chk3"]);
const EXCLUDE_FILES = new Set(["local.properties", "release.jks", "keystore.properties"]);

async function collect(dir, relBase, out) {
  for (const entry of await fs.readdir(dir, { withFileTypes: true })) {
    const rel = relBase ? relBase + "/" + entry.name : entry.name;
    if (entry.isDirectory()) {
      if (EXCLUDE_DIRS.has(entry.name)) continue;
      if (!includeDist && relBase === "" && entry.name === "dist") continue;
      await collect(path.join(dir, entry.name), rel, out);
    } else {
      if (EXCLUDE_FILES.has(entry.name)) continue;
      out.push(rel);
    }
  }
  return out;
}

function api(pathname, init = {}) {
  return fetch("https://api.github.com" + pathname, {
    ...init,
    headers: {
      Authorization: "Bearer " + token,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "tomato-push-script",
      "Content-Type": "application/json",
      ...(init.headers || {}),
    },
  });
}

async function createBlob(file) {
  const buf = await fs.readFile(file);
  const r = await api("/repos/" + repo + "/git/blobs", {
    method: "POST",
    body: JSON.stringify({ content: buf.toString("base64"), encoding: "base64" }),
  });
  if (!r.ok) throw new Error("blob 失败 " + file + " -> " + r.status + " " + (await r.text()).slice(0, 200));
  return (await r.json()).sha;
}

async function main() {
  const files = await collect(root, "", []);
  files.sort();
  console.log("仓库:", repo, "分支:", branch);
  console.log("待上传文件:", files.length, "个，根目录:", root);

  const repoInfo = await api("/repos/" + repo);
  if (!repoInfo.ok) throw new Error("读取仓库失败：" + repoInfo.status);
  const defaultBranch = (await repoInfo.json()).default_branch || branch;

  const tree = [];
  let done = 0;
  const CONCURRENCY = 4;
  for (let i = 0; i < files.length; i += CONCURRENCY) {
    const batch = files.slice(i, i + CONCURRENCY);
    const shas = await Promise.all(batch.map((rel) => createBlob(path.join(root, rel))));
    batch.forEach((rel, k) => tree.push({ path: rel, mode: "100644", type: "blob", sha: shas[k] }));
    done += batch.length;
    if (done % 20 === 0 || done === files.length) console.log("blob", done + "/" + files.length);
  }

  const treeRes = await api("/repos/" + repo + "/git/trees", {
    method: "POST",
    body: JSON.stringify({ tree }),
  });
  if (!treeRes.ok) throw new Error("创建 tree 失败：" + treeRes.status + " " + (await treeRes.text()).slice(0, 300));
  const treeSha = (await treeRes.json()).sha;

  // 空仓库没有父提交
  let parents = [];
  const refRes = await api("/repos/" + repo + "/git/ref/heads/" + defaultBranch);
  if (refRes.ok) {
    parents = [(await refRes.json()).object.sha];
  }

  const commitRes = await api("/repos/" + repo + "/git/commits", {
    method: "POST",
    body: JSON.stringify({ message, tree: treeSha, parents }),
  });
  if (!commitRes.ok) throw new Error("创建 commit 失败：" + commitRes.status + " " + (await commitRes.text()).slice(0, 300));
  const commitSha = (await commitRes.json()).sha;
  console.log("commit:", commitSha);

  const updateRes = parents.length
    ? await api("/repos/" + repo + "/git/refs/heads/" + defaultBranch, {
        method: "PATCH",
        body: JSON.stringify({ sha: commitSha, force: true }),
      })
    : await api("/repos/" + repo + "/git/refs", {
        method: "POST",
        body: JSON.stringify({ ref: "refs/heads/" + defaultBranch, sha: commitSha }),
      });

  if (!updateRes.ok) throw new Error("更新分支失败：" + updateRes.status + " " + (await updateRes.text()).slice(0, 300));
  console.log("完成: https://github.com/" + repo);
}

main().catch((e) => {
  console.error("失败:", e.message);
  process.exit(1);
});
