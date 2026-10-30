#!/usr/bin/env bash
# Stop フックから呼ばれ、現在のセッションの会話を読みやすい Markdown として docs/logs/ に書き出す。
# 入力: stdin に Claude Code のフック JSON（transcript_path, session_id）
# 出力: <リポジトリルート>/docs/logs/<日付>_<セッションID先頭8桁>.md（毎回全体を再生成）
#
# git に残すため、次のものはログに含めない:
#   - ツールの出力（コマンド結果・ファイル内容）と思考過程
#   - system-reminder（アカウント情報などの環境コンテキスト）
#   - メールアドレス、API キー/トークン、パスワード類、秘密鍵、緯度経度の組（伏せ字にする）
set -euo pipefail

input=$(cat)
transcript=$(jq -r '.transcript_path // empty' <<<"$input")
session=$(jq -r '.session_id // empty' <<<"$input")
[ -n "$transcript" ] && [ -f "$transcript" ] || exit 0

log_dir="${CLAUDE_PROJECT_DIR:-$(pwd)}/../docs/logs"
mkdir -p "$log_dir"

first_ts=$(jq -rn 'first(inputs | select(.timestamp) | .timestamp) // empty' "$transcript")
date_part=$(TZ=Asia/Tokyo date -d "${first_ts:-now}" +%Y-%m-%d)
out="$log_dir/${date_part}_${session:0:8}.md"

jq -rs -f "$(dirname "$0")/export-transcript.jq" "$transcript" | {
  echo "# 会話ログ ${date_part} (session ${session:0:8})"
  echo
  echo "_Claude Code の Stop フックで自動生成。ツールの出力と思考過程は省略し、メールアドレス・トークン・パスワード・位置座標は伏せ字にしています。_"
  echo
  cat
} > "$out.tmp" && mv "$out.tmp" "$out"
