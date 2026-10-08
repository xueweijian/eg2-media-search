#!/bin/sh
# 本地语法冒烟（push 前必跑）：kotlinc 无 classpath 编译全部 .kt。
# 白名单策略：只报"无 classpath 也必然为真"的错误（语法/控制流/修饰符/重声明），
# 类型推断类错误可能是依赖缺失的级联（Bitmap 等 Unresolved 所致），交给 CI。
# kotlinc 2.1（项目用 2.4，语法层兼容；此脚本目的是拦截低级错误，不替代 CI）。
KOTLINC=/opt/kotlinc/kotlinc/bin/kotlinc
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

FILES=$(find "$ROOT/app/src/main" "$ROOT/app/src/test" "$ROOT/app/src/androidTest" -name '*.kt' 2>/dev/null)
[ -z "$FILES" ] && echo "no kotlin files" && exit 1

OUT=$($KOTLINC $FILES -d /tmp/kc-smoke-out 2>&1)

# 确定性错误白名单（历史翻车实录：挤行/缺return/companion误用/when尾逗号/重复声明）
REAL=$(echo "$OUT" | grep ": error:" | grep -iE \
  -e "syntax error" \
  -e "expecting" \
  -e "unexpected" \
  -e "missing return" \
  -e "modifier .* is not applicable" \
  -e "redeclaration" \
  -e "conflicting" \
  -e "must be initialized" \
  -e "type parameter .* hides" \
  -e "'when' branch" \
)

TOTAL_ERR=$(echo "$OUT" | grep -c ": error:" || true)
if [ -n "$REAL" ]; then
  echo "======== 确定性错误（必须修，push 前清零） ========"
  echo "$REAL"
  echo "======== 共 $(echo "$REAL" | wc -l) 处（另有 $((TOTAL_ERR - $(echo "$REAL" | wc -l))) 处依赖级联噪声交 CI） ========"
  exit 1
fi
echo "语法冒烟通过 ✓（$TOTAL_ERR 处依赖级联噪声已交由 CI 判定）"
