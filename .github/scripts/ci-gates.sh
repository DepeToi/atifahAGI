#!/usr/bin/env bash
# ci-gates.sh — CI 门禁统一入口（ADR-0001 R6；规则依据 docs/review/standards.md §5）
# 用法：ci-gates.sh <detect|scope|static|task|size|compose>
#   detect  变更分类：docs_only / deploy_changed 写入 GITHUB_OUTPUT（docs-only PR 跳过构建 job）
#   scope   PR 类型与路径一致性（SCOPE-01、GIT-07）+ Feature 任务包与「允许修改」校验
#           （SCOPE-03 受保护文件授权与 DOC-01 契约变更流程由 Codex 评审覆盖，本脚本未实现）
#   static  静态规则，只查 diff 新增行（DB-02、CODE-01/04/08、ARCH-03/04）
#   task    任务包 front matter 与基线标签（DOC-04 机械部分）
#   size    生产语义 diff ≤400 行近似统计（SCOPE-02）
#   compose docker compose 配置语法校验（Ops B 级验证自动化）
# 环境：BASE_REF（对比基准，compose 除外）；PR_BODY（scope 用，push 事件留空即跳过）；
#       PR_LABELS（scope 用，逗号分隔；status-batch 为批量 done 收口例外）
set -uo pipefail

cmd="${1:?用法: ci-gates.sh <detect|scope|static|task|size|compose>}"
BASE_REF="${BASE_REF:-}"
PR_BODY="${PR_BODY:-}"
# PR 标签（逗号分隔）：status-batch 为维护者批量 done 收口例外，见 GIT-02
PR_LABELS="${PR_LABELS:-}"
workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

base_ok() { [ -n "$BASE_REF" ] && git rev-parse -q --verify "${BASE_REF}^{commit}" >/dev/null 2>&1; }
changed() { git diff --name-only "${BASE_REF}...HEAD" "$@"; }

fm_get() { printf '%s\n' "$2" | sed -n "s/^${1}:[[:space:]]*//p" | head -n1; }

# 文件 → 变更分类（未列出的根级文件默认按 Ops，见 AGENTS.md §0）
cat_of() {
  case "$1" in
    docs/tasks/T-*.md) echo task ;;
    docs/contracts/*|docs/rules/*|docs/adr/*|pom.xml|CLAUDE.md|AGENTS.md|*/AGENTS.md|\
atifah-common/*|atifah-gateway/*|\
docs/tasks/README.md|docs/tasks/_TEMPLATE.md|\
docs/review/standards.md|docs/review/report-template.md|docs/review/reports/README.md) echo arch ;;
    .github/*|deploy/*|*Dockerfile*|*.dockerignore) echo ops ;;
    docs/*|*.md|TODO.md) echo docs ;;
    atifah-services/*|atifah-admin-web/*|atifah-app/*) echo feat ;;
    *) echo ops ;;
  esac
}

# ---------- detect：变更分类，供 ci.yml 决定跳过哪些 job ----------
do_detect() {
  local docs_only=false deploy_changed=false f empty=true
  if ! base_ok; then
    echo "WARN: base ref 不存在，按非 docs 处理兜底" >&2
  else
    docs_only=true
    while IFS= read -r f; do
      [ -z "$f" ] && continue
      empty=false
      case "$f" in
        deploy/*) deploy_changed=true; docs_only=false ;;
        docs/*|*.md) ;;                 # 文档路径不影响 docs_only 判定
        *) docs_only=false ;;
      esac
    done < <(changed)
    if [ "$empty" = true ]; then docs_only=false; fi   # 空 diff 兜底跑全量
  fi
  {
    echo "docs_only=${docs_only}"
    echo "deploy_changed=${deploy_changed}"
  } >> "${GITHUB_OUTPUT:-/dev/null}"
  if [ -z "${GITHUB_OUTPUT:-}" ]; then
    echo "docs_only=${docs_only} deploy_changed=${deploy_changed}"
  fi
}

# ---------- scope：变更类型与路径一致性 + Feature 任务包校验 ----------
do_scope() {
  if ! base_ok; then echo "WARN: base ref 不存在，跳过 scope"; return 0; fi
  if [ -z "$PR_BODY" ]; then
    echo "INFO: 非 PR 事件（无 PR 描述），跳过 scope（受保护分支由分支保护兜底）"
    return 0
  fi
  local files; files=$(changed)
  if [ -z "$files" ]; then echo "INFO: 无变更，跳过 scope"; return 0; fi

  # PR 类型：模板行「- 类型（必填）：X」，占位多选/未填均拒绝（GIT-02/DOC-03）
  local type
  type=$(printf '%s\n' "$PR_BODY" | grep -m1 '类型（必填）' | grep -oE 'Feature|Arch|Ops|Docs' | sort -u || true)
  if [ "$(printf '%s' "$type" | wc -w)" -ne 1 ]; then
    echo "[FAIL] SCOPE: PR 描述「类型（必填）」须且只能填一项 Feature/Arch/Ops/Docs（GIT-02/DOC-03）"
    return 1
  fi

  # 类型 → 允许的路径类别；混类一律拒绝（GIT-07：一个 PR 只做一件事）
  # status-batch 为维护者批量 done 收口例外（GIT-02）：额外放行任务包路径，且只允许任务包变更
  local ok status_batch=false
  case ",${PR_LABELS}," in
    *,status-batch,*) status_batch=true ;;
  esac
  case "$type" in
    Arch)    ok="arch docs" ;;
    Ops)     ok="ops docs" ;;
    Docs)    ok="docs" ;;
    Feature) ok="task feat" ;;
  esac
  if [ "$status_batch" = true ]; then
    ok="$ok task"
  fi

  local f c fails=0 arch_n=0 ops_n=0 docs_n=0 feat_n=0 task_n=0
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    c=$(cat_of "$f")
    case "$c" in
      arch) arch_n=$((arch_n + 1)) ;;  ops)  ops_n=$((ops_n + 1)) ;;
      docs) docs_n=$((docs_n + 1)) ;;  feat) feat_n=$((feat_n + 1)) ;;
      task) task_n=$((task_n + 1)) ;;
    esac
    case " $ok " in
      *" $c "*) ;;
      *) echo "[FAIL] SCOPE: $f 属于 ${c} 类路径，与 PR 类型 ${type} 不符（允许：${ok}；SCOPE-01/03、GIT-07）"; fails=1 ;;
    esac
  done < <(printf '%s\n' "$files")

  echo "变更分类统计：arch=${arch_n} ops=${ops_n} docs=${docs_n} feature=${feat_n} task=${task_n}（PR 类型：${type}）"

  # status-batch 例外只允许改任务包（GIT-02），夹带其他路径直接拒绝
  if [ "$status_batch" = true ]; then
    while IFS= read -r f; do
      [ -z "$f" ] && continue
      case "$f" in
        docs/tasks/T-*.md) ;;
        *) echo "[FAIL] SCOPE: status-batch PR 只允许修改任务包 docs/tasks/T-*.md：$f（GIT-02）"; fails=1 ;;
      esac
    done < <(printf '%s\n' "$files")
  fi

  # Feature 附加校验：任务包存在、status=in-review、路径 ⊆「允许修改」
  if [ "$type" = "Feature" ]; then
    local task_list t st task_file allowed pat ok_match
    task_list=$(changed 'docs/tasks/T-*.md')
    if [ -z "$task_list" ]; then
      echo "[FAIL] SCOPE: Feature PR 必须包含任务包 docs/tasks/T-*.md 的变更（SCOPE-01 必需产物）"
      return 1
    fi
    while IFS= read -r t; do
      [ -z "$t" ] && continue
      if [ ! -f "$t" ]; then
        echo "[FAIL] SCOPE: 任务包 $t 在 HEAD 中不存在（DOC-04）"; fails=1; continue
      fi
      st=$(awk '/^---$/{n++; next} n==1 && /^status:/{print $2}' "$t")
      if [ "$st" != "in-review" ]; then
        echo "[FAIL] SCOPE: 任务包 $t status=${st}，须为 in-review（DOC-04）"; fails=1
      fi
    done <<< "$task_list"

    task_file=$(printf '%s\n' "$task_list" | head -n1)
    if [ -f "$task_file" ]; then
      allowed=$(awk '/^### 允许修改/{on=1} on && /^```/{ if (inblock) exit; inblock=1; next } inblock { print }' "$task_file" \
                | sed 's/#.*$//' | sed 's/[[:space:]]*$//' | grep -v '^$' || true)
      if [ -z "$allowed" ]; then
        echo "[FAIL] SCOPE: 任务包 ${task_file} 缺少「### 允许修改」路径清单（SCOPE-01）"; fails=1
      else
        while IFS= read -r f; do
          [ -z "$f" ] && continue
          case "$f" in docs/tasks/T-*.md) continue ;; esac
          ok_match=0
          while IFS= read -r pat; do
            [ -z "$pat" ] && continue
            # shellcheck disable=SC2254  # pat 有意作为 glob 模式使用
            case "$f" in $pat|$pat/*) ok_match=1 ;; esac
          done <<< "$allowed"
          if [ "$ok_match" -eq 0 ]; then
            echo "[FAIL] SCOPE: 文件 $f 不在任务包允许修改路径内（SCOPE-01）"; fails=1
          fi
        done < <(printf '%s\n' "$files")
      fi
    fi
  fi

  if [ "$fails" -eq 0 ]; then
    echo "scope 通过（类型：${type}）"
  else
    return 1
  fi
}

# ---------- static：静态规则，只查 diff 新增行（避免历史代码误报） ----------
do_static() {
  if ! base_ok; then echo "WARN: base ref 不存在，跳过 static"; return 0; fi

  # 产出新增行清单：file<TAB>new-line<TAB>content（行号取自 -U0 hunk 头）
  git diff -U0 --no-color "${BASE_REF}...HEAD" | awk '
    /^\+\+\+ b\// { file = substr($0, 7); next }
    /^@@ -/ { spec = $3; sub(/^\+/, "", spec); split(spec, parts, ","); line = parts[1] + 0; next }
    /^\+/ { if (file != "") print file "\t" line "\t" substr($0, 2); line++; next }
    { next }
  ' > "$workdir/added"
  : > "$workdir/findings"   # 预建空文件：无违规时 grep -c 也能正常计数

  # 单条规则：文件过滤 ERE（bash =~）+ 内容 ERE（grep -E），命中写入 findings
  rule() {
    local lvl="$1" id="$2" fre="$3" cre="$4" msg="$5" file lineno content
    while IFS=$'\t' read -r file lineno content; do
      [[ "$file" =~ $fre ]] || continue
      if printf '%s' "$content" | grep -Eq -- "$cre"; then
        printf '%s %s %s:%s: %s — %s\n' "$lvl" "$id" "$file" "$lineno" "$content" "$msg" >> "$workdir/findings"
      fi
    done < "$workdir/added"
  }

  # Mapper 禁止继承 BaseMapper/IService：SQL 全部写入 resources/mapper/*.xml
  rule FAIL DB-02 '\.java$' 'extends[[:space:]]+BaseMapper|implements[[:space:]]+IService|extends[[:space:]]+ServiceImpl|IService<' 'Mapper 禁止继承 BaseMapper/IService（DB-02/CODE-06），SQL 全部写入 resources/mapper/*.xml'
  # 业务服务禁止扫描公共包
  rule FAIL ARCH-04 '\.java$' '@ComponentScan\("com\.atifah' '业务服务禁止 @ComponentScan("com.atifah")（ARCH-04），公共能力经 AutoConfiguration 装配'
  # 后端控制台输出
  rule FAIL CODE-01 'src/main/.*\.java$' 'System\.out\.print|\.printStackTrace\(' '禁止 System.out / printStackTrace（CODE-01），日志用 @Slf4j 占位符'
  # 前端 console.log（敏感数据可能经控制台泄露）
  rule FAIL CODE-01 '\.(ts|vue)$' 'console\.log' '前端禁止 console.log（敏感数据与 frontend/AGENTS.md 安全约束）'
  # mapper XML 禁止 SELECT *
  rule FAIL DB-02 'mapper/[^/]*\.xml$' '[Ss][Ee][Ll][Ee][Cc][Tt][[:space:]]+\*' 'mapper XML 禁止 SELECT *（DB-02），显式列出字段并带 deleted = 0'
  # 网关依赖禁区：禁止 servlet 栈
  rule FAIL ARCH-03 'atifah-gateway/pom\.xml$' 'common-web|common-mybatis|common-redis|spring-boot-starter-web' '网关只依赖 common-core + common-security（ARCH-03），禁止 servlet 栈依赖'
  # 需人工确认的项：只警告，不阻塞
  rule WARN CODE-04 'src/main/.*\.java$' '@Autowired' '出现 @Autowired（CODE-04 要求构造注入），请确认为非字段注入用法'
  rule WARN CODE-08 '\.java$' '@SuppressWarnings' '出现 @SuppressWarnings（CODE-08），如非掩盖问题请在 PR 说明原因'
  rule WARN CODE-08 '\.(ts|vue|tsx)$' '@ts-ignore' '出现 @ts-ignore（CODE-08），如非掩盖问题请在 PR 说明原因'
  rule WARN CODE-01 '\.(ts|vue)$' 'console\.(debug|info|warn|error)' '前端 console 输出，请确认不含敏感数据'

  local fail_n warn_n
  fail_n=$(grep -c '^FAIL' "$workdir/findings" || true)
  warn_n=$(grep -c '^WARN' "$workdir/findings" || true)
  if [ "$fail_n" -gt 0 ] || [ "$warn_n" -gt 0 ]; then
    sort "$workdir/findings" >&2
  fi
  if [ "$fail_n" -gt 0 ]; then
    echo "[FAIL] static：${fail_n} 处静态规则违规"
    return 1
  fi
  echo "static 通过（WARN：${warn_n} 处，需在 PR 说明）"
}

# ---------- task：任务包 front matter 与基线标签 ----------
do_task() {
  if ! base_ok; then echo "WARN: base ref 不存在，跳过 task"; return 0; fi
  # 校验对象：diff 涉及的任务包；无任务包变更时兜底校验仓库内全部（数量小）
  local -a tasks=()
  mapfile -t tasks < <(changed 'docs/tasks/T-*.md')
  if [ "${#tasks[@]}" -eq 0 ]; then
    mapfile -t tasks < <(git ls-files 'docs/tasks/T-*.md')
    if [ "${#tasks[@]}" -eq 0 ]; then
      echo "INFO: 无任务包，跳过 task"
      return 0
    fi
  fi

  local mainref
  mainref=$(git rev-parse -q --verify refs/remotes/origin/main 2>/dev/null || git rev-parse -q --verify refs/heads/main 2>/dev/null || true)

  local fails=0 t fm id status updated cb
  for t in "${tasks[@]}"; do
    if [ ! -f "$t" ]; then
      echo "WARN: $t 在 HEAD 中不存在（已删除？），跳过"
      continue
    fi
    fm=$(awk '/^---$/{n++; next} n==1 {print} n==2 {exit}' "$t")
    id=$(fm_get id "$fm")
    status=$(fm_get status "$fm")
    updated=$(fm_get updated "$fm")
    cb=$(fm_get contract_baseline "$fm")
    if [[ ! "$id" =~ ^T-[0-9]{3}$ ]]; then
      echo "[FAIL] DOC-04 ${t}: id 格式非法（应为 T-<三位编号>）：'${id}'"; fails=1
    fi
    case "$status" in
      draft|ready|in-review|done|blocked) ;;
      *) echo "[FAIL] DOC-04 ${t}: status 非法（五状态枚举）：'${status}'"; fails=1 ;;
    esac
    if [[ ! "$updated" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
      echo "[FAIL] DOC-04 ${t}: updated 需为 YYYY-MM-DD：'${updated}'"; fails=1
    fi
    if [[ "$cb" =~ ^arch-baseline-v[0-9]+\.[0-9]+$ ]]; then
      if [ -n "$mainref" ]; then
        if ! git rev-parse -q --verify "refs/tags/${cb}" >/dev/null 2>&1; then
          echo "[FAIL] DOC-04 ${t}: 基线标签 ${cb} 不存在（契约未冻结，不可进入实现）"; fails=1
        elif ! git merge-base --is-ancestor "refs/tags/${cb}" "$mainref" 2>/dev/null; then
          echo "[FAIL] DOC-04 ${t}: 标签 ${cb} 不是 main 的祖先（基线未合入默认分支）"; fails=1
        fi
      else
        echo "WARN: 找不到 main 引用，跳过基线标签存在性校验"
      fi
    else
      echo "[FAIL] DOC-04 ${t}: contract_baseline 格式非法：'${cb}'"; fails=1
    fi
  done

  if [ "$fails" -eq 0 ]; then
    echo "task 通过（校验 ${#tasks[@]} 个任务包）"
  else
    return 1
  fi
}

# ---------- size：生产语义 diff 体积（SCOPE-02 口径：生产代码/SQL/配置/契约/规则计入；
# ---------- 测试、生成文件与其余治理文档不计，docs/contracts 与 docs/rules 全量计入） ----------
do_size() {
  if ! base_ok; then echo "WARN: base ref 不存在，跳过 size"; return 0; fi
  local code_total doc_total total
  code_total=$(git diff --numstat "${BASE_REF}...HEAD" -- \
    pom.xml atifah-common atifah-gateway atifah-services atifah-admin-web/backend \
    atifah-admin-web/frontend/src atifah-app deploy \
    ':(exclude)**/src/test/**' ':(exclude)**/node_modules/**' ':(exclude)**/dist/**' \
    ':(exclude)**/target/**' ':(exclude)**/__tests__/**' ':(exclude)**/*.test.ts' \
    ':(exclude)**/*.spec.ts' ':(exclude)**/*.md' \
    | awk '$1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+$/ { add += $1; del += $2 } END { print add + del }')
  # 契约与规则按 SCOPE-02 全量计入（不能用 :(exclude) 排除 .md 后再放行：exclude 恒优先）
  doc_total=$(git diff --numstat "${BASE_REF}...HEAD" -- docs/contracts docs/rules \
    | awk '$1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+$/ { add += $1; del += $2 } END { print add + del }')
  total=$((code_total + doc_total))
  if [ -z "$total" ] || [ "$total" -eq 0 ]; then
    echo "INFO: 无生产语义变更（docs-only 或空 diff），跳过体积门禁"
    return 0
  fi
  echo "生产语义 diff 行数（近似）：${total} / 400"
  if [ "$total" -gt 400 ]; then
    echo "[FAIL] SCOPE-02：超出 400 行，请拆分任务/PR（测试代码已不计入；一次性基线导入由维护者在 PR 说明豁免）"
    return 1
  fi
  echo "size 通过"
}

# ---------- compose：配置语法校验（CI 无本地 .env 时用一次性哑变量，不启动容器） ----------
do_compose() {
  local env_args=() f
  if [ -f deploy/docker-compose/.env ]; then
    env_args=(--env-file deploy/docker-compose/.env)
  else
    cat > "$workdir/compose.env" <<'EOF'
# 仅用于 config 语法校验的哑变量（非真实密钥，不用于启动容器）
NACOS_AUTH_TOKEN=dummy-token
EOF
    env_args=(--env-file "$workdir/compose.env")
  fi
  for f in deploy/docker-compose/docker-compose.yml deploy/docker-compose/docker-compose.app.yml; do
    if [ ! -f "$f" ]; then echo "WARN: 缺少 $f，跳过"; continue; fi
    echo "校验 $f"
    docker compose "${env_args[@]}" -f "$f" config -q
  done
  echo "compose 配置校验通过"
}

case "$cmd" in
  detect)  do_detect ;;
  scope)   do_scope ;;
  static)  do_static ;;
  task)    do_task ;;
  size)    do_size ;;
  compose) do_compose ;;
  *) echo "未知子命令：$cmd。用法: ci-gates.sh <detect|scope|static|task|size|compose>"; exit 2 ;;
esac
