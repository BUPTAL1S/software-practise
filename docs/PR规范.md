# PR 规范

三人小仓，用 GitHub Flow：从 `main` 拉分支，PR 合回 `main`，不要直推 `main`（仓库管理员首次初始化除外）。

## 分支

按角色起名，短分支，合完就删：

| 前缀 | 例子 |
|---|---|
| `client/` | `client/login-ui` |
| `server/` | `server/kamailio-register` |
| `admin/` | `admin/cdr-api` |
| `docs/` | `docs/phase-notes` |

不要从别人的功能分支拉新分支。不要把 `pjproject` / Kamailio / FreeSWITCH 源码推进本仓。

## 不要提交

- `native/` 下的 `.dll` / `.dylib` / `.so`
- `target/`、IDE 配置、本机 `.env`、MySQL 数据卷
- 上游整仓拷贝

## PR 怎么写

标题一行说清改了什么，用前缀：

- `client: 登录窗口能 REGISTER`
- `server: room- 前缀转到 FreeSWITCH`
- `admin: 文件上传接口`
- `docs: 补阶段验收说明`

正文只写三块（没有就写「无」）：

```
做了什么：
怎么验：
影响谁：客户端 / 信令 / 后台（改了接口必须 @ 对方）
```

跨角色接口（SIP 账号、房间 URI、HTTP 字段、ESL 密码）没有对面说一声，不要合。

## 评审与合并

- 只动自己目录的小改：另一人看一眼即可
- 改了 `docs/整体规划.md` 或三份分工里的接口表：三人都看
- 合入用 merge commit 或 squash，不要 rebase 别人的 `main`
- 不要 `push --force` 到 `main`

## 本机流程

```bash
git checkout main
git pull
git checkout -b client/xxx
# 改完
git add -p
git commit
git push -u origin HEAD
gh pr create --title "client: xxx" --body "$(cat <<'EOF'
做了什么：
怎么验：
影响谁：
EOF
)"
```
